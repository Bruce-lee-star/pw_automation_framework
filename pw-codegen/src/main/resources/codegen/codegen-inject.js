/*
 * codegen-inject.js —— 注入被测页（addInitScript，随导航自动重挂）
 *
 * 角色/可访问名计算保留自 Playwright roleUtils.ts 的"轻量"子集（role 判定 + accessible name 含文本回退）。
 * 本脚本只负责把元素事实（role / name / 属性 / 文本 / 同级序号）连同"上溯父级链"打包回 Java；
 * <b>打分选择 / 唯一性校验 / 父级嵌套降级</b>全部在 Java 侧 {@code CodegenScorer} 完成（可纯单元测试、不依赖浏览器）。
 * recorder 监听真实用户操作，经 window.__codegenEvent 桥回 Java（Step 生成后续迭代，见 CodegenRecorder）。
 */
(function () {
    'use strict';
    if (window.__CODEGEN__) return;

    // 2026-11-13 / 2026-11-15：记录最近一次「会被 label 转发」的勾选类控件，用于抑制 label 默认行为
    // 向控件转发的「合成 click」——否则点一次 radio/checkbox 的 label（含 label 内任意文本/svg）
    // 会同时生成两条元素定位。
    var __codegenLastLabel = null;

    /**
     * 元素身份（文档标识 + 元素标识）：供 Java 侧 {@code CodegenLauncher#matchFieldKey} 判定
     * 「这次操作的是不是<b>已经拾取过的那一个</b>元素」。
     *
     * <p><b>为什么不能用 role+name 判等</b>：页面里常有「双胞胎按钮」（同一表单的两处
     * {@code <button>Continue</button>}、分区各一个 Next/Cancel）。它们的 role+name 完全一致，
     * 用 role+name 反查会把<b>第二个按钮的操作记到第一个元素上</b>，用户看到的就是
     * 「点第二个按钮没有任何定位生成」——拾取被静默吞掉，且日志里连一行痕迹都没有。
     *
     * <p>文档标识每次文档加载重新生成（导航后元素集合完全不同，跨文档复用 eid 会误判成同一元素）；
     * eid 用 {@link WeakMap} 绑定节点对象，节点本身被回收即自动释放，不产生泄漏。
     * 两者一起构成「同一文档里的同一个节点」——这正是去重该用的粒度：<b>路径/文案变了仍认同一元素，
     * 路径不同（哪怕是同 role+name 的孪生元素）绝不合并</b>。
     */
    var __codegenDocId = (function () {
        try {
            if (window.crypto && typeof window.crypto.randomUUID === 'function') {
                return window.crypto.randomUUID();
            }
        } catch (e) { }
        return 'd' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
    })();
    var __codegenEidSeq = 0;
    var __codegenEids = (typeof WeakMap === 'function') ? new WeakMap() : null;

    /** 取（首次调用则分配）元素在本文档内的唯一标识；无 WeakMap 环境退化为空串（Java 侧回退旧判据）。 */
    function eidOf(el) {
        if (!el || el.nodeType !== 1 || !__codegenEids) return '';
        try {
            var id = __codegenEids.get(el);
            if (id) return id;
            id = 'e' + (++__codegenEidSeq);
            __codegenEids.set(el, id);
            return id;
        } catch (e) {
            return '';
        }
    }

    /*__LOCATOR_CORE__*/

    /**
     * 目标自身稳定属性谓词（XPath 形态）：{@code [@id="…"]} / {@code [@data-i18n="…"]} / {@code [@name="…"]} …
     * 用于构造锚定的 XPath。无稳定属性时返回空数组（由调用方补空串）。
     */
    function xpathSelfAttrPreds(e) {
        var preds = [];
        if (e.id && !isDynamicId(e.id)) preds.push('[@id="' + attrEsc(e.id) + '"]');
        var attrs = stableAttrs(e);
        for (var i = 0; i < attrs.length; i++) {
            if (attrs[i].rand) continue;
            preds.push('[@' + attrs[i].name + '="' + attrEsc(attrs[i].value) + '"]');
        }
        return preds;
    }

    /** 把 CSS 锚点段（{@code [data-i18n="X"]} / {@code #id}）转成 XPath 谓词；descendant 时用 {@code .//*} 后代轴。 */
    function cssAnchorToXpath(anchor, descendant) {
        if (!anchor) return null;
        var inner;
        if (anchor.charAt(0) === '#') {
            inner = '[@id="' + attrEsc(anchor.substring(1)) + '"]';
        } else if (anchor.charAt(0) === '[') {
            inner = '[@' + anchor.substring(1);   // [data-i18n="X"] → [@data-i18n="X"]
        } else {
            return null;
        }
        return descendant ? '[.//*' + inner + ']' : inner;
    }

    /** XPath 唯一性校验：结果类型 7（SNAPSHOT）+ {@code snapshotLength === 1}（结果类型 9 的 singleNodeValue 在
     * 快照类型上按规范抛 InvalidStateError，故用快照 + 长度判定，与 Java 侧 buildExpression 的快照分支同源）。 */
    function uniqueXPath(x) {
        try {
            var snap = document.evaluate(x, document, null, 7, null);
            return !!(snap && snap.snapshotLength === 1);
        } catch (e) { return false; }
    }

    /**
     * 稳健 XPath 兜底候选：借<b>父级 / 子级属性</b>定位唯一元素，避免退化为脆弱的绝对路径
     * （{@code /html/body/div[9]/div[10]/…} 随 modal 在 body 里的序号漂移而失效）。每个候选都在文档内
     * 校验「恰好命中 1 个」才返回。
     *
     * <p>两类：
     * <ul>
     *   <li>子级锚点——目标自身含稳定后代属性（如按钮内的 {@code data-i18n} span）：
     *       {@code //button[.//*[@data-i18n="forgot_username_button"]]}。</li>
     *   <li>父级锚点——祖先的稳定属性（id / data-testid / data-i18n / aria-label …）或祖先内部的后代锚点
     *       （面板标题的 data-i18n）把作用域收窄到正确容器，再配目标自身属性：
     *       {@code //div[@data-i18n="forgot_username_title"]//span[@data-i18n="forgot_username_button"]}。</li>
     * </ul>
     * 与 CSS 的 {@code :has()} 思路同源（父级作用域 + 目标），但 XPath 在「祖先作用域 + 目标」组合上表达更直接；
     * 仅在 CSS 候选（含 descendantAnchorSel / 祖先作用域前缀）都失效时才被 scorer 采用。
     */
    function robustXPaths(e) {
        if (!e || e.nodeType !== 1) return [];
        var selfTag = e.tagName.toLowerCase();
        var selfPreds = xpathSelfAttrPreds(e);
        if (selfPreds.length === 0) selfPreds = [''];
        var out = [];
        // 子级锚点：目标自身含稳定后代属性
        var childAnchors = descendantAnchorsOf(e, 3, 4);
        for (var ci = 0; ci < childAnchors.length && out.length === 0; ci++) {
            var cp = cssAnchorToXpath(childAnchors[ci], true);
            if (!cp) continue;
            for (var s = 0; s < selfPreds.length; s++) {
                var xc = '//' + selfTag + selfPreds[s] + cp;
                if (uniqueXPath(xc)) { out.push(xc); break; }
            }
        }
        // 父级锚点：祖先的稳定属性或祖先内部后代锚点 + 目标自身属性
        var p = e.parentElement, depth = 0;
        while (p && p.nodeType === 1 && depth < 8 && out.length === 0) {
            var ancTag = p.tagName.toLowerCase();
            var preds = xpathSelfAttrPreds(p);
            // 排除目标子树：父级锚点若取自目标内部的 span/aria 描述，产出的
            // `//div[.//*[@data-i18n="continue"]]//button` 与"按钮属于哪个分区"无关。
            var achild = descendantAnchorsOf(p, 3, 4, e);
            var real = [];
            for (var a = 0; a < preds.length; a++) real.push(preds[a]);
            for (var b = 0; b < achild.length; b++) {
                var acp = cssAnchorToXpath(achild[b], true);
                if (acp) real.push(acp);
            }
            if (real.length === 0) { p = p.parentElement; depth++; continue; }   // 祖先无锚点，跳过
            for (var c = 0; c < real.length && out.length === 0; c++) {
                for (var s2 = 0; s2 < selfPreds.length; s2++) {
                    var xp = '//' + ancTag + real[c] + '//' + selfTag + selfPreds[s2];
                    if (uniqueXPath(xp)) { out.push(xp); break; }
                }
            }
            p = p.parentElement; depth++;
        }
        return out;
    }

    /**
     * 本次点击是否会让浏览器向「勾选类控件」补发一次合成 click？
     *
     * <p>只有 {@code <label>} 的激活行为会对 radio/checkbox 派发 click（文本框只聚焦、不派发 click），
     * 且必须是 {@link nestedControlOf} 认定的<b>被包裹</b>控件。返回 {@code {ctrl, t}} 供随后的
     * 合成 click 抑制；其余情形返回 {@code null}。
     */
    function forwardedControl(target, resolved) {
        if (!target || !resolved || resolved === target) return null;
        var ty = ((resolved.getAttribute && resolved.getAttribute('type')) || '').toLowerCase();
        if (ty !== 'radio' && ty !== 'checkbox') return null;
        var lb = (target.tagName === 'LABEL') ? target
            : (target.closest ? target.closest('label') : null);
        if (!lb) return null;
        if (nestedControlOf(lb) !== resolved) return null;
        return { ctrl: resolved,
                 t: (typeof performance !== 'undefined') ? performance.now() : Date.now() };
    }

    /** 单个元素的事实快照（Java 侧打分所需原始材料）。 */
    function factsOf(el) {
        if (!el || el.nodeType !== 1) return null;
        var tag = el.tagName ? el.tagName.toLowerCase() : '';
        return {
            // 元素身份（文档 + 元素）：Java 侧据此判定「是不是已拾取过的同一个元素」。
            // 必须随每次事件回传——同一元素即使结构路径/文案被页面改写（loading、列表插入兄弟节点），
            // eid 不变；而 role+name 相同的孪生元素 eid 必然不同，不会被并成一条。
            docId: __codegenDocId,
            eid: eidOf(el),
            role: roleOf(el),
            name: accessibleName(el),
            tag: tag,
            id: el.id || '',
            testid: (el.getAttribute && el.getAttribute('data-testid')) || '',
            type: ((el.getAttribute && el.getAttribute('type')) || '').toLowerCase(),
            placeholder: (el.getAttribute && el.getAttribute('placeholder')) || '',
            alt: (el.getAttribute && el.getAttribute('alt')) || '',
            title: (el.getAttribute && el.getAttribute('title')) || '',
            value: (typeof el.value === 'string') ? el.value : '',
            checked: checkedStateOf(el),
            text: normalizedText(el),
            label: labelTextOf(el),
            nth: siblingIndex(el),
            cssSelector: cssSel(el),
            shadowHosts: shadowHostsOf(el),
            className: (typeof el.className === 'string') ? el.className : '',
            xpath: absXPath(el),
            nameAttr: (el.getAttribute && el.getAttribute('name')) || '',
            src: (el.getAttribute && el.getAttribute('src')) || '',
            attrs: semanticAttrsOf(el, 10),
            // 目标自身的「后代锚点」：供 Java 侧 CodegenScorer 生成
            // `tag:has([data-i18n="x"])` 短形式（零结构层级、跨语言稳定）。
            // 历史缺口：该字段只在 ancestorFacts 里采集，self 侧恒为空 ⇒ scorer 无法为
            // 「自身无稳定属性、但内部有唯一语义锚点」的按钮/容器生成 :has() 短形式，
            // 只能退化成按 div 序号拼的长结构链（modal footer 这类 `button` 直接子级页面必成死链）。
            // 深度 3 / 最多 6 个与祖先侧口径一致，节点访问上限 200 保证成本可控。
            childAnchors: descendantAnchorsOf(el, 3, 6)
        };
    }

    /**
     * 父级嵌套降级候选：每项是「从该祖先到目标的完整路径」（段链以 {@code >} 连接、层级严格连续）。
     *
     * <p><b>必须整条替换 base，不能当 base 的前缀拼接</b>：{@link cssSel} 是"上溯最多 6 层"的<b>窗口</b>，
     * 两个窗口相拼会重叠，产出 {@code div:nth-of-type(7) > body:nth-of-type(1)} 这类不可能的层级，
     * 生成的 CSS 永远匹配不到元素（历史 bug）。
     *
     * <p>v2.12 收紧：<b>每一层</b>都必须有稳定 id / 动态 id 前缀 / 稳定属性段（含组内索引）——
     * 目标层或任一中间层是裸 tag:nth-of-type(n) 段时整条链作废（返回空数组），不再作为 CSS 候选，
     * 交给 Java 侧 XPath / role 兜底；「锚点 + 中间裸 tag 段」的链同样不产出。
     *
     * <p>只回传 {@code cssSelector}：祖先不需要 role/name/xpath/shadowHosts —— 为每层重复算全量事实
     * 是白付多轮 DOM 遍历。
     */
    
    window.__CODEGEN__ = {
        /** 富事实：self + 父级链；Java 侧 CodegenScorer 据此打分/唯一性/嵌套。 */
        getInfo: function (el) {
            var target = pickTarget(el);
            var self = factsOf(target);
            if (!self) return null;
            return { self: self, ancestors: ancestorChain(target, 8), robustXpaths: robustXPaths(target) };
        },
        recorder: {
            /** 启动录制监听（幂等：脚本在每个新文档都会跑，重复 start 不得重复注册）。 */
            start: function () {
                if (window.__CODEGEN_RECORDER_STARTED__) return;
                window.__CODEGEN_RECORDER_STARTED__ = true;
                // 只取元素自身事实（factsOf）；不要用 getInfo —— 它还会额外算 6 层祖先链，
                // 而录制只需要 self，白白让每次 click/input 多做一遍 DOM 遍历（大页面上表现为输入卡顿）。
                // 同元素缓存：一个输入框连续输入（每个按键一次 input 事件）时，元素的静态结构
                // （cssSelector / role / name / id …）不变，只有 value 在变；value 由事件顶层字段单独回传 Java，
                // 故这里缓存 factsOf 结果、每次刷新 value 即可，省掉每个按键一整轮 DOM 遍历。
                var _lastInfoEl = null, _lastInfo = null;
                function infoOf(node) {
                    try {
                        if (node === _lastInfoEl && _lastInfo) {
                            _lastInfo.value = (typeof node.value === 'string') ? node.value : '';
                            return _lastInfo;
                        }
                        var info = factsOf(pickTarget(node));
                        if (info) { _lastInfoEl = node; _lastInfo = info; }
                        return info;
                    } catch (e) { return null; }
                }
                /**
                 * 元素所属 frame 的稳定路径：从内到外每层 iframe 在其父文档中的序号（outer→inner）。
                 * 例：[2, 0] = 「顶层文档第 3 个 iframe」里的「第 1 个 iframe」。
                 * 与 Java 侧 Page.getFrameTree 的 childFrames 文档顺序一致，可据此映射到 CDP frameId，从而只探命中帧、
                 * 省掉全 frame 扫描（iframe 多时显著降低每拾取 CDP 往返）。
                 * 跨源 iframe 内 frameElement 为 null（安全限制）⇒ 返回 null，Java 侧回退全帧扫描（保持跨源正确）；
                 * 顶层文档返回 []（空路径 = 主 frame）。
                 */
                function framePathOf() {
                    try {
                        var path = [];
                        var w = window;
                        while (w && w !== w.top) {
                            var fe = w.frameElement;            // 父文档里的 <iframe>（跨源父时为 null）
                            if (!fe) return null;
                            var doc = fe.ownerDocument;
                            if (!doc || !doc.querySelectorAll) return null;
                            // iframe 与旧式 frameset 的 frame 同属「子浏览上下文」，序号映射到 CDP frameId 时
                            // 由 Page.getFrameTree 的 childFrames 文档顺序对齐——只数 iframe 会漏 frame。
                            var iframes = doc.querySelectorAll('iframe, frame');
                            var idx = -1;
                            for (var i = 0; i < iframes.length; i++) {
                                if (iframes[i] === fe) { idx = i; break; }
                            }
                            if (idx < 0) return null;
                            path.unshift(idx);                  // 外层在前
                            w = w.parent;
                        }
                        return path;
                    } catch (e) { return null; }
                }
                // 注意：这里绝不 preventDefault / stopPropagation —— 拾取必须与页面交互共存
                // （早先版本用 CDP Overlay.setInspectMode 拦截鼠标，导致「一进拾取页面就点不动」）。
                // 注册在 window 捕获层而非 document：跨域官网弹窗（如 business.hsbc.com.hk 的
                // privacy-and-security / terms-of-use）带 consent/analytics 全局脚本，会在更早阶段
                // stopPropagation 吞掉点击，document 捕获层监听收不到（症状：新页面 enable 后
                // 点击零日志、拾取无显示）。本脚本经 context.addInitScript 注入，先于页面任何脚本执行，
                // 故 window 捕获监听总是最早注册 —— 页面脚本的 stopImmediatePropagation 拦不到我们。
                window.addEventListener('click', function (ev) {
                    try {
                        var target = ev.target;
                        // 框架自绘 UI（控制条按钮等）不参与录制与拾取：本监听在捕获阶段先于按钮自身
                        // 的 onclick 执行，若照常上报，「点暂停」会把暂停按钮自己拾取进面板
                        // （此刻 Java 侧 pickOn 尚未被 pause 命令置 false）。
                        if (isCodegenUi(target)) return;
                        // 2026-11-13 / 2026-11-15：<label> 被点击时，浏览器会向关联控件
                        // （radio/checkbox）再派发一次「合成 click」（label 默认行为）。该合成 click
                        // 与真实点击几乎同时、目标即控件；若照常上报会生成「label + 控件」两条记录
                        // （用户 2026-11-15 反馈：点 radio 的 label 会产出两个元素定位）。
                        // 这里抑制被 label 转发的控件 click，仅保留用户真正点的那一次（归一后的控件），
                        // 绝不 preventDefault —— 真实勾选/聚焦照常发生。
                        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'
                                || target.tagName === 'SELECT')) {
                            var now = (typeof performance !== 'undefined') ? performance.now() : Date.now();
                            if (__codegenLastLabel && __codegenLastLabel.ctrl === target
                                    && (now - __codegenLastLabel.t) < 120) {
                                __codegenLastLabel = null;
                                return;
                            }
                        }
                        // 诊断计数：Java 侧事件泵据此区分「DOM 事件没进来」与「事件进来了但 binding 没派发」。
                        window.__CODEGEN_CLICK_COUNT__ = (window.__CODEGEN_CLICK_COUNT__ || 0) + 1;
                        var resolved = null;
                        try { resolved = pickTarget(target); } catch (ignore) { resolved = null; }
                        // 只上报「事件目标 + 其事实（选择器 / XPath / frame 路径）」：拾取按选择器逐 frame 求值，
                        // 绝不带坐标——坐标随滚动/重排漂移，且对跨源 iframe 必然打空。
                        window.__codegenEvent({ type: 'click', tag: target && target.tagName,
                            framePath: framePathOf(),
                            info: infoOf(target) });
                        // 登记「即将到来的合成 click」：点包裹式 label（或其内部任意文本/svg）且归一到的控件是
                        // radio/checkbox 时，浏览器随后会向该控件补发一次 click，必须抑制掉（否则两条定位）。
                        // 直接点控件本身不会触发转发，故不做登记——避免把用户随后的真实点击误吞。
                        __codegenLastLabel = forwardedControl(target, resolved);
                    } catch (e) {}
                }, true);
                // 右键（contextmenu）：与 click 同层的拾取入口，动作由生成侧映射为 rightClick()。
                // 绝不 preventDefault —— 浏览器右键菜单照常弹出（拾取必须与页面交互共存）。
                // 不做 label 合成抑制（右键不会向关联控件转发 click），故不需要 __codegenLastLabel 登记。
                window.addEventListener('contextmenu', function (ev) {
                    try {
                        var target = ev.target;
                        if (isCodegenUi(target)) return;
                        window.__CODEGEN_CLICK_COUNT__ = (window.__CODEGEN_CLICK_COUNT__ || 0) + 1;
                        window.__codegenEvent({ type: 'contextmenu', tag: target && target.tagName,
                            framePath: framePathOf(),
                            info: infoOf(target) });
                    } catch (e) {}
                }, true);
                window.addEventListener('input', function (ev) {
                    try {
                        if (isCodegenUi(ev.target)) return;   // 框架自绘 UI 不录制
                        window.__codegenEvent({ type: 'fill', tag: ev.target && ev.target.tagName,
                            framePath: framePathOf(),
                            value: ev.target.value, info: infoOf(ev.target) });
                    } catch (e) {}
                }, true);
                // 语义事件（评审 P0-4 最小集）：change 是 select / 文本域 / file 的权威值事件，
                // 不再依赖 Chromium「select 变更会连带触发 input」这一实现细节。
                // checkbox / radio 不在此上报：click 已携带 checked，这里只会产生 value="on" 伪数据。
                window.addEventListener('change', function (ev) {
                    try {
                        if (isCodegenUi(ev.target)) return;   // 框架自绘 UI 不录制
                        var el = ev.target;
                        var kind = (el && el.type ? String(el.type).toLowerCase() : '');
                        if (kind === 'checkbox' || kind === 'radio') return;
                        var semantic = 'change';
                        var values = null;
                        if (kind === 'file') {
                            // 安全模型只暴露 C:\fakepath\xxx：这个值落进产物就是「录得出、跑不了」。
                            // 标记为 file 动作、值留空，由生成侧出 uploadFile("TODO…") 提示人工补资源路径。
                            semantic = 'file';
                        } else if (el && el.multiple && el.selectedOptions) {
                            // 多选 select 的 el.value 只是首项；全量放进 values，生成侧据此提示其余项。
                            values = Array.prototype.map.call(el.selectedOptions,
                                function (o) { return o.value; });
                            semantic = 'multiple';
                        }
                        var single = kind === 'file' ? ''
                            : (values && values.length ? values[0]
                                : ((typeof el.value === 'string') ? el.value : ''));
                        window.__codegenEvent({ type: 'fill', semantic: semantic, tag: el && el.tagName,
                            framePath: framePathOf(),
                            value: single, valueCount: values ? values.length : 1,
                            info: infoOf(el) });
                    } catch (e) {}
                }, true);
                // 键盘：只有 Enter / Escape / Tab 生成可回放动作，逐字符输入仍由 input 承载，不污染录制。
                // 修饰键按 Playwright 的 "Control+Enter" 形式拼接，否则 Ctrl+Enter 会退化成普通回车。
                window.addEventListener('keydown', function (ev) {
                    try {
                        if (isCodegenUi(ev.target)) return;   // 控制条内的按键不录制
                        var key = (ev.key === 'Enter' || ev.key === 'Escape' || ev.key === 'Tab') ? ev.key : null;
                        if (!key) return;
                        var mods = [];
                        if (ev.ctrlKey) mods.push('Control');
                        if (ev.metaKey) mods.push('Meta');
                        if (ev.altKey) mods.push('Alt');
                        if (ev.shiftKey) mods.push('Shift');
                        var combo = mods.concat([key]).join('+');
                        var el2 = ev.target;
                        window.__codegenEvent({ type: 'press', value: combo, tag: el2 && el2.tagName,
                            framePath: framePathOf(), info: infoOf(el2) });
                    } catch (e) {}
                }, true);
            }
        }
    };

    /**
     * 是否为框架自绘 UI（被测页内的控制条 / 拾取高亮框）。
     *
     * <p><b>为什么必须排除</b>：控制条按钮本身就在被测页 DOM 里，而录制监听注册在 window
     * <b>捕获</b>层——它先于按钮自身的 onclick（发送 control 命令）执行。于是「点暂停」这一下
     * 会先被当成业务点击上报给 Java，此时 Java 侧 {@code pickOn} 仍是 true，暂停按钮就被拾取进
     * 面板（停止 / 继续按钮同理）。这不是拾取门控失效，而是框架 UI 被当成业务元素录制，
     * 必须在事件源头排除。
     *
     * <p>判定用 {@code data-codegen-ui} 标记 + id 双保险：标记便于将来新增自绘 UI 复用；
     * id 兜底旧版本注入（或热更新前已存在的）节点。
     */
    function isCodegenUi(node) {
        try {
            var n = node;
            while (n && n.nodeType === 1) {
                if (n.hasAttribute && n.hasAttribute('data-codegen-ui')) return true;
                var id = n.id;
                if (id === '__codegen_statusbar__' || id === '__codegen_highlight__') return true;
                n = n.parentElement;
            }
        } catch (e) { }
        return false;
    }

    // ---- 拾取悬停高亮（纯 JS 自绘）----
    // 旧版靠 CDP Overlay.setInspectMode 顺带提供高亮，但那个模式会吞掉鼠标事件（页面点不动），
    // 故改为自绘：只画一个 pointer-events:none 的框，绝不参与事件流，不影响页面交互。
    var HL_ID = '__codegen_highlight__';
    var hlAttached = false;
    var hlLast = null;

    function hlBox() {
        var d = document.getElementById(HL_ID);
        if (d) return d;
        d = document.createElement('div');
        d.id = HL_ID;
        d.setAttribute('data-codegen-ui', '1');   // 标记为框架 UI：录制/拾取/悬停一律跳过
        d.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;display:none;'
            + 'border:2px solid rgba(59,130,246,.95);background:rgba(59,130,246,.10);'
            + 'border-radius:2px;box-sizing:border-box;';
        var label = document.createElement('span');
        label.style.cssText = 'position:absolute;left:0;top:-19px;background:#2563eb;color:#fff;'
            + 'font:11px/17px -apple-system,"Segoe UI",sans-serif;padding:0 6px;border-radius:3px;'
            + 'white-space:nowrap;max-width:320px;overflow:hidden;text-overflow:ellipsis;';
        d.appendChild(label);
        (document.body || document.documentElement).appendChild(d);
        return d;
    }

    // 高亮标签只取「便宜」的事实：标签名 + role + 可访问名（均不遍历兄弟/祖先链），
    // 绝不用 factsOf（会上溯 6 层算 cssSel、64 层算 absXPath、遍历属性）——否则每次 mouseover 都重做一遍 DOM 遍历，
    // 鼠标一移动浏览器主线程就被打满，整机随即是卡死。
    function hlLabelOf(el) {
        var text = el.tagName ? el.tagName.toLowerCase() : '';
        try {
            var role = roleOf(el);
            if (role && role !== 'generic') text += ' · ' + role;
            var name = accessibleName(el);
            if (name) text += ' "' + String(name).slice(0, 40) + '"';
        } catch (e) { }
        return text;
    }

    var hlCurrent = null;   // 当前已高亮的元素：同一元素重入 mouseover 直接跳过
    var hlPending = null;   // rAF 待刷新目标：合并同一帧内的多次 mouseover/scroll
    var hlRaf = 0;

    function hlFlush() {
        hlRaf = 0;
        var el = hlPending;
        hlPending = null;
        if (!el || el.nodeType !== 1) return;
        hlCurrent = el;
        hlLast = el;
        try { hlShow(el); } catch (e) { }
    }

    function hlSchedule(el) {
        hlPending = el;
        if (hlRaf !== 0) return;
        if (window.requestAnimationFrame) {
            hlRaf = window.requestAnimationFrame(hlFlush);
        } else {
            hlRaf = 1;
            setTimeout(hlFlush, 16);
        }
    }

    function hlShow(el) {
        if (!el || el.nodeType !== 1) return;
        var r = el.getBoundingClientRect();
        if (!r || (r.width === 0 && r.height === 0)) return;
        var d = hlBox();
        d.style.display = 'block';
        d.style.left = r.left + 'px';
        d.style.top = r.top + 'px';
        d.style.width = r.width + 'px';
        d.style.height = r.height + 'px';
        d.firstChild.textContent = hlLabelOf(el);
    }

    function hlOnOver(ev) {
        var el = ev.target;
        if (!el || el.nodeType !== 1 || el.id === HL_ID) return;
        if (isCodegenUi(el)) return;           // 别给框架自己的控制条画拾取框
        if (el === hlCurrent) return;          // 同一元素：mouseover 在元素内移动会高频重入，跳过
        hlSchedule(el);
    }

    function hlOnScroll() {
        if (hlCurrent) hlSchedule(hlCurrent);  // 滚动/缩放时只重定位，标签文本复用上次结果
    }

    function hlAttach() {
        if (hlAttached) return;
        hlAttached = true;
        document.addEventListener('mouseover', hlOnOver, true);
        window.addEventListener('scroll', hlOnScroll, true);
        window.addEventListener('resize', hlOnScroll, true);
    }

    function hlDetach() {
        if (!hlAttached) return;
        hlAttached = false;
        hlCurrent = null;
        hlLast = null;
        hlPending = null;
        if (hlRaf) {
            if (window.cancelAnimationFrame) window.cancelAnimationFrame(hlRaf);
            hlRaf = 0;
        }
        document.removeEventListener('mouseover', hlOnOver, true);
        window.removeEventListener('scroll', hlOnScroll, true);
        window.removeEventListener('resize', hlOnScroll, true);
        var d = document.getElementById(HL_ID);
        if (d) d.style.display = 'none';
    }

    /**
     * 拾取高亮开关（幂等）。Java 事件泵每 120ms 同步一次：页面导航 / 新标签页后本页标志会丢，
     * 靠它自动恢复；暂停拾取时立即隐藏，不留残框。
     */
    window.__CODEGEN_SET_HIGHLIGHT__ = function (on) {
        if (on === window.__CODEGEN_HL_ON__) return;
        window.__CODEGEN_HL_ON__ = on;
        try { on ? hlAttach() : hlDetach(); } catch (e) { }
    };

    // ---- 被测页控制条（对标 page.pause 的调试栏）：开始 / 继续 / 停止，且实时显示拾取态 ----
    // 与面板命令同源：按钮经 window.__codegenEvent({type:'control', action:...}) 回传 Java，
    // 复用与面板 Start/Pause/Stop 完全相同的会话控制逻辑。state: 'ready' | 'picking' | 'paused' | 'hidden'。
    window.__CODEGEN_SET_STATUS__ = function (state) {
        try {
            if (state === 'hidden') {
                var old = document.getElementById('__codegen_statusbar__');
                if (old) old.parentNode && old.parentNode.removeChild(old);
                return;
            }
            var bar = document.getElementById('__codegen_statusbar__');
            if (!bar) {
                bar = document.createElement('div');
                bar.id = '__codegen_statusbar__';
                bar.setAttribute('data-codegen-ui', '1');   // 标记为框架 UI：其按钮的点击不录制、不拾取
                bar.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:2147483646;'
                    + 'pointer-events:auto;display:flex;align-items:center;gap:10px;height:34px;padding:0 12px;'
                    + 'font:600 12px/34px -apple-system,"Segoe UI",sans-serif;letter-spacing:.2px;'
                    + 'background:#1f2937;color:#e5e7eb;box-shadow:0 1px 6px rgba(0,0,0,.45);';
                var txt = document.createElement('span');
                txt.style.cssText = 'flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
                var btnToggle = document.createElement('button');
                btnToggle.type = 'button';
                btnToggle.style.cssText = BTN_CSS();
                var btnStop = document.createElement('button');
                btnStop.type = 'button';
                btnStop.textContent = '停止';
                btnStop.style.cssText = BTN_CSS() + 'background:#7f1d1d;color:#fff;border-color:#b91c1c;';
                btnStop.onclick = function () { sendControl('stop'); };
                bar.appendChild(txt);
                bar.appendChild(btnToggle);
                bar.appendChild(btnStop);
                bar.__txt = txt;
                bar.__btnToggle = btnToggle;
                (document.body || document.documentElement).appendChild(bar);
            }
            var toggle = bar.__btnToggle;
            toggle.onclick = function () {
                sendControl(bar.dataset.state === 'picking' ? 'pause' : 'start');
            };
            if (state === 'ready') {
                bar.__txt.textContent = '就绪 · 点「开始拾取」后点击页面元素即被记录';
                toggle.textContent = '开始拾取';
            } else if (state === 'picking') {
                bar.__txt.textContent = '拾取中 · 点击元素加入 Elements；点「暂停」仅停止收集元素，交互与录制保持';
                toggle.textContent = '暂停拾取';
            } else { // paused
                // 措辞要能自解释「为什么点了页面按钮却没有新代码」：暂停 = 不收集新元素，
                // 只是页面交互与「已拾取元素」的操作仍被记录（旧文案"录制保持"容易被读成"点击照常收集"）。
                bar.__txt.textContent = '已暂停 · 不再收集新元素（页面可正常操作），点「继续拾取」恢复';
                toggle.textContent = '继续拾取';
            }
            bar.dataset.state = state;
        } catch (e) { }
    };

    function BTN_CSS() {
        return 'cursor:pointer;border:1px solid #374151;border-radius:4px;padding:3px 12px;'
            + 'font:600 12px/1 -apple-system,"Segoe UI",sans-serif;background:#2563eb;color:#fff;';
    }
    function sendControl(action) {
        try { if (window.__codegenEvent) window.__codegenEvent({ type: 'control', action: action }); } catch (e) { }
    }

    // 录制器随脚本自动启动：本脚本经 addInitScript 在每个新文档运行（也由 inject 立即在当前文档求值），
    // 故导航/新页都会自动重挂监听，Java 侧不需要（也无法可靠地）逐页调用 start。
    // 若 __codegenEvent 绑定尚未建立，事件会被 internal try/catch 丢弃，不影响页面。
    try { window.__CODEGEN__.recorder.start(); } catch (e) { }
})();

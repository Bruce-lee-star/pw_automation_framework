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

    // 2026-11-13：记录最近一次 label 点击及其关联控件，用于抑制 label 默认行为向控件
    // 转发的「合成 click」——否则点 label 会同时生成 label 与控件两条记录。
    var __codegenLastLabel = null;

    /** ARIA role 判定（显式 role 或 HTML 标签映射）。 */
    function roleOf(e) {
        var r = e.getAttribute && e.getAttribute('role');
        if (r) return r.toLowerCase();
        var t = e.tagName ? e.tagName.toLowerCase() : '';
        if (t === 'button') return 'button';
        if (t === 'a' && e.getAttribute('href')) return 'link';
        if (t === 'input') {
            // 按 HTML-AAM 的隐式 role 映射。绝不能一律兜底成 textbox：
            // hidden/file/color/date 等没有隐式 role，判成 textbox 会生成
            // @RoleElement(role = TEXTBOX)，而 Playwright 的 getByRole 对它们恒 0 命中（静默失效）。
            var ty = (e.getAttribute('type') || 'text').toLowerCase();
            if (ty === 'checkbox') return 'checkbox';
            if (ty === 'radio') return 'radio';
            if (ty === 'submit' || ty === 'button' || ty === 'reset' || ty === 'image') return 'button';
            if (ty === 'range') return 'slider';
            if (ty === 'number') return 'spinbutton';
            if (ty === 'search') return 'searchbox';
            // 无隐式 role（含 hidden）：归为 generic（下游按"无语义角色"回退 CSS）；
            // hidden 元素同时不在 AX 树里，role 定位永远不成立。
            if (ty === 'hidden' || ty === 'file' || ty === 'color'
                    || ty === 'date' || ty === 'time' || ty === 'month'
                    || ty === 'week' || ty === 'datetime-local') return 'generic';
            return 'textbox';
        }
        if (t === 'textarea') return 'textbox';
        if (t === 'select') return e.getAttribute('multiple') != null ? 'listbox' : 'combobox';
        if (/^h[1-6]$/.test(t)) return 'heading';
        if (t === 'img') return 'img';
        if (t === 'nav') return 'navigation';
        if (t === 'main') return 'main';
        if (t === 'table') return 'table';
        if (t === 'li') return 'listitem';
        if (t === 'label') return 'label';
        return 'generic';
    }

    /** 可访问名（accessible name）：aria-label → aria-labelledby → label[for] → 包裹 label → title 兜底。 */
    function accessibleName(e) {
        var a = e.getAttribute && e.getAttribute('aria-label');
        if (a && a.trim()) return a.trim();
        if (e.id) {
            var byId = document.getElementById(e.id);
            if (byId && byId !== e) {
                var bid = byId.getAttribute('aria-label');
                if (bid && bid.trim()) return bid.trim();
            }
            var l = document.querySelector('label[for="' + (window.CSS ? CSS.escape(e.id) : e.id) + '"]');
            if (l && l.textContent.trim()) return l.textContent.trim();
        }
        if (e.tagName && e.tagName.toLowerCase() === 'img') {
            var alt = e.getAttribute('alt');
            if (alt && alt.trim()) return alt.trim();
        }
        // name from content（button/link/heading 等允许从子内容算 name；不截断长度，完整文本才利于 NLS 反查）
        var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
        if (t) return t;
        var title = e.getAttribute && e.getAttribute('title');
        if (title && title.trim()) return title.trim();
        return '';
    }

    /** 折叠空白、去换行后的可见文本（截断护栏）。 */
    function normalizedText(e) {
        var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
        return t.length > 240 ? t.substring(0, 240) : t;
    }

    /** 关联 <label> 文本（含 label[for] 与包裹 label）。 */
    function labelTextOf(e) {
        if (e.id) {
            var l = document.querySelector('label[for="' + (window.CSS ? CSS.escape(e.id) : e.id) + '"]');
            if (l && l.textContent.trim()) return l.textContent.trim();
        }
        var p = e.parentElement;
        if (p && p.tagName && p.tagName.toLowerCase() === 'label' && p.textContent.trim()) {
            return p.textContent.trim();
        }
        return '';
    }

    /**
     * 动态/随机 id 识别：此类 id 每次渲染都会变（Radix、Headless UI、react-select、MUI、antd 等），
     * 直接落进 CSS 选择器会产出「录制时唯一、回放必挂」的定位器。命中即视为无稳定 id，
     * 由调用方降级为 tag:nth-of-type 结构链或 role/name 定位。规则取保守前缀黑名单，避免误伤
     * password / username 这类稳定 id。
     */
    function isDynamicId(id) {
        if (!id || !id.length) return false;
        if (/^_R_/.test(id) || /_R_[A-Za-z0-9]{4,}_/.test(id)) return true; // Radix: _R_kmub7m_ / dropdown-list-_R_kmub7m_
        if (/:r\d+:/.test(id)) return true;                                // Radix v1/v2: :r1:
        if (/^headlessui-/.test(id)) return true;    // Headless UI
        if (/^react-select-/.test(id)) return true;  // react-select
        if (/^mui-\d+$/.test(id)) return true;       // MUI: mui-12345
        if (/^[a-z]+(-[a-z]+)*-\d{4,}$/.test(id)) return true; // 通用: 任意段前缀 + ≥4 位数字（antd 类）
        if (/\d{4,}-id$/.test(id)) return true;                // 通用: 段内随机数字 + 字面量 -id 后缀（如 modal-forgot_username_title46223-id）
        if (/^[a-z]+(-[a-z]+)*\d{4,}$/.test(id)) return true;  // 通用: 段前缀 + ≥4 位尾随数字（无连字符，如 listitem1234）
        if (/[0-9a-f]{8,}/i.test(id)) return true;             // 十六进制/哈希 token（如 a1b2c3d4e5 / deadbeef）
        if (/[A-Za-z0-9]{8,}/.test(id)) {                      // 高熵 base62 token：≥8 位无分隔符，且（含大小写+≥2 数字）或（≥3 数字），且数字非纯尾随
            var d = (id.match(/[0-9]/g) || []).length;
            if (/[0-9][A-Za-z]/.test(id) && ((/[A-Z]/.test(id) && /[a-z]/.test(id) && d >= 2) || d >= 3)) return true;
        }
        return false;
    }

    /**
     * 动态 id 的稳定前缀：截掉随机夹心（Radix {@code _R_xxxx_}/ {@code :rN:}、数字后缀）后，
     * 剩余部分在组件语义上稳定（如 {@code dropdown-list-_R_kmub7m_} → {@code dropdown-list-}）。
     * 无稳定前缀时返回空串。
     */
    function stableIdPrefix(id) {
        var m = id.match(/^(.*?)(?:_R_[A-Za-z0-9]{4,}_|:r\d+:|-\d{4,}|\d{4,}$|[0-9a-f]{8,})/i);
        return m ? m[1] : '';
    }

    /** 动态 id 的稳定后缀：截掉开头随机段（Radix 夹心/`:rN:`/数字段+连字符）后，剩余尾部在组件语义上稳定。 */
    function stableIdSuffix(id) {
        var m = id.match(/^(?:_R_[A-Za-z0-9]{4,}_|:r\d+:|\d{4,}-|[0-9a-f]{8,})(.*)$/i);
        return m ? m[1] : '';
    }

    function attrEsc(v) {
        return String(v).replace(/["\\]/g, function (c) { return '\\' + c; });
    }
    /** 可见性判定：hidden、aria-hidden="true"、display:none / visibility:hidden（含祖先，经计算样式）、
     * visibility:collapse、opacity:0（含祖先链，不继承需逐级查）、clip 裁零 / clip-path 裁零、
     * 零尺寸或整体移出视口（响应式/移动端隐藏副本常见）均视为隐藏。
     *
     * <p><b>祖先链必须逐级查 display / visibility</b>（2026-11-11）：{@code getComputedStyle(child).display}
     * 返回的是该元素自身的计算值，父级 {@code display:none} 不会传递下来——只看自身会把「藏在
     * {@code style="display: none;"} 面板里的副本」判成可见。祖先深度上限 10 层（5 层截断会漏判）。
     *
     * <p><b>本函数只回答「渲染是否可见」，不等于「应从候选排除」</b>：{@code class="accessibleHidden"} /
     * {@code .sr-only} 这类元素渲染不可见，但<b>仍在可访问性树里</b>（{@code getByRole} 能命中，往往是唯一的
     * 可访问名来源）。把「不可见」当成「垃圾副本」去排除，等于丢掉无障碍语义。
     * 只有「从渲染树剔除 ⇒ 同时从 AX 树剔除」的形态（{@code display:none} / {@code visibility:hidden} /
     * {@code hidden} 属性 / {@code aria-hidden="true"}）才可以作为排除依据。
     */
    function isVisible(e) {
        if (!e || e.nodeType !== 1) return false;
        if (e.hidden) return false;
        if (e.getAttribute && e.getAttribute('aria-hidden') === 'true') return false;
        if (e.style && (e.style.display === 'none' || e.style.visibility === 'hidden')) return false;
        if (typeof window !== 'undefined' && typeof window.getComputedStyle === 'function') {
            try {
                var cs = window.getComputedStyle(e);
                if (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse') return false;
                if (cs.opacity === '0' || cs.opacity === '0.0' || cs.opacity === '0.00') return false;
                if (cs.clip && (cs.clip === 'rect(0px, 0px, 0px, 0px)' || cs.clip.indexOf('rect(0') === 0)) return false;
                if (cs.clipPath && (/inset\(0%\)/.test(cs.clipPath) || /inset\(50%\)/.test(cs.clipPath) || /circle\(0/.test(cs.clipPath))) return false;
                var p = e.parentElement, d = 0;
                while (p && p.nodeType === 1 && d < 10) {
                    var pcs = window.getComputedStyle(p);
                    if (pcs.display === 'none' || pcs.visibility === 'hidden' || pcs.visibility === 'collapse') return false;
                    if (pcs.opacity === '0' || pcs.opacity === '0.0' || pcs.opacity === '0.00') return false;
                    p = p.parentElement; d++;
                }
            } catch (err) { }
        }
        if (typeof e.getBoundingClientRect === 'function') {
            try {
                var r = e.getBoundingClientRect();
                if (!r || (r.width === 0 && r.height === 0)) return false;
                var vw = (typeof window !== 'undefined' && window.innerWidth) || 0;
                var vh = (typeof window !== 'undefined' && window.innerHeight) || 0;
                if (vw > 0 && vh > 0 && (r.right <= 0 || r.bottom <= 0 || r.left >= vw || r.top >= vh)) return false;
            } catch (err) { }
        }
        return true;
    }


    /**
     * 匹配总数（<b>含隐藏元素</b>）。
     *
     * <p>Playwright 的 {@code locator(sel)} 不做可见性过滤，strict mode 下多匹配直接抛错，
     * 故唯一性必须按 DOM 全量计数。只数可见会漏掉隐藏的重复副本——典型如隐藏面板里那个
     * 同名 "Continue" 按钮：拾取时被判唯一，运行期面板一显示立刻变成多匹配
     * （2026-11-11：重复面板定位不准的根因）。
     */
    function totalCount(sel) {
        try {
            if (!sel) return 0;
            return document.querySelectorAll(sel).length;
        } catch (e) { return 0; }
    }

    /**
     * 唯一性校验：DOM 全量匹配恰好 1 个（隐藏元素同样参与计数，对齐 Playwright strict mode）。
     * 绝不使用 Playwright 专有可见性伪类（原生 {@code document} 不识别，会抛 SyntaxError）。
     */
    function uniqueSel(sel) {
        return totalCount(sel) === 1;
    }

    /**
     * CSS 选择器<b>语法合法性护栏</b>：拦截一切非法选择器，杜绝"未定位到"的畸形串——
     * 典型如把两个属性塞进同一个方括号 {@code [data-i18n="x" data-i18n="x"]}（缺 {@code ][}，属非法 CSS，
     * {@code querySelectorAll} 抛 {@code SyntaxError} 或零匹配）。
     *
     * <p>两层校验：
     * <ul>
     *   <li><b>结构探针（零误伤）</b>：单个 {@code [ ]} 内出现「属性条件 + 空白 + 属性条件」（中间无 {@code ]}）
     *       即非法，直接判废。引号内的空格不算（{@code =} 与下一个 {@code =} 之间无空白），
     *       故 {@code [class*="btn primary"]} 这类合法选择器不受影响。</li>
     *   <li><b>浏览器解析兜底</b>：{@code document.querySelectorAll(sel)} 在语法错误时抛 {@code SyntaxError}，
     *       捕获即判废，覆盖结构探针未穷举的其它畸形形态。</li>
     * </ul>
     *
     * <p>只校验<b>语法</b>，不校验唯一性 / 匹配数量（那由 {@link uniqueSel} 等负责）。本函数为纯防御层：
     * 即便上游构造器出现回归式畸形拼接，{@link cssSel} 也会将其判废并继续走下一层级 / XPath 兜底，
     * 绝不把畸形串写进 {@code CodegenEntry.cssSelector}。
     */
    function validCssSel(sel) {
        if (typeof sel !== 'string' || !sel) return false;
        // 单括号内多属性条件（缺 ][）：[ ...=... 空白 ...=... ]
        if (/\[[^\]]*=[^\]]*\s+[^\]]*=/.test(sel)) return false;
        try {
            document.querySelectorAll(sel);
            return true;
        } catch (e) {
            return false;
        }
    }


    /**
     * 动态 id 的 CSS 兜底：优先稳定前缀 {@code [id^="…"]}（组件语义前缀，如 {@code dropdown-list-}），
     * 前缀不唯一时再试包含 {@code [id*="…"]}（去尾部分隔符的核心词）。每个候选都做文档唯一性校验，
     * 唯一才采用 —— 保证生成的选择器回放时命中且不歧义；全部不唯一则返回空串交给结构链兜底。
     */
    function dynamicIdFallbackSel(id) {
        var prefix = stableIdPrefix(id);
        if (prefix) {
            var byPrefix = '[id^="' + attrEsc(prefix) + '"]';
            if (uniqueSel(byPrefix)) return byPrefix;
        }
        var suffix = stableIdSuffix(id);
        if (suffix) {
            var bySuffix = '[id$="' + attrEsc(suffix) + '"]';
            if (uniqueSel(bySuffix)) return bySuffix;
        }
        var core = (prefix || suffix).replace(/^[-_]+|[-_]+$/g, '');
        if (core) {
            var byCore = '[id*="' + attrEsc(core) + '"]';
            if (uniqueSel(byCore)) return byCore;
        }
        return '';
    }

    /** 取 src 尾部文件名（URL 末段，去 query/hash）；data URI / 空值返回空串。 */
    function srcTail(s) {
        if (!s) return '';
        s = String(s).trim();
        if (s.indexOf('data:') === 0) return '';
        var q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        var h = s.indexOf('#'); if (h >= 0) s = s.substring(0, h);
        var sl = s.lastIndexOf('/'); if (sl >= 0) s = s.substring(sl + 1);
        return s.length >= 3 ? s : '';
    }

    /**
     * 语言无关的 {@code src} 文件名前缀：文件名尾部带语言/地区段（{@code _EN} / {@code _zh-HK} /
     * {@code .en_US}）时返回该段<b>之前</b>的部分，否则返回空串。
     *
     * <p>动机：HSBC 图片按语言分文件——{@code img_username_EN.png} / {@code img_username_ZH.png} /
     * {@code img_username_TC.png}。用 {@code [src$="img_username_EN.png"]} 定位，用户一切语言整条就失效。
     * 改成 {@code img[src*="img_username"]} 可覆盖全部语言变体。
     *
     * <p>只吃<b>最后一个</b>语言段：{@code Search_HSBC_HK_Business_Express_EN} → 保留到
     * {@code Search_HSBC_HK_Business_Express}（中间 {@code _HK} 属业务名）。
     * 前缀 &lt; 4 字符不归一化（{@code *=} 过宽，易在日后资源增多时误命中）。
     */
    var SRC_TRAILING_LOCALE = /^(.{4,}?)[._-](?:en|zh|tc|sc|hk|cn|tw)(?:[._-](?:us|gb|hk|cn|tw|hans|hant))?$/i;
    function srcLocaleNeutralPrefix(tail) {
        if (!tail) return '';
        var stem = String(tail).replace(/[.][A-Za-z0-9]{1,5}$/, '');
        var m = SRC_TRAILING_LOCALE.exec(stem);
        if (!m) return '';
        var prefix = m[1].replace(/[._-]+$/, '');
        return prefix.length >= 4 ? prefix : '';
    }

    /** class 里的稳定类名（空格切分，滤掉框架随机 token 与过短碎片）。 */
    function stableClassTokens(cls) {
        var out = [], seen = {};
        if (!cls) return out;
        var raw = String(cls).trim().split(/\s+/);
        for (var i = 0; i < raw.length; i++) {
            var t = raw[i];
            if (t.length < 3) continue;
            if (/^(css|sc)-[a-z0-9]{5,}$/i.test(t)) continue;      // Emotion css-xxx
            if (/^[a-z0-9]{8,}$/i.test(t)) continue;                // 长 hash
            if (/_R_[A-Za-z0-9]{4,}_/.test(t)) continue;            // Radix 夹心
            if (/^[a-z]+(-[a-z]+)*-\d{4,}$/.test(t)) continue;      // 数字后缀
            if (seen[t]) continue;
            seen[t] = true;
            out.push(t);
        }
        return out;
    }
    /** 属性值稳定段：值无随机段 → 精确(=)；含随机段（Radix 夹心 / :rN: / 数字段）→ 稳定前缀 ^=、核心 *=、稳定后缀 $=（段自身无随机段、长度 2~40，避免随机值；^= → *= → $= 顺序）。 */
    /**
     * 共享「随机属性值/动态段」判定（单一事实来源，被 {@link attrValueSegments} 与 {@link stableAttrs} 复用，避免两处正则漂移）。
     * 命中即视为随机段：Radix 夹心（{@code _R_xxxx_}/ {@code :rN:}）、通用 4+ 位数字+连字符、长数字串
     * （≥6 位连续数字，无需连字符，如 {@code 123456}/ {@code prefix123456}）、长十六进制/哈希串（≥10 位，如
     * {@code a1b2c3d4e5}/ {@code deadbeef}）、高熵 base62 token（≥8 位无分隔符且字母数字混杂、数字非纯尾随，如
     * {@code aZ3kM9xQ}/ {@code k7m9p2q5x}）。
     */
    var RAND_RE = /_R_[A-Za-z0-9]{4,}_|:r\d+:|\d{4,}-|-\d{4,}|\d{6,}|[0-9a-fA-F]{10,}|(?=[A-Za-z0-9]*[0-9][A-Za-z])(?:(?=(?:[^0-9]*[0-9]){3})|(?=[^A-Z]*[A-Z])(?=[^a-z]*[a-z])(?=(?:[^0-9]*[0-9]){2}))[A-Za-z0-9]{8,}/;

    function attrValueSegments(v) {
        if (!v) return [];
        v = String(v).trim();
        if (!v || v.length > 40) return [];
        var rand = RAND_RE;
        if (!rand.test(v)) return [{ op: '=', seg: v }];
        var parts = v.split(rand);
        var head = (parts[0] || '').replace(/^[-_ :]+|[-_ :]+$/g, '');
        var tail = (parts[parts.length - 1] || '').replace(/^[-_ :]+|[-_ :]+$/g, '');
        var core = parts.join(' ').replace(/\s+/g, ' ').trim();
        var out = [];
        if (head.length >= 2 && !rand.test(head)) out.push({ op: '^=', seg: head });
        if (core.length >= 2 && core !== head && !rand.test(core)) out.push({ op: '*=', seg: core });
        if (tail.length >= 2 && tail !== head && tail !== core && !rand.test(tail)) out.push({ op: '$=', seg: tail });
        return out;
    }

    /** 稳定属性候选（id/class/style/src 各走专档）：值非空且 ≤40 字符、非纯数字长串；含随机段的属性不再丢弃，标 rand=1（值用 ^=/*=$= 变体）；稳定（rand=0）排前、按文档顺序，上限 6。 */
    function stableAttrs(e) {
        var out = [], seen = {};
        var attrs = e.attributes;
        if (!attrs) return out;
        var rand = RAND_RE;
        for (var i = 0; i < attrs.length; i++) {
            var a = attrs[i];
            var n = a.name;
            if (n === 'id' || n === 'class' || n === 'style' || n === 'src') continue;
            var v = a.value;
            if (!v) continue;
            v = String(v).trim();
            if (!v || v.length > 40) continue;
            if (/^[\d.,-]+$/.test(v) && v.length > 6) continue;
            if (seen[n]) continue;
            seen[n] = true;
            out.push({ name: n, value: v, rand: rand.test(v) ? 1 : 0 });
        }
        out.sort(function (a, b) { return a.rand - b.rand; });
        if (out.length > 6) out.length = 6;
        return out;
    }

    /** 可见匹配计数（唯一性之外，用于识别"一组元素"场景）。 */
    function visibleCount(sel) {
        try {
            if (!sel) return 0;
            var nodes = document.querySelectorAll(sel), n = 0;
            for (var i = 0; i < nodes.length; i++) if (isVisible(nodes[i])) n++;
            return n;
        } catch (e) { return 0; }
    }

    /** 组内索引：候选在父下同 tag 兄弟中的第 k 个（1 起），产出 {@code sel:nth-of-type(k)}；无父/无 tag 返回空串。 */
    function groupIndexSel(sel, el) {
        var parent = el && el.parentElement;
        if (!parent || !el.tagName) return '';
        var tag = el.tagName.toLowerCase();
        var k = 0, sibs = parent.children || [];
        for (var i = 0; i < sibs.length; i++) {
            var c = sibs[i];
            if (c && c.nodeType === 1 && c.tagName && c.tagName.toLowerCase() === tag) {
                k++;
                if (c === el) return sel + ':nth-of-type(' + k + ')';
            }
        }
        return '';
    }

    /**
     * 唯一或组内索引。
     *
     * <p>① 全量唯一（含隐藏）直接用；② 否则只要存在「有限个可见匹配」就尝试 {@code :nth-of-type(k)} 收窄，
     * 再用全量计数复核。② 的下界是 {@code > 0} 而非 {@code > 1}：隐藏面板 + 可见面板各一个同名
     * "Continue" 时可见计数只有 1（旧逻辑直接放弃 → 退化成长结构路径），组内索引能稳定收窄。
     */
    /**
     * 祖先作用域收窄（隐藏副本短路的补丁）。
     *
     * <p>当候选 {@code sel} 只因「隐藏副本」全量非唯一、且同级 {@code :nth-of-type} 无法区分（目标在父级内是唯一同 tag
     * 元素、没有同级兄弟）时，把 {@code sel} 挂到最近的、能让「组合选择器全量计数 === 1」的祖先作用域上，例如
     * {@code .modal-abc button[aria-label="Close"]} 或 {@code div:nth-of-type(2) button[aria-label="Close"]}。
     *
     * <p><b>关键约束</b>：只接受组合后全量唯一的结果——既解除隐藏副本导致的短路，又不破坏 Playwright strict mode
     * （绝不引入 2026-11-11 重复面板 bug：隐藏面板一旦显示立刻变多匹配的回归）。祖先段在此函数内<b>自包含计算</b>，
     * 不回调 {@link attrFallbackSel}（避免与 {@link uniqueOrGroup} 互为递归）；祖先段优先稳定 id → 动态 id 前缀/包含
     * → 单稳定 class token → 单稳定属性 → 兜底 {@code :nth-of-type}（位置），由内向外逐级尝试。
     *
     * <p><b>子元素锚点</b>：当 {@code sel} 本身不是 {@code :has} 形式时，候选集额外叠加「后代锚点」候选
     * （{@link descendantAnchorCandidates}）——在全局同类元素众多、目标自身属性被隐藏副本淹没时，按钮内的子元素
     * （如图标 {@code span[data-i18n="X"]}）往往更精确；它们同样只在「组合后全量唯一」时才会被接受。
     */
    /**
     * 给作用域祖先段附加「排除隐藏副本」判别符，返回首个能比原段更窄（剔除隐藏副本）者；无则空串。
     * <p>覆盖本项目常见的隐藏模态/面板形态：自身或容器带 {@code d-none}（Bootstrap/Tailwind）、{@code aria-hidden="true"}、
     * {@code hidden} 属性、行内 {@code display:none}。仅当附加后该段确实更窄（{@code totalCount} 下降）才采纳，
     * 组合后的<b>全局唯一性</b>由调用方复核——不破坏 Playwright strict mode。
     */
    // 隐藏副本判别符（按优先级）：d-none / aria-hidden / hidden / 行内 display:none。
    // 全部为原生合法 CSS —— 绝不使用 Playwright 专有可见性伪类（原生 document 不识别，会抛 SyntaxError）。
    var HIDDEN_EXCLUDES = [':not(.d-none)', ':not([aria-hidden="true"])', ':not([hidden])',
        ':not([style*="display: none"])', ':not([style*="display:none"])'];
    function scopeSegExcludingHidden(seg) {
        if (!seg) return '';
        for (var i = 0; i < HIDDEN_EXCLUDES.length; i++) {
            var ex = HIDDEN_EXCLUDES[i];
            var s2 = seg + ex;
            // 仅在「判别符确实剔除了隐藏副本（总匹配下降且仍 >0）」时采纳，避免误把可见副本也排除。
            var c2 = totalCount(s2);
            if (c2 > 0 && c2 < totalCount(seg)) return s2;
        }
        return '';
    }

    function scopeUnderUniqueAncestor(sel, e) {
        if (!sel || !e) return '';
        // 候选集：自身选择器排首位；若自身不是后代锚点形式，再叠加「子元素锚点」候选——当全局同类元素众多、
        // 自身属性被隐藏副本淹没时，子元素（如按钮内图标）往往更精确。
        var cands = [sel];
        if (sel.indexOf(':has') === -1) {
            var dacs = descendantAnchorCandidates(e);
            for (var d = 0; d < dacs.length; d++) cands.push(dacs[d]);
        }
        var n = e.parentElement;
        var guard = 0;
        while (n && n.nodeType === 1 && guard++ < 12) {
            var t = n.tagName ? n.tagName.toLowerCase() : '';
            if (!t || t === 'html') break;
            var seg = '';
            if (n.id && !isDynamicId(n.id)) {
                seg = '#' + (window.CSS ? CSS.escape(n.id) : n.id);
            } else if (n.id) {
                var pf = dynamicIdFallbackSel(n.id);
                if (pf) seg = pf;
            }
            if (!seg && t) {
                var cls = (typeof n.className === 'string') ? n.className : '';
                var toks = stableClassTokens(cls);
                for (var i = 0; i < toks.length && !seg; i++) {
                    var ts = t + '[class*="' + attrEsc(toks[i]) + '"]';
                    if (uniqueSel(ts)) seg = ts;
                }
            }
            if (!seg && t) {
                var nas = stableAttrs(n);
                for (var a = 0; a < nas.length && !nas[a].rand && !seg; a++) {
                    var as = t + '[' + nas[a].name + '="' + attrEsc(nas[a].value) + '"]';
                    if (uniqueSel(as)) seg = as;
                }
            }
            if (!seg) {
                var f = fallbackSeg(n);
                if (f) seg = f;
            }
            if (seg) {
                var isDirectParent = (n === e.parentElement);
                for (var c = 0; c < cands.length; c++) {
                    var combined = seg + ' ' + cands[c];
                    if (uniqueSel(combined)) return combined;
                    if (isDirectParent) {
                        // 直接父级 + 子代轴（>）：隐藏副本常是目标的兄弟，用父级差异（如 body vs div[display:none]）
                        // 即可把「该父级下唯一」的目标单独摘出——这是「父级标签+属性」兜底档。
                        var childCombined = seg + ' > ' + cands[c];
                        if (uniqueSel(childCombined)) return childCombined;
                        // 父级自身带隐藏判别符（父级 display:none / d-none …）时，子代轴进一步收窄。
                        var parentRefined = scopeSegExcludingHidden(seg);
                        if (parentRefined) {
                            var prc = parentRefined + ' > ' + cands[c];
                            if (uniqueSel(prc)) return prc;
                        }
                    }
                    // 隐藏副本短路补丁（生成侧）：候选仅因「隐藏副本」全量非唯一、且可见命中恰 1 时，
                    // 给作用域祖先段附加排除隐藏副本的原生判别符，把选择器收窄到可见副本，恢复 strict mode 唯一性。
                    if (visibleCount(combined) === 1) {
                        var refined = scopeSegExcludingHidden(seg);
                        if (refined) {
                            var rc = refined + ' ' + cands[c];
                            if (uniqueSel(rc)) return rc;
                        }
                    }
                }
            }
            n = n.parentElement;
        }
        return '';
    }

    function uniqueOrGroup(sel, e, allowScope) {
        if (allowScope === undefined) allowScope = true;
        if (!sel || !e) return '';
        if (uniqueSel(sel)) return sel;
        if (!allowScope) return '';
        var cnt = visibleCount(sel);
        if (cnt > 0 && cnt <= 10) {
            var g = groupIndexSel(sel, e);
            if (g && uniqueSel(g)) return g;
            // 隐藏副本导致的非唯一：尝试祖先作用域收窄（组合后须仍全量唯一，严格模式安全）。
            var sc = scopeUnderUniqueAncestor(sel, e);
            if (sc) return sc;
            // 祖先作用域也失效时（隐藏副本与可见副本祖先结构相同）：本层自身即「可见命中恰 1」，
            // 直接用排除隐藏副本的判别符收窄本层自身段，恢复 strict mode 唯一性（如可见模态 div.forgot-x:not(.d-none)）。
            if (cnt === 1) {
                var refined = scopeSegExcludingHidden(sel);
                if (refined) return refined;
            }
        }
        return '';
    }
    /**
     * 目标元素属性兜底（在结构链之前）：无 id / 动态 id 无法唯一时，先试
     * {@code img[src$="文件名"]}、稳定 class token / class 双 token AND，然后按「最稳定优先」用属性：
     * ① 稳定属性（值无随机段）精确 {@code [name="value"]}；② 稳定属性双→三精确 AND；③ 含随机值属性
     * 用开头 {@code ^=}、包含 {@code *=}、结尾 {@code $=} 变体（段自身无随机值）；④ 组合层：单属性仍
     * 不唯一时多个属性共同定位——稳定属性精确 AND 随机变体（最稳定优先）、随机变体 × 随机变体 AND。
     * 每个候选先做可见唯一性校验——唯一即采用；可见不唯一（一组元素）时取组内索引；全部失败返回空串交给结构链/XPath。
     *
     * <p>{@code allowScope} 控制是否允许在「自身选择器不可见唯一」时挂到祖先作用域收窄
     * （见 {@link uniqueOrGroup} / {@link scopeUnderUniqueAncestor}）：{@code true}（默认，结构链分段与 CDP
     * 路径使用）允许祖先作用域兜底；{@code false}（{@code cssSel} 的「自身」层使用）只接受不挂祖先即全局唯一的
     * 自身选择器，从而把「子元素锚点」「父/祖先作用域」两档严格排在它之后——先自身、再子元素、最后父元素。
     */
    function attrFallbackSel(e, allowScope) {
        if (allowScope === undefined) allowScope = true;
        if (!e || e.nodeType !== 1) return '';
        var t = e.tagName.toLowerCase();
        if (t === 'img') {
            var tail = srcTail(e.getAttribute && e.getAttribute('src'));
            if (tail) {
                // 语言变体文件名（xxx_EN.png）改锚定语言段之前的前缀，否则切语言后 src 换名即失效
                var neutral = srcLocaleNeutralPrefix(tail);
                var byTail = neutral
                    ? 'img[src*="' + attrEsc(neutral) + '"]'
                    : 'img[src$="' + attrEsc(tail) + '"]';
                var s0 = uniqueOrGroup(byTail, e, allowScope);
                if (s0) return s0;
            }
        }
        var cls = (typeof e.className === 'string') ? e.className : '';
        var toks = stableClassTokens(cls);
        for (var i = 0; i < toks.length; i++) {
            var byCls = t + '[class*="' + attrEsc(toks[i]) + '"]';
            var s1 = uniqueOrGroup(byCls, e, allowScope);
            if (s1) return s1;
        }
        // class 双 token AND：单 token 不唯一时，两个稳定 token 组合常能收敛。
        for (var i = 0; i < toks.length && i < 4; i++) {
            for (var j = i + 1; j < toks.length && j < 4; j++) {
                var byCls2 = t + '[class*="' + attrEsc(toks[i]) + '"][class*="' + attrEsc(toks[j]) + '"]';
                var s2 = uniqueOrGroup(byCls2, e, allowScope);
                if (s2) return s2;
            }
        }
        // 跨属性：按「最稳定优先」用属性——稳定属性（值无随机段）单精确 → 双/三精确 AND → 含随机值属性 ^=/*=$= 变体。
        var names = stableAttrs(e);
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            var by1 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"]';
            var s3 = uniqueOrGroup(by1, e, allowScope);
            if (s3) return s3;
        }
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            for (var j = i + 1; j < names.length && !names[j].rand; j++) {
                var by2 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"][' + names[j].name + '="' + attrEsc(names[j].value) + '"]';
                var s4 = uniqueOrGroup(by2, e, allowScope);
                if (s4) return s4;
            }
        }
        for (var i = 0; i < names.length && !names[i].rand; i++) {
            for (var j = i + 1; j < names.length && !names[j].rand; j++) {
                for (var k = j + 1; k < names.length && !names[k].rand; k++) {
                    var by3 = t + '[' + names[i].name + '="' + attrEsc(names[i].value) + '"][' + names[j].name + '="' + attrEsc(names[j].value) + '"][' + names[k].name + '="' + attrEsc(names[k].value) + '"]';
                    var s6 = uniqueOrGroup(by3, e, allowScope);
                    if (s6) return s6;
                }
            }
        }
        for (var i = 0; i < names.length; i++) {
            if (!names[i].rand) continue;
            var segs = attrValueSegments(names[i].value);
            for (var m = 0; m < segs.length; m++) {
                var byV = t + '[' + names[i].name + segs[m].op + '"' + attrEsc(segs[m].seg) + '"]';
                var s5 = uniqueOrGroup(byV, e, allowScope);
                if (s5) return s5;
            }
        }
        // 组合层：单属性仍不唯一时多个属性共同定位——① 稳定属性精确 AND 随机属性变体
        // （稳定取前 2、随机取前 2，随机变体按 ^= → *= → $=）；② 随机变体 × 随机变体 AND。
        // 全部经可见唯一校验（含组内索引）；命中即返回。
        var randNames = [], stabNames = [];
        for (var i = 0; i < names.length; i++) if (names[i].rand) randNames.push(names[i]);
        for (var i = 0; i < names.length && !names[i].rand && i < 2; i++) stabNames.push(names[i]);
        for (var a = 0; a < stabNames.length; a++) {
            for (var b = 0; b < randNames.length && b < 2; b++) {
                var rbSegs = attrValueSegments(randNames[b].value);
                for (var m = 0; m < rbSegs.length; m++) {
                    var byMix = t + '[' + stabNames[a].name + '="' + attrEsc(stabNames[a].value) + '"][' + randNames[b].name + rbSegs[m].op + '"' + attrEsc(rbSegs[m].seg) + '"]';
                    var s7 = uniqueOrGroup(byMix, e, allowScope);
                    if (s7) return s7;
                }
            }
        }
        for (var b = 0; b < randNames.length && b < 2; b++) {
            for (var c = b + 1; c < randNames.length && c < 2; c++) {
                var rb2Segs = attrValueSegments(randNames[b].value);
                var rcSegs = attrValueSegments(randNames[c].value);
                for (var m = 0; m < rb2Segs.length; m++) {
                    for (var m2 = 0; m2 < rcSegs.length; m2++) {
                        var byRand = t + '[' + randNames[b].name + rb2Segs[m].op + '"' + attrEsc(rb2Segs[m].seg) + '"][' + randNames[c].name + rcSegs[m2].op + '"' + attrEsc(rcSegs[m2].seg) + '"]';
                        var s8 = uniqueOrGroup(byRand, e, allowScope);
                        if (s8) return s8;
                    }
                }
            }
        }
        return '';
    }
    /** 结构链上溯层数上限。深层页面（弹窗套卡片套表格）6 层常不足以覆盖到带 id 的容器。 */
    var MAX_PATH_DEPTH = 8;

    /**
     * 结构链里允许的「裸 {@code tag:nth-of-type(n)}」补位层数上限。
     *
     * <p>历史策略是「任何一层无锚点即整条作废」（v2.12 收紧），理由是纯 tag 链又长又脆。
     * 但复杂页面里第 3~5 层常是纯布局 {@code div}（无 id / 无 data-*），整条作废后只能退到
     * <b>绝对 XPath</b>（全 nth 路径，更脆）或干脆无候选。折中：允许 ≤2 层补位，且整条链
     * 仍须至少有 1 个锚点——「锚点 + 2 层 nth」远比「绝对路径」可控。
     */
    var MAX_FILLER_LAYERS = 2;

    /** 单层兜底段：{@code tag:nth-of-type(k)}（无父级时退化为裸 tag，如 {@code html}）。 */
    function fallbackSeg(n) {
        if (!n || !n.tagName) return '';
        var tag = n.tagName.toLowerCase();
        return groupIndexSel(tag, n) || tag;
    }

    /**
     * 子代锚点兜底：元素自身无稳定属性时，若其浅层后代（深度 ≤3、最多访问 120 节点）携带稳定语义属性
     * （data-i18n / data-testid / aria-label / name / type 等），用 {@code :has()} 把元素与后代的业务标签
     * 绑定成唯一选择器，例如 {@code button:has(span[data-i18n="forgot_username_button"])}。
     *
     * <p>这覆盖了「点击落在按钮本体（而非带 data-i18n 的子 span）」等情形——直接定位元素自身，
     * 不依赖爬到带稳定 class 的祖先（2 层 {@code :nth-of-type} 补位上限常挡在中间布局 div 上）。
     * 仅当结果在文档内全局唯一时才返回，否则空串（交给结构链 / Java 侧 XPath 兜底）。
     * {@code :has()} 不被支持的环境里 {@link uniqueSel} 的 try/catch 会让其安静返回空串，不报错。
     */
    /**
     * 收集目标的「子元素锚点」候选（不判定唯一性）：用目标后代（深度 ≤3、稳定属性元素）构造
     * {@code t:has(后代[稳定属性="值"])} 形式的选择器。供 {@link scopeUnderUniqueAncestor} 在「自身属性被全局同类 +
     * 隐藏副本淹没」时作为更精确的锚点；同一属性名只取首个稳定值（去重），层数与访问数有界以防爆炸。
     *
     * <p>加固层（AND 复合，非 {@code :is} 的 OR）：在纯 {@code :has} 候选之后，追加目标自身稳定信号与后代锚点的复合——
     * {@code t[自身稳定属性]:has(后代[...])}、双属性复合、{@code t[自身稳定class token]:has(后代[...])}、双 token 复合，
     * 以及「自身属性 × 自身 class token」交叉复合。纯 {@code :has} 已够窄时会先返回（更短）；仅当纯 {@code :has}
     * 因「自身属性被全局同类 + 隐藏副本」而非唯一时，更窄的 AND 复合才作为兜底收敛为唯一。自身稳定属性与 class token
     * 各取前 2 个非随机项，组合数有界。
     */
    function descendantAnchorCandidates(e) {
        var t = e.tagName ? e.tagName.toLowerCase() : '';
        var out = [];
        if (!t || t === 'html' || t === 'body') return out;
        // 目标自身稳定属性（非随机值）+ 稳定 class token，用于与 :has 后代锚点做 AND 复合加固。
        // 自身层（Tier 1）已排除「全局唯一即返回」的情况，故走到此处时自身信号多半非全局唯一；
        // 但与后代锚点复合（t[own]:has(后代) / t[class*=tok]:has(后代)）会更窄，常在「自身信号被全局同类 + 隐藏副本淹没」时收敛为唯一。
        var selfAttrs = [];
        var sa = stableAttrs(e);
        for (var z = 0; z < sa.length && selfAttrs.length < 2; z++) {
            if (!sa[z].rand) selfAttrs.push(sa[z]);
        }
        var selfCls = [];
        var cls = (typeof e.className === 'string') ? e.className : '';
        var ctoks = stableClassTokens(cls);
        for (var c0 = 0; c0 < ctoks.length && selfCls.length < 2; c0++) {
            selfCls.push(ctoks[c0]);
        }
        var plainDescs = [];
        var queue = [], visited = 0, seen = new Set();
        for (var i = 0; i < e.children.length; i++) queue.push({ n: e.children[i], d: 1 });
        while (queue.length && visited < 120) {
            var cur = queue.shift();
            var n = cur.n;
            if (!n || n.nodeType !== 1) continue;
            visited++;
            var attrs = stableAttrs(n);
            for (var j = 0; j < attrs.length; j++) {
                if (attrs[j].rand) continue;
                if (seen.has(attrs[j].name)) continue;
                seen.add(attrs[j].name);
                var ds = t + ':has(' + n.tagName.toLowerCase() + '[' + attrs[j].name + '="' + attrEsc(attrs[j].value) + '"])';
                plainDescs.push(ds);
                out.push(ds);
            }
            if (cur.d < 3) {
                for (var k = 0; k < n.children.length; k++) queue.push({ n: n.children[k], d: cur.d + 1 });
            }
        }
        // 加固层（AND 复合，非 :is 的 OR）：把目标自身稳定属性 / 稳定 class token 与每个后代锚点复合。
        // 纯 :has 在前（保持原行为，优先返回更短选择器）；复合仅在其非唯一时作为更窄的兜底。
        for (var p = 0; p < plainDescs.length; p++) {
            var idx = plainDescs[p].indexOf(':has(');
            if (idx < 0) continue;
            var hasPart = plainDescs[p].slice(idx); // ':has(...)'
            // 自身属性复合：t[ownA]:has(...) 与 t[ownA][ownB]:has(...)
            for (var a = 0; a < selfAttrs.length; a++) {
                var ownA = '[' + selfAttrs[a].name + '="' + attrEsc(selfAttrs[a].value) + '"]';
                out.push(t + ownA + hasPart);
                for (var b = a + 1; b < selfAttrs.length; b++) {
                    var ownB = '[' + selfAttrs[b].name + '="' + attrEsc(selfAttrs[b].value) + '"]';
                    out.push(t + ownA + ownB + hasPart);
                }
            }
            // 自身 class token 复合：t[class*=tok]:has(...) 与 t[class*=tok1][class*=tok2]:has(...)
            for (var x = 0; x < selfCls.length; x++) {
                var ownC = '[class*="' + attrEsc(selfCls[x]) + '"]';
                out.push(t + ownC + hasPart);
                for (var y = x + 1; y < selfCls.length; y++) {
                    var ownD = '[class*="' + attrEsc(selfCls[y]) + '"]';
                    out.push(t + ownC + ownD + hasPart);
                }
            }
            // 自身属性 × 自身 class token 交叉复合（进一步收窄）
            for (var a2 = 0; a2 < selfAttrs.length; a2++) {
                var oa = '[' + selfAttrs[a2].name + '="' + attrEsc(selfAttrs[a2].value) + '"]';
                for (var x2 = 0; x2 < selfCls.length; x2++) {
                    var oc = '[class*="' + attrEsc(selfCls[x2]) + '"]';
                    out.push(t + oa + oc + hasPart);
                }
            }
        }
        return out;
    }

    function descendantAnchorSel(e) {
        // 复用后代锚点候选生成：自身可唯一则直接返回；只因隐藏副本非唯一时，挂到最近的、组合后全量唯一的祖先作用域
        // （如 .modal-x button:has(span[data-i18n="X"])），解除"没通过子元素定位到"的短路。
        var cands = descendantAnchorCandidates(e);
        for (var i = 0; i < cands.length; i++) {
            if (uniqueSel(cands[i])) return cands[i];
            var vc = visibleCount(cands[i]);
            if (vc > 0 && vc <= 10) {
                var sc = scopeUnderUniqueAncestor(cands[i], e);
                if (sc) return sc;
            }
        }
        return '';
    }

    /**
     * 稳定 CSS 选择器：稳定 id 优先；动态 id 先试稳定前缀/包含选择器（唯一才用）；
     * 否则结构链逐层上溯——每层优先接受稳定 id / 动态 id 前缀 / 稳定属性段（含组内索引），
     * 无锚点层用 {@code :nth-of-type} 补位（上限 {@link MAX_FILLER_LAYERS} 层）；
     * 补位超限才返回空串，交给 Java 侧 XPath 兜底（属性优先：稳定属性精确 → 双/三精确 AND →
     * 含随机值属性 ^=/*=$= 变体）。
     */
    function cssSel(e) {
        if (!e || e.nodeType !== 1) return '';
        if (e.id && !isDynamicId(e.id)) {
            var idSel = '#' + (window.CSS ? CSS.escape(e.id) : e.id);
            if (validCssSel(idSel)) return idSel;
        }
        if (e.id) {
            var fb = dynamicIdFallbackSel(e.id);
            if (fb && validCssSel(fb)) return fb;
        }
        // 自身层（Tier 1）：只接受不挂祖先即全局唯一的自身选择器（属性 / 组合，均经严格唯一校验）。
        var selfNoScope = attrFallbackSel(e, false);
        if (selfNoScope && validCssSel(selfNoScope)) return selfNoScope;
        // 子代锚点层（Tier 2）：元素自身无稳定属性时，借后代的 data-i18n/data-testid/aria-label 等经 :has() 唯一定位。
        var da = descendantAnchorSel(e);
        if (da && validCssSel(da)) return da;
        // 父/祖先作用域层（Tier 3）：把自身选择器挂到最近的、组合后全量唯一的祖先上（隐藏副本短路补丁）。
        var ab = attrFallbackSel(e, true);
        if (ab && validCssSel(ab)) return ab;
        var parts = [];
        var n = e;
        var anchored = false;
        var filler = 0;
        while (n && n.nodeType === 1 && parts.length < MAX_PATH_DEPTH) {
            if (n.id && !isDynamicId(n.id)) { parts.unshift('#' + (window.CSS ? CSS.escape(n.id) : n.id)); anchored = true; break; }
            if (n.id) {
                var pf = dynamicIdFallbackSel(n.id);
                if (pf) { parts.unshift(pf); anchored = true; break; }
            }
            var seg = attrFallbackSel(n);
            // 2026-11-13：祖先的稳定 class/属性段（如 modal 的 forgot-username-modal）本身已是全量唯一锚点，
            // 直接作为作用域锚点并停止上溯——即便目标自身属性因"隐藏副本"全局不唯一（全量计数仍计入隐藏），
            // 也能靠父类/祖先类的差异把选择器收窄到正确作用域，而非退化成坐标兜底。
            if (seg) { parts.unshift(seg); anchored = true; break; }
            // 该层无稳定锚点也无可用属性段：用 :nth-of-type 补位，超过上限才整条作废。
            if (filler >= MAX_FILLER_LAYERS) return '';
            var fs = fallbackSeg(n);
            if (!fs) return '';
            // 加固：若该层有稳定 class token，给位置段加 class 收窄
            // （tag[class*=tok]:nth-of-type(k) 比裸 tag:nth-of-type(k) 更抗 DOM 重排；元素确有该 class，匹配关系不变，零回归）。
            var fcls = (typeof n.className === 'string') ? n.className : '';
            var ftoks = stableClassTokens(fcls);
            if (ftoks.length) {
                var ftag = n.tagName.toLowerCase();
                fs = fs.replace(ftag, ftag + '[class*="' + attrEsc(ftoks[0]) + '"]');
            }
            parts.unshift(fs);
            filler++;
            n = n.parentElement;
        }
        if (!anchored) return '';
        var pathSel = parts.join(' > ');
        // 终极护栏（双重）：① 语法非法（回归式畸形拼接）⇒ 作废；② <b>当前零命中</b> ⇒ 同样作废。
        // ② 是本轮新增：此前的结构链分支只校验语法，能把一条「层级错位 / 元素已不在该位置」的死链
        // 写进 cssSelector（典型症状：`… > div:nth-of-type(3) button:has(…)` 而父级直接子元素其实是
        // button —— 生成即 0 命中，回放必然等不到元素）。语法合法但零命中的链对下游毫无价值：
        // 交给 Java 侧 XPath / role 兜底（它们各自带唯一性校验），比留一条死 CSS 更好。
        if (!validCssSel(pathSel) || totalCount(pathSel) <= 0) return '';
        return pathSel;
    }

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
            var achild = descendantAnchorsOf(p, 3, 4);
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

    /** 同级兄弟元素中的序号（0 起），供"一组元素（List）"识别。 */
    function siblingIndex(e) {
        var i = 0, s = e.previousElementSibling;
        while (s) { i++; s = s.previousElementSibling; }
        return i;
    }

    /**
     * 拾取目标归一化：点 <label> / 标签内文本 / 链接内 <span> / 装饰性图标（alt="" 或 aria-hidden）时，
     * 上溯到真正可操作的控件或「带可访问名的语义祖先」。
     *
     * <p><b>2026-11-11 定稿规则：拾取对象必须 = 用户实际点击的 DOM 对象。</b>
     * {@code <label>} 一律保留自身，<b>不再</b>转发到 {@code label.control} / 内部控件——关联控件是另一个
     * DOM 对象，转发会让产物定位到用户没点的元素（点"用户名"标签却生成 input 定位器）。label 自身带
     * 可访问名（其文本），命中 NLS 时可走文本语义策略定位。
     *
     * <p>装饰性节点（如 {@code <img alt="" aria-hidden="true">} 信息图标、空 {@code <span>}）：自身无可访问名，
     * 拾取只会产出 {@code role=img} 这类既无名又易碎的定位器。故当<b>被点元素自身无可访问名</b>时，继续上溯到
     * 最近一个带可访问名（含 textContent 兜底，不再截断长度）的祖先，使产物落到其所在的语义容器（如提示区
     * {@code <div id="newPasswordHint">}），从而带回完整提示文本——既能反查出 NLS 文案 key，又能回退到稳定的
     * {@code #id} 定位器。<b>注意</b>：{@code eHasName} 守卫保证「自身已有名的元素」（文本 div、按钮、带 data-i18n
     * 的 span 等）行为完全不变，避免无谓上溯。
     */
    /**
     * 被点元素自身是否带「身份锚点」属性（足以唯一定位的强语义属性）：稳定 id、data-*（data-i18n /
     * data-testid / data-qa …）、aria-label、表单 name、控件 type。刻意<b>排除</b>状态类 ARIA
     * （aria-hidden / aria-expanded …，随交互状态变化、装饰性元素常带）与 class——这些不算身份。
     * 仅当返回 true 时 pickTarget 才保留被点元素，避免把装饰性 img（带 aria-hidden）误判为可唯一定位。
     */
    function hasIdentityAttr(e) {
        if (!e || e.nodeType !== 1) return false;
        if (e.id && !isDynamicId(e.id)) return true;
        var attrs = stableAttrs(e);
        for (var i = 0; i < attrs.length; i++) {
            if (attrs[i].rand) continue;
            var n = attrs[i].name.toLowerCase();
            // 仅这些才算「身份锚点」：业务 data-*（data-i18n / data-testid / …）、aria-label、
            // 表单 name、控件 type。排除 width/height/title 等形态属性与状态类 ARIA——
            // 它们不足以唯一定位，且装饰性元素（如 aria-hidden 的信息图标）常带，误判会破坏「装饰性节点上溯」。
            if (/^data-/.test(n) || n === 'aria-label' || n === 'name' || n === 'type') return true;
        }
        return false;
    }

    function pickTarget(e) {
        // 被点元素自身若已带身份锚点（data-i18n / data-testid / aria-label / name / type / 稳定 id）足以唯一定位，
        // 直接保留——不无意义上溯到祖先控件，避免丢失业务锚点。典型场景：modal「Close」按钮内带
        // data-i18n 的 <span> 被点，若上溯到 <button> 会丢掉 data-i18n、且按钮自身 class 不唯一，
        // 导致 cssSel 退化为空串、只能依赖脆弱的绝对 XPath 兜底（甚至可能抛错）；保留 span 即可产出
        // span[data-i18n="forgot_username_button"] 这类稳定、唯一、位置无关的定位器。
        if (e && e.nodeType === 1 && hasIdentityAttr(e)) return e;
        var eHasName = (accessibleName(e) || '').trim();
        var n = e, depth = 0;
        while (n && n.nodeType === 1 && depth < 5) {
            var t = n.tagName ? n.tagName.toLowerCase() : '';
            if (t === 'input' || t === 'select' || t === 'textarea' || t === 'button') return n;
            // 点 <label> 就保留 <label> 本身：绝不转发到 label.control（关联控件是另一个 DOM 对象，
            // 转发会让拾取对象 != 用户点击对象）。label 自身带可访问名，命中 NLS 可走文本语义策略。
            if (t === 'label') return n;
            if (t === 'a' && n.getAttribute && n.getAttribute('href')) return n;
            // 2026-11-13：容器/地标类 role（document/dialog/group/region/...）不是可交互目标，
            // 不应把「用户点的标题/正文」上溯到它们——Bootstrap .modal-dialog=role=document、.modal=role=dialog
            // 会把 modal 内点击提升为对话框容器，再被 isContainerRoot 当背景误杀，导致 modal 内拾取静默无结果。
            // 仅对交互/语义控件角色（button/tab/checkbox/...）保留上溯提升。
            var __role = n.getAttribute && n.getAttribute('role');
            if (__role) {
                var __rl = String(__role).toLowerCase();
                var __containerRole = /^(document|dialog|alertdialog|application|group|region|banner|main|navigation|contentinfo|complementary|form|section|generic|none|presentation|article|aside|header|footer|search|tooltip|status|log|list|listitem)$/.test(__rl);
                if (!__containerRole) return n;
            }
            // 2026-11-12：上溯只接受「带稳定 #id 的语义容器」，拒绝无 id 的纯布局容器（content-wrapper / section /
            // 大段文本 div / span）——它们只是把大段文本包起来，落到这类容器会让产物定位过广。找不到稳定 id 容器
            // 则保留点击对象本身（precise = 用户实际点击的 DOM 对象）。
            if (depth > 0 && !eHasName && (accessibleName(n) || '').trim() && n.id && !isDynamicId(n.id)) return n;
            n = n.parentElement;
            depth++;
        }
        return e;
    }

    /**
     * 元素所在的 open shadow 宿主链（outermost-first；主文档元素返回空数组）。
     *
     * <p>每层宿主的选择器都相对其<b>自身文档 / 外层 shadow root</b> 计算，与 {@link cssSel} 口径一致。
     * Java 侧据此拼出 {@code document.querySelector(h0).shadowRoot.querySelector(h1)…} 的链式求值——
     * 因为 {@code document.querySelector} <b>无法穿透 shadow 边界</b>，这正是 shadow 内元素拾取失败的根因。
     */
    function shadowHostsOf(e) {
        var hosts = [];
        try {
            var node = e;
            while (node) {
                var root = node.getRootNode ? node.getRootNode() : null;
                if (!root || !root.host) break;   // 已到 Document（或游离节点）
                hosts.unshift(cssSel(root.host));
                node = root.host;
            }
        } catch (ignore) {
            return [];
        }
        return hosts;
    }

    /**
     * 绝对 XPath（结构 CSS 无法唯一时的兜底定位器）。
     *
     * <p>shadow root 内返回空串：XPath 无法表达"穿透 shadow 边界"的路径，那种场景由 shadowHosts + CSS 负责。
     */
    function absXPath(e) {
        if (!e || e.nodeType !== 1) return '';
        var root = e.getRootNode ? e.getRootNode() : null;
        if (root && root.nodeType === 11) return '';
        var parts = [], n = e, guard = 0;
        while (n && n.nodeType === 1 && guard++ < 64) {
            var t = n.tagName.toLowerCase();
            if (t === 'html') { parts.unshift('/html'); break; }
            var same = 0, idx = 0, sib = n.parentElement ? n.parentElement.firstElementChild : null;
            while (sib) {
                if (sib.tagName === n.tagName) { same++; if (sib === n) idx = same; }
                sib = sib.nextElementSibling;
            }
            parts.unshift('/' + t + (same > 1 ? '[' + idx + ']' : ''));
            n = n.parentElement;
        }
        return parts.join('');
    }

    /**
     * 可定位的语义属性集合（{@code data-*} + {@code aria-label}），供 CSS 属性定位使用。
     *
     * <p><b>为什么必须采集整族</b>：{@code data-i18n} / {@code data-qa} / {@code data-test} /
     * {@code data-automation-id} 这类业务自写属性值稳定、语义清晰，通常一个就能唯一定位；ARIA 属性
     * （{@code aria-label} / {@code aria-labelledby} / {@code aria-describedby} / {@code aria-roledescription} /
     * {@code aria-placeholder} 等身份类，以及 {@code aria-modal} / {@code aria-hidden} / {@code aria-expanded} 等
     * 状态类）同样可作为定位依据。只回传 name/value（trim、限长）；值是否随机由 Java 侧
     * {@code SelectorStability} 判定后，再决定用精确还是部分匹配。
     *
     * <p>{@code data-testid} 已由 {@code testid} 字段单独消费（优先级最高），这里跳过以免重复；
     * {@code id} / {@code class} / {@code style} 等由其它字段单独处理，也不在此收集。
     */
    function semanticAttrsOf(e, limit) {
        var out = {};
        if (!e || !e.attributes) return out;
        var used = 0;
        for (var i = 0; i < e.attributes.length && used < limit; i++) {
            var a = e.attributes[i];
            if (!a || !a.name) continue;
            var name = a.name.toLowerCase();
            if (name.indexOf('data-') !== 0 && name.indexOf('aria-') !== 0) continue;
            if (name === 'data-testid') continue;
            var v = (a.value || '').replace(/\s+/g, ' ').trim();
            if (!v || v.length > 120) continue;
            out[name] = v;
            used++;
        }
        return out;
    }

    /**
     * 勾选态（评审 N-02）：原生 input 读 .checked；组件库常用的
     * {@code <div role="checkbox" aria-checked="true">} 没有 .checked 属性，必须回退到 aria-checked，
     * 否则「已选中项再点一次取消」会被两边都读成 false，产物恒出 check() + 断言恒 true。
     * aria-checked="mixed" 不算选中。捕获阶段读到的是「点击前」状态，Java 侧据此推导终态。
     */
    function checkedStateOf(el) {
        if (!el) return false;
        if (typeof el.checked === 'boolean') return el.checked;
        var aria = el.getAttribute && el.getAttribute('aria-checked');
        return aria === 'true';
    }

    /** 单个元素的事实快照（Java 侧打分所需原始材料）。 */
    function factsOf(el) {
        if (!el || el.nodeType !== 1) return null;
        var tag = el.tagName ? el.tagName.toLowerCase() : '';
        return {
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

    /** 单层选择器段：稳定 id 优先，动态 id 试稳定前缀（唯一才用），否则稳定属性段（含组内索引）；
     * 一律不产出裸 tag:nth-of-type(n) 段——纯 tag 段既无锚点也无属性，不作 CSS 候选（交 Java 侧 XPath/role 兜底）。 */
    function segOf(n) {
        if (n.id && !isDynamicId(n.id)) return '#' + (window.CSS ? CSS.escape(n.id) : n.id);
        if (n.id) {
            var fb = dynamicIdFallbackSel(n.id);
            if (fb) return fb;
        }
        return attrFallbackSel(n);
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
    /**
     * 后代锚点（供 Java 侧拼 {@code 祖先:has(锚点) 目标}）。
     *
     * <p>结构相同、文案相同的双胞胎靠祖先自身属性区分不了；但祖先内部通常有唯一子锚点
     * （面板标题的 {@code data-i18n}、表格行的 {@code data-testid}）。深度取 4、最多 4 个、
     * 最多访问 200 个节点——账户卡片/分页容器常嵌套 3~4 层，再深收益低而扫描成本线性上升。
     * 值是否真稳定仍由 Java 侧 {@code SelectorStability} 复核，这里只做形态过滤。
     */
    function descendantAnchorsOf(root, maxDepth, maxCount) {
        var out = [];
        if (!root || !root.children) return out;
        var queue = [], visited = 0;
        for (var i = 0; i < root.children.length; i++) queue.push({ n: root.children[i], d: 1 });
        while (queue.length && out.length < maxCount && visited < 200) {
            var cur = queue.shift();
            var n = cur.n;
            if (!n || n.nodeType !== 1) continue;
            visited++;
            var seg = '';
            if (n.id && !isDynamicId(n.id)) {
                seg = '#' + (window.CSS ? CSS.escape(n.id) : n.id);
            } else {
                var attrs = stableAttrs(n);
                for (var j = 0; j < attrs.length; j++) {
                    if (attrs[j].rand) continue;
                    seg = '[' + attrs[j].name + '="' + attrEsc(attrs[j].value) + '"]';
                    break;
                }
            }
            if (seg) out.push(seg);
            if (cur.d < maxDepth) {
                for (var k = 0; k < n.children.length; k++) queue.push({ n: n.children[k], d: cur.d + 1 });
            }
        }
        return out;
    }

    /** 祖先事实（比 factsOf 轻）：供 Java 侧 {@code :has()} 作用域与祖先属性前缀使用。 */
    function ancestorFacts(n, path) {
        return {
            cssSelector: path,
            id: n.id || '',
            tag: n.tagName ? n.tagName.toLowerCase() : '',
            testid: (n.getAttribute && n.getAttribute('data-testid')) || '',
            attrs: semanticAttrsOf(n, 6),
            // 深度 6 / 最多 6 个：4 层时「卡片 > 列表 > 行 > 单元格 > 标题」这类行级锚点采不到，
            // 而 :has() 作用域恰恰依赖这些深层锚点。节点访问上限 200 不变，成本可控。
            childAnchors: descendantAnchorsOf(n, 6, 6)
        };
    }

    /**
     * 祖先链：从近到远的「从该祖先到目标的完整路径」。
     *
     * <p><b>无锚点层改为补位而非整条作废</b>（2026-11-11）：历史上任一层 {@code segOf} 失败即
     * 返回空数组，复杂页面（中间 2~3 层是纯布局 div）因此<strong>一条祖先候选都产不出</strong>，
     * 唯一性降级只能落到绝对 XPath。现在该层用 {@code :nth-of-type} 补位，累计超过
     * {@link MAX_FILLER_LAYERS} 层才停止上溯（<strong>截断而非清空</strong>：已收集的近端路径仍有效）。
     */
    function ancestorChain(el, maxDepth) {
        var first = segOf(el) || fallbackSeg(el);
        if (!first) return [];
        var chain = [first], out = [], last = '';
        var filler = segOf(el) ? 0 : 1;
        var p = el.parentElement, d = 0;
        while (p && p.nodeType === 1) {
            var seg = segOf(p);
            if (!seg) {
                if (filler >= MAX_FILLER_LAYERS) break;   // 补位超限：截断，保留已有近端路径
                seg = fallbackSeg(p);
                if (!seg) break;
                filler++;
            }
            chain.unshift(seg);
            last = chain.join(' > ');
            if (d < maxDepth) out.push(ancestorFacts(p, last));
            p = p.parentElement;
            d++;
        }
        if (d > maxDepth && out.length && last !== out[out.length - 1].cssSelector) {
            out.push({ cssSelector: last, id: '' });
        }
        return out;
    }

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
                 * 把本 frame 的文档坐标换算到<b>顶层文档坐标</b>的偏移量。
                 *
                 * <p>CDP {@code DOM.getNodeForLocation} 只认主 frame 的文档坐标，而事件在 iframe 内触发时
                 * {@code pageX/pageY} 是相对该 iframe 文档的。逐层向上累加宿主 iframe 的位置即可换算；
                 * 跨源 iframe 读不到 {@code frameElement}（或无 frameElement 对象）时返回 null ⇒ 放弃拾取，
                 * 避免用一个错误坐标去命中无关元素。
                 */
                function topFrameOffset() {
                    var dx = 0, dy = 0, w = window;
                    try {
                        while (w !== w.parent) {
                            var fe = w.frameElement;
                            if (!fe) return null;
                            var r = fe.getBoundingClientRect();
                            var cs = w.parent.getComputedStyle(fe);
                            // 视口坐标转换：iframe 元素在父文档视口里的位置即可，不叠加父/自身滚动——
                            // clientX/clientY 已是 iframe 内视口坐标，叠加 rect.left 即得顶层视口坐标；
                            // 此前叠加 scrollX/scrollY 把文档坐标误当视口坐标，页面滚动后坐标兜底命中错位元素。
                            dx += r.left + (parseFloat(cs.borderLeftWidth) || 0);
                            dy += r.top + (parseFloat(cs.borderTopWidth) || 0);
                            w = w.parent;
                        }
                        return { x: dx, y: dy };
                    } catch (e) {
                        return null;   // 跨源 iframe：SecurityError / frameElement 为 null
                    }
                }
                /** 元素中心点的顶层视口坐标（CSS 像素、不含滚动）：fill 这类没有可靠 pointer 坐标的事件用它。 */
                function centerOf(el, off) {
                    try {
                        if (!el || !el.getBoundingClientRect) return null;
                        var r = el.getBoundingClientRect();
                        if (!r || (r.width === 0 && r.height === 0)) return null;
                        // rect 已是视口坐标，不叠加滚动（与 pointOf 的 clientX/clientY 口径一致）
                        return { x: r.left + r.width / 2 + off.x,
                                 y: r.top + r.height / 2 + off.y };
                    } catch (e) { return null; }
                }
                /** 点击点优先用真实 pointer 坐标，缺失（键盘触发/合成事件）时回退元素中心。 */
                function pointOf(ev, el, off) {
                    var x = ev.clientX, y = ev.clientY;   // 视口坐标（非 pageX/pageY 文档坐标）：DOM.getNodeForLocation 要视口坐标，页面滚动后文档坐标会命中错位元素
                    if (typeof x === 'number' && typeof y === 'number' && (x !== 0 || y !== 0)) {
                        return { x: x + off.x, y: y + off.y };
                    }
                    return centerOf(el, off);
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
                            var iframes = doc.querySelectorAll('iframe');
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
                        // 2026-11-13：<label> 被点击时，浏览器会向关联控件（input/textarea/select）
                        // 再派发一次「合成 click」（label 默认行为）。该合成 click 与真实 label click
                        // 几乎同时、目标即 label.control，若照常上报会生成「label + 控件」两条记录，
                        // 违背「拾取对象 = 用户点击对象」。这里抑制被 label 转发的控件 click，
                        // 仅保留用户真正点的 label 记录（不 preventDefault，真实聚焦/交互照常发生）。
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
                        var off = topFrameOffset();
                        var pt = off ? pointOf(ev, target, off) : null;
                        window.__codegenEvent({ type: 'click', tag: target && target.tagName,
                            x: pt ? pt.x : null, y: pt ? pt.y : null,
                            coordSkipped: !off,
                            framePath: framePathOf(),
                            info: infoOf(target) });
                        // 记录 label 点击（含其关联控件），供识别随后浏览器自动转发的控件 click 并抑制。
                        if (target && target.tagName === 'LABEL') {
                            var ctrl = target.control
                                || (target.getAttribute && target.getAttribute('for')
                                    ? document.getElementById(target.getAttribute('for')) : null);
                            __codegenLastLabel = ctrl ? {
                                ctrl: ctrl,
                                t: (typeof performance !== 'undefined') ? performance.now() : Date.now()
                            } : null;
                        } else {
                            __codegenLastLabel = null;
                        }
                    } catch (e) {}
                }, true);
                window.addEventListener('input', function (ev) {
                    try {
                        if (isCodegenUi(ev.target)) return;   // 框架自绘 UI 不录制
                        var off2 = topFrameOffset();
                        var pt2 = off2 ? centerOf(ev.target, off2) : null;
                        window.__codegenEvent({ type: 'fill', tag: ev.target && ev.target.tagName,
                            x: pt2 ? pt2.x : null, y: pt2 ? pt2.y : null,
                            coordSkipped: !off2,
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
                        var off3 = topFrameOffset();
                        var pt3 = off3 ? centerOf(el, off3) : null;
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
                            x: pt3 ? pt3.x : null, y: pt3 ? pt3.y : null,
                            coordSkipped: !off3, framePath: framePathOf(),
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
                        var off4 = topFrameOffset();
                        var pt4 = off4 ? centerOf(el2, off4) : null;
                        window.__codegenEvent({ type: 'press', value: combo, tag: el2 && el2.tagName,
                            x: pt4 ? pt4.x : null, y: pt4 ? pt4.y : null,
                            coordSkipped: !off4, framePath: framePathOf(), info: infoOf(el2) });
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
                bar.__txt.textContent = '已暂停 · 页面交互与录制保持，点「继续拾取」恢复收集元素';
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

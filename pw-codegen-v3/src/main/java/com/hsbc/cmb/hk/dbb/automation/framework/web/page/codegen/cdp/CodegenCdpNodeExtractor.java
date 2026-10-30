package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.AriaRoles;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenFacts;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp.ElementRef.Frames;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls.NlsReverseLookup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score.CodegenScorer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score.LocatorCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 从 inspect 事件里的 {@code backendNodeId} 反查元素信息，组装 {@link CodegenEntry}。
 *
 * <p>事实收集（role/name/cssSelector/父级链）经 CDP {@code Runtime.callFunctionOn} 注入一段<b>自包含</b>
 * 函数（{@link #SELF_CONTAINED_GET_INFO_FN}），<b>不依赖</b>被测页是否预先注入 {@code window.__CODEGEN__}。
 * 这是支持<b>跨源 iframe</b> 的关键：Chromium 对跨源 iframe 做进程隔离，{@code addInitScript} 不会在其中运行，
 * 故原先依赖 {@code window.__CODEGEN__.getInfo} 的写法在跨源 iframe 内恒返回 null；自包含函数直接在元素所属的
 * frame 执行上下文里求值，跨源 iframe 内的元素同样可被拾取。
 *
 * <p>（注：{@code codegen-inject.js} 里的 {@code __CODEGEN__} 仍保留，供 recorder 录制真实操作——
 * 但 recorder 同样受跨源 iframe 隔离限制，跨源 iframe 内的 click/fill 暂不被录制，仅拾取可用。）
 *
 * <p>打分选择 / 唯一性校验 / 父级嵌套降级统一交给 {@link CodegenScorer}（纯 Java、可单测、不依赖浏览器）。
 *
 * <p><b>role 引擎复核不在拾取主路径上</b>：{@code page.getByRole(role,name).count()} 是"生成的
 * {@code @RoleElement} 真能命中"的唯一可信校验，但在按钮/链接密集的大页面上单次要 10~90ms（4k 元素实测 93ms），
 * 压在点击响应里明显发涩。故本类的 {@code extract} 只给出 AX 语义的 role <b>候选</b>；
 * {@link #requiresDeferredRoleVerification(CodegenEntry)} 告知调用方该条目是否欠一次复核，
 * 调用方必须在<b>任何产物输出前</b>用 {@link #countRoleMatches(CodegenEntry)} 补做，
 * {@code 1} 命中才保留 role，否则回退 CSS（见 {@code RoleRevision#toCss}）。
 * 这样拾取响应不随 DOM 规模增长，产物与"拾取时同步复核"逐字一致。
 *
 * <p><b>name 不再恒为 null</b>：可访问名缺失时回退到元素文本（单行、<=120 字符护栏），使 LabelText / generic /
 * StaticText 等"文本型"角色也能拿到 name（v2 旧实现用 {@code innerText} 属性兜底，恒为 null）。
 */
public final class CodegenCdpNodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(CodegenCdpNodeExtractor.class);

    /**
     * 自包含的元素事实收集函数（经 CDP {@code Runtime.callFunctionOn} 注入，{@code this} 绑定到目标元素）。
     *
     * <p>必须<b>不依赖</b> {@code window.__CODEGEN__}：跨源 iframe 内 addInitScript 不会运行，只有自包含函数才能
     * 在元素所属的 frame 执行上下文里完成求值。逻辑与 {@code codegen-inject.js} 的 {@code factsOf/ancestorChain}
     * 保持同源（改一处须同步另一处）。
     */
    private static final String SELF_CONTAINED_GET_INFO_FN =
            """
            function () {
                var el = this;
                function roleOf(e) {
                    var r = e.getAttribute && e.getAttribute('role');
                    if (r) return r.toLowerCase();
                    var t = e.tagName ? e.tagName.toLowerCase() : '';
                    if (t === 'button') return 'button';
                    if (t === 'a' && e.getAttribute('href')) return 'link';
                    if (t === 'input') {
                        var ty = (e.getAttribute('type') || '').toLowerCase();
                        if (ty === 'checkbox') return 'checkbox';
                        if (ty === 'radio') return 'radio';
                        if (ty === 'submit' || ty === 'button' || ty === 'reset') return 'button';
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
                function accessibleName(e) {
                    var a = e.getAttribute && e.getAttribute('aria-label');
                    if (a && a.trim()) return a.trim();
                    if (e.id) {
                        var byId = document.getElementById(e.id);
                        if (byId && byId !== e) { var bid = byId.getAttribute('aria-label'); if (bid && bid.trim()) return bid.trim(); }
                        var l = document.querySelector('label[for="' + (window.CSS ? CSS.escape(e.id) : e.id) + '"]');
                        if (l && l.textContent.trim()) return l.textContent.trim();
                    }
                    if (e.tagName && e.tagName.toLowerCase() === 'img') {
                        var alt = e.getAttribute('alt');
                        if (alt && alt.trim()) return alt.trim();
                    }
                    var t = (e.textContent || '').replace(/\\s+/g, ' ').trim();
                    if (t) return t;
                    var title = e.getAttribute && e.getAttribute('title');
                    if (title && title.trim()) return title.trim();
                    return '';
                }
                function normalizedText(e) {
                    var t = (e.textContent || '').replace(/\\s+/g, ' ').trim();
                    return t.length > 240 ? t.substring(0, 240) : t;
                }
                function labelTextOf(e) {
                    if (e.id) {
                        var l = document.querySelector('label[for="' + (window.CSS ? CSS.escape(e.id) : e.id) + '"]');
                        if (l && l.textContent.trim()) return l.textContent.trim();
                    }
                    var p = e.parentElement;
                    if (p && p.tagName && p.tagName.toLowerCase() === 'label' && p.textContent.trim()) return p.textContent.trim();
                    return '';
                }
                function cssSel(e) {
                    if (!e || e.nodeType !== 1) return '';
                    if (e.id) return '#' + (window.CSS ? CSS.escape(e.id) : e.id);
                    var parts = []; var n = e;
                    while (n && n.nodeType === 1 && parts.length < 6) {
                        var t = n.tagName.toLowerCase();
                        var i = 1, sib = n.previousElementSibling;
                        while (sib) { if (sib.tagName && sib.tagName.toLowerCase() === t) i++; sib = sib.previousElementSibling; }
                        parts.unshift(t + ':nth-of-type(' + i + ')');
                        if (n.id) { parts.unshift('#' + (window.CSS ? CSS.escape(n.id) : n.id)); break; }
                        n = n.parentElement;
                    }
                    return parts.join(' > ');
                }
                function siblingIndex(e) { var i = 0, s = e.previousElementSibling; while (s) { i++; s = s.previousElementSibling; } return i; }
                // 绝对 XPath（结构 CSS 无法唯一时的兜底定位器）。shadow root 内返回空串：XPath 表达不了
                // "穿透 shadow 边界"的路径，那种情况由 shadowHosts + CSS 负责。
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
                // 拾取目标归一化：点 <label> / 标签内文本 / 链接内 <span> / 装饰性图标（alt="" 或 aria-hidden）时，
                // 上溯到真正可操作的控件或「带可访问名的语义祖先」。<label> 在 AriaRole 枚举里根本没有对应常量，<span>/<div>
                // 只是 generic —— 它们只能生成又长又脆的结构 CSS；归一到控件后就能用 role + label 文本（可访问名）
                // 定位。装饰性节点（如 <img alt="" aria-hidden="true"> 信息图标、空 <span>）自身无可访问名，拾取只会
                // 产出 role=img 这类既无名又易碎的定位器；故当被点元素自身无名（eHasName 为假）时，继续上溯到最近一个
                // 带可访问名（含 textContent 兜底，不再截断长度）的祖先——使产物落到其语义容器（如提示区 div#newPasswordHint），
                // 带回完整提示文本：既能反查出 NLS 文案 key，又能回退到稳定的 #id 定位器。eHasName 守卫保证「自身已有名的
                // 元素」（文本 div、按钮、带 data-i18n 的 span 等）行为完全不变，避免无谓上溯。
                function pickTarget(e) {
                    var eHasName = (accessibleName(e) || '').trim();
                    var n = e, depth = 0;
                    while (n && n.nodeType === 1 && depth < 5) {
                        var t = n.tagName ? n.tagName.toLowerCase() : '';
                        if (t === 'input' || t === 'select' || t === 'textarea' || t === 'button') return n;
                        if (t === 'label') {
                            var ctrl = n.control || (n.querySelector && n.querySelector('input,select,textarea,button'));
                            if (ctrl) return ctrl;
                        }
                        if (t === 'a' && n.getAttribute && n.getAttribute('href')) return n;
                        if (n.getAttribute && n.getAttribute('role')) return n;
                        if (depth > 0 && !eHasName && (accessibleName(n) || '').trim()) return n;
                        n = n.parentElement; depth++;
                    }
                    return e;
                }
                function shadowHostsOf(e) {
                    var hosts = [];
                    try {
                        var node = e;
                        while (node) {
                            var root = node.getRootNode ? node.getRootNode() : null;
                            if (!root || !root.host) break;
                            hosts.unshift(cssSel(root.host));
                            node = root.host;
                        }
                    } catch (ignore) { return []; }
                    return hosts;
                }
                // 可定位的语义属性（data-* + 整族 aria-*）：业务自写/无障碍属性，值稳定时通常一个就能唯一定位。
                // data-testid 已由 testid 字段单独消费，这里跳过；id/class/style 由其它字段处理，也不收集；
                // 值随机与否交给 Java 侧判定（状态类属性只进组合，详见 CodegenScorer）。
                function semanticAttrsOf(e, limit) {
                    var out = {}, used = 0;
                    if (!e || !e.attributes) return out;
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
                function factsOf(x) {
                    if (!x || x.nodeType !== 1) return null;
                    var tag = x.tagName ? x.tagName.toLowerCase() : '';
                    return {
                        role: roleOf(x), name: accessibleName(x), tag: tag, id: x.id || '',
                        testid: (x.getAttribute && x.getAttribute('data-testid')) || '',
                        type: ((x.getAttribute && x.getAttribute('type')) || '').toLowerCase(),
                        placeholder: (x.getAttribute && x.getAttribute('placeholder')) || '',
                        alt: (x.getAttribute && x.getAttribute('alt')) || '',
                        title: (x.getAttribute && x.getAttribute('title')) || '',
                        value: (typeof x.value === 'string') ? x.value : '',
                        text: normalizedText(x), label: labelTextOf(x), nth: siblingIndex(x), cssSelector: cssSel(x),
                        shadowHosts: shadowHostsOf(x),
                        className: (typeof x.className === 'string') ? x.className : '',
                        xpath: absXPath(x),
                        nameAttr: (x.getAttribute && x.getAttribute('name')) || '',
                        attrs: semanticAttrsOf(x, 10)
                    };
                }
                // 单层选择器段：id 优先，否则 tag:nth-of-type(k)（同级同 tag 中的序号）。
                function segOf(n) {
                    if (n.id) return '#' + (window.CSS ? CSS.escape(n.id) : n.id);
                    var t = n.tagName.toLowerCase(), i = 1, sib = n.previousElementSibling;
                    while (sib) {
                        if (sib.tagName && sib.tagName.toLowerCase() === t) i++;
                        sib = sib.previousElementSibling;
                    }
                    return t + ':nth-of-type(' + i + ')';
                }
                // 父级嵌套降级候选：每项是「从该祖先到目标的完整路径」（段链 > 连接、层级严格连续）。
                // 必须整条替换 base，绝不能当 base 的前缀拼接——cssSel 是"上溯最多 6 层"的窗口，
                // 两个窗口相拼会重叠，产出 div:nth-of-type(7) > body:nth-of-type(1) 这类不可能的层级，
                // 生成的 CSS 永远匹配不到元素。最后一条为绝对路径兜底（逐级 nth-of-type 精确 ⇒ 必然唯一）。
                function ancestorChain(x, maxDepth) {
                    var chain = [segOf(x)], out = [], last = '';
                    var p = x.parentElement, d = 0;
                    while (p && p.nodeType === 1) {
                        chain.unshift(segOf(p));
                        last = chain.join(' > ');
                        if (d < maxDepth) out.push({ cssSelector: last, id: p.id || '' });
                        p = p.parentElement;
                        d++;
                    }
                    if (d > maxDepth && out.length && last !== out[out.length - 1].cssSelector) {
                        out.push({ cssSelector: last, id: '' });
                    }
                    return out;
                }
                if (!el || el.nodeType !== 1) return null;
                var target = pickTarget(el);
                if (!target || target.nodeType !== 1) target = el;
                return { self: factsOf(target), ancestors: ancestorChain(target, 8) };
            }
            """;

    /**
     * 跨 frame 穿透：给定 `<iframe>` 宿主元素（在父 frame 文档内），返回其"优先、可读<b>且唯一</b>"的 CSS 选择器。
     *
     * <p>优先级对齐 Playwright {@code generateFrameSelectorInParent}：{@code #id} →
     * {@code iframe[name="…"]} → {@code iframe[src="…"]} → 结构性后代链。但<b>每个候选都先做唯一性校验</b>
     * （{@code document.querySelectorAll(cand).length === 1}）：否则 {@code page.frameLocator(...)} 在严格模式下
     * 会因命中多个 iframe 而抛错（{@code iframe[src="…"]} 在广告位/重复组件上极易不唯一）。
     * 结构性回退边拼边校验，命中唯一即返回（最多 12 层，覆盖常规页面深度）。
     *
     * <p>自包含、不依赖 {@code window.__CODEGEN__}，可直接经 {@code Runtime.callFunctionOn} 在父 frame 执行上下文求值。
     */
    private static final String IFRAME_OWNER_FN =
            """
            function () {
                var el = this;
                function esc(v) { return window.CSS ? CSS.escape(v) : String(v); }
                function attrVal(v) { return String(v).replace(/"/g, '\\\\"'); }
                function unique(sel) {
                    try { return sel && document.querySelectorAll(sel).length === 1; } catch (e) { return false; }
                }
                function cssStruct(e) {
                    if (!e || e.nodeType !== 1) return '';
                    var parts = []; var n = e; var depth = 0;
                    while (n && n.nodeType === 1 && depth < 12) {
                        depth++;
                        var t = n.tagName ? n.tagName.toLowerCase() : '';
                        var i = 1, sib = n.previousElementSibling;
                        while (sib) { if (sib.tagName && sib.tagName.toLowerCase() === t) i++; sib = sib.previousElementSibling; }
                        parts.unshift(t + ':nth-of-type(' + i + ')');
                        if (n.id) {
                            var withId = '#' + esc(n.id) + (parts.length > 1 ? ' > ' + parts.slice(1).join(' > ') : '');
                            if (unique(withId)) return withId;
                        }
                        var chain = parts.join(' > ');
                        if (unique(chain)) return chain;
                        n = n.parentElement;
                    }
                    return parts.join(' > ');
                }
                var t = (el.tagName ? el.tagName.toLowerCase() : '');
                if (t !== 'iframe' && t !== 'frame') return cssStruct(el);
                if (el.id) {
                    var byId = '#' + esc(el.id);
                    if (unique(byId)) return byId;
                }
                var nm = el.getAttribute && el.getAttribute('name');
                if (nm && nm.trim()) {
                    var byName = 'iframe[name="' + attrVal(nm) + '"]';
                    if (unique(byName)) return byName;
                }
                var src = el.getAttribute && el.getAttribute('src');
                if (src && src.trim()) {
                    var bySrc = 'iframe[src="' + attrVal(src) + '"]';
                    if (unique(bySrc)) return bySrc;
                }
                return cssStruct(el);
            }
            """;

    private final CdpTransport sender;
    private final NlsReverseLookup nls;
    private final LocatorCounter counter;

    /** 无 Page 时（如纯 CDP 调试）默认不做唯一性校验，计数恒为 1（视为唯一）。 */
    public CodegenCdpNodeExtractor(CodegenCdpSession session, NlsReverseLookup nls) {
        this(session, nls, sel -> 1);
    }

    /** 带唯一定位数计数的构造（生产路径经被测页 {@code page.locator(css).count()} 注入）。 */
    public CodegenCdpNodeExtractor(CodegenCdpSession session, NlsReverseLookup nls, LocatorCounter counter) {
        this(session::send, nls, counter);
    }

    /** 测试 seam：直接注入 CDP 发送通道（单测无需真实浏览器）。 */
    CodegenCdpNodeExtractor(CdpTransport sender, NlsReverseLookup nls, LocatorCounter counter) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.nls = nls;
        this.counter = counter;
    }

    /** 从 backendNodeId 反查 role/name/cssSelector，装配成 CodegenEntry；任一环节失败返回 null。 */
    public CodegenEntry extract(long backendNodeId) {
        return extract(backendNodeId, null, ElementRef.FrameScope.UNKNOWN, null, null);
    }

    /**
     * 同上，并由调用方给出元素<b>所属 frameId</b>（{@link ElementRef#frameId()}）。
     *
     * <p>这是跨域 iframe 可运行的关键：{@code DOM.describeNode} 的 {@code node.frameId} 只对 frame owner 元素
     * 填充，普通元素拿不到归属；由定位过程（逐 frame 求值 / {@code DOM.getNodeForLocation}）告知后，
     * 才能沿 {@code Page.getFrameTree} 的 {@code parentId} 链生成穿透路径。
     *
     * @param frameIdHint 元素所属 frameId；{@code null} 表示未知（回退 {@code node.frameId}，仅 frame owner 可靠）
     */
    public CodegenEntry extract(long backendNodeId, String frameIdHint) {
        return extract(backendNodeId, frameIdHint, ElementRef.FrameScope.UNKNOWN, null, null);
    }

    /**
     * 同上，并带上元素相对 frame 树的归属（{@link ElementRef.FrameScope}）。
     *
     * @param backendNodeId 目标元素
     * @param frameIdHint   元素所属 frameId；{@code null} 表示未知
     * @param scopeHint     归属结论（定位阶段给出）；{@link ElementRef.FrameScope#UNKNOWN} 表示未知
     */
    public CodegenEntry extract(long backendNodeId, String frameIdHint, ElementRef.FrameScope scopeHint) {
        return extract(backendNodeId, frameIdHint, scopeHint, null, null);
    }

    /**
     * 生产入口：直接消费定位阶段的 {@link ElementRef}。
     *
     * <p>比起 {@code extract(backendNodeId, frameId)} 多带两项结论：
     * <ul>
     *   <li><b>frame 归属 + frame 树快照</b>：省一次 {@code Page.getFrameTree}；子 frame 元素直接走 CSS（页级
     *       role 复核对它们恒 0 命中，纯浪费），且其穿透路径复用定位阶段顺带产出的 parent 映射、无需二次读树；</li>
     *   <li><b>{@code objectId}</b>：定位阶段求值命中的元素已经是一个 {@code RemoteObject}，直接
     *       {@code Runtime.callFunctionOn} 即可，省掉一次 {@code DOM.resolveNode}（失效时自动回退）。</li>
     * </ul>
     */
    public CodegenEntry extract(ElementRef ref) {
        if (ref == null) return null;
        return extract(ref.backendNodeId(), ref.frameId(), ref.scope(), ref.objectId(), ref.frames());
    }

    /**
     * 抽取主流程。
     *
     * @param backendNodeId 目标元素
     * @param frameIdHint   元素所属 frameId（定位阶段给出）；{@code null} 表示未知
     * @param scopeHint     元素相对 frame 树的归属（定位阶段给出）；{@link ElementRef.FrameScope#UNKNOWN}
     *                      表示未知，此时自行读一次 frame 树判定（与引入 scope 之前的行为一致）
     * @param objectIdHint  定位阶段顺带得到的元素 {@code RemoteObject} id（加速提示）；{@code null} 表示未知。
     *                      该 id 随执行上下文销毁而失效，故只当提示：不可用时回退 {@code DOM.resolveNode}，
     *                      语义与不传完全一致（多一次往返，绝不失败）
     * @param framesHint    定位阶段顺带产出的 frame 树快照（主 frame id + parent 映射）；非 {@code null}
     *                      时抽取阶段直接复用，避免为生成穿透路径二次读取 {@code Page.getFrameTree}
     * @return 抽取结果；任一环节失败返回 {@code null}（调用方按"未拾取"处理，绝不产出半成品）
     */
    public CodegenEntry extract(long backendNodeId, String frameIdHint, ElementRef.FrameScope scopeHint,
                                String objectIdHint, Frames framesHint) {
        long startedNanos = System.nanoTime();
        try {
            String objectId = objectIdHint;
            JsonObject info = null;
            long afterResolve = startedNanos;
            if (objectId != null && !objectId.isEmpty()) {
                // 直传：定位阶段已经把元素做成了 RemoteObject，省掉一次 DOM.resolveNode。
                info = infoOf(objectId);
                afterResolve = System.nanoTime();
                if (info == null) {
                    log.debug("[codegen][cdp] objectId {} unusable (context destroyed?) — falling back to "
                            + "DOM.resolveNode", objectId);
                    objectId = null;
                }
            }
            if (objectId == null || objectId.isEmpty()) {
                JsonObject object = CdpJson.objectField(
                        sender.send("DOM.resolveNode", Map.of("backendNodeId", backendNodeId)), "object");
                if (object == null || !object.has("objectId")) return null;
                objectId = object.get("objectId").getAsString();
                info = infoOf(objectId);
                afterResolve = System.nanoTime();
            }
            if (info == null) return null;

            CodegenFacts facts = CodegenFacts.fromJson(info);
            if (facts == null || facts.self == null) return null;
            long afterFacts = System.nanoTime();

            // AX 权威语义：与 Playwright getByRole 同源。页面侧 roleOf 只是标签推断（label/div/span 会落到
            // AriaRole 里不存在或无语义的角色，最终只能生成又长又脆的结构 CSS）；AX 给出的才是浏览器实际
            // 暴露给辅助技术的 role（radio/link/textbox/...）与可访问名。
            CodegenFacts scoredFacts = new CodegenFacts(
                    withAxSemantics(facts.self, axSemantics(objectId)), facts.ancestors);
            long afterAx = System.nanoTime();

            // frame 归属：主 frame 元素不必读 frame 树（定位阶段已告知），子 frame 元素才需要为穿透路径读一次。
            // 必须早于计数：唯一性计数要绑定元素自身作用域（frame + shadow 链），否则 iframe 内元素恒 0 命中。
            // 定位阶段顺带带出的 frame 树快照优先复用（避免二次读取同一棵 frame 树），缺失时再按需自读。
            Frames frames = (scopeHint == ElementRef.FrameScope.MAIN)
                    ? null : (framesHint != null ? framesHint : loadFramesSafely());
            String frameId = (frameIdHint != null) ? frameIdHint : frameIdOfNode(backendNodeId);
            ElementRef.FrameScope scope = resolveScope(scopeHint, frames, frameId);
            boolean inSubFrame = scope == ElementRef.FrameScope.SUB;

            // 多语言环境（配置了 nls 包）禁用文案属性定位：placeholder/alt/title 随语言变化，
            // 用它们生成的 @Element 换个语言就跑不通。此时只能用 testid/id/name/class/结构/XPath。
            // 计数绑定元素作用域：实现方可按 frame/shadow 计数并合并候选计数（页级计数只看主 frame）。
            CountingCounter counting = new CountingCounter(
                    counter.forFrame(frameId, facts.self.shadowHosts));
            CodegenScorer.ScoredLocator scored = CodegenScorer.score(scoredFacts, counting, !nls.enabled());
            long afterScore = System.nanoTime();
            CodegenFacts.Fact self = scoredFacts.self;

            String role = self.role;
            String name = self.name;
            // 容器根（document / RootWebArea / <html> / <body>）不是真实交互目标：坐标拾取命中它们，实际是点了
            // 页面背景或弹窗遮罩，生成出来的条目既无意义又会污染 Elements 面板，且其聚合出的整页文案还会触发
            // 巨名 / 伪 NLS 键（见 NlsReverseLookup.MAX_REVERSE_NAME 与 CodegenFieldPlanner.MAX_NAME_IDENTIFIER）。
            // 直接判为无效拾取：调用方据此跳过条目、动作（若此前未被拾取）一并忽略，背景点击不污染产物。
            if (isContainerRoot(self)) {
                log.info("[codegen][cdp] rejected container-root pick role='{}' tag='{}' — "
                                + "not a real interaction target (background/backdrop), entry skipped",
                        self.role, self.tag);
                return null;
            }
            // 优先用元素自身 data-i18n（字面即 key，最精确、消歧多 key 同值）；否则按可见文案反查。
            String dataI18n = (self.attrs != null) ? self.attrs.get("data-i18n") : null;
            String nlsKey = nls.lookup(name, dataI18n);
            String css = scored.uniqueCss == null || scored.uniqueCss.isEmpty() ? "html" : scored.uniqueCss;
            int index = self.nth;
            String sigKey = role + "|" + (name == null ? "" : name) + "#" + index;

            if (scored.roleLocatable && inSubFrame) {
                log.debug("[codegen][cdp] role verification skipped (element lives in sub frame {}): "
                        + "page-level getByRole only searches the main frame", frameId);
            }

            // role 只作为「候选」给出，拾取阶段不做引擎复核：候选来自浏览器 AX 树，而 getByRole 走 Playwright
            // 自己的 ARIA 实现（roleUtils），边缘元素上两者并不等价（典型：generic 只有显式 role="generic" 才成立）。
            // 一次页级计数在按钮密集的大页面上要 10~90ms，压在点击响应里明显发涩；故改由调用方在任何产物
            // 输出前用 countRoleMatches 复核（1 命中才保留 role，否则 RoleRevision.toCss 回退 CSS）。
            // 子 frame 元素连候选都不给：页级 getByRole 对它们恒 0 命中，role 策略无从校验，直接走穿透 CSS。
            boolean roleLocatable = scored.roleLocatable && !inSubFrame;
            if (scored.roleLocatable && !inSubFrame) {
                log.debug("[codegen][cdp] role verification deferred to generation for role={} name='{}'",
                        role, name);
            }
            long afterRole = System.nanoTime();

            String strategy = roleLocatable
                    ? ((nlsKey != null && !nlsKey.isEmpty()) ? LocatorStrategy.ROLE_WITH_NAME : LocatorStrategy.ROLE)
                    : LocatorStrategy.CSS;
            String selector = buildSelector(role, name, nlsKey);

            // 跨 frame 穿透：元素若位于（跨源）iframe 内，沿父 frame 上行取每层 <iframe> 宿主选择器
            List<String> framePath = inSubFrame ? resolveFramePath(frames, frameId) : List.of();
            // shadow 宿主链随事实一并带出：CDP 侧按选择器反查时 document.querySelector 不穿透 shadow 边界
            CodegenEntry entry = new CodegenEntry(sigKey, role, name, strategy, scored.score, selector, css,
                    nlsKey, index, framePath, self.shadowHosts);
            logProfile(startedNanos, afterResolve, afterFacts, afterAx, afterScore, afterRole, counting, entry);
            return entry;
        } catch (Exception e) {
            log.warn("[codegen][cdp] extract failed: {}", e.getMessage());
            return null;
        }
    }

    /** AX（Accessibility 域）给出的权威语义：浏览器实际暴露的 ARIA role + 可访问名。 */
    record AxSemantics(String role, String name) {
    }

    /**
     * 页面级容器根不是真实交互目标：坐标拾取命中它们实为点了页面背景 / 弹窗遮罩，不该生成条目。
     *
     * <p>覆盖两类等价情况：AX 树根角色（{@code document} / {@code RootWebArea} / {@code WebArea}）与
     * DOM 根标签（{@code <html>} / {@code <body>}）。二者都不会是用户真正想操作的元素。
     */
    private static boolean isContainerRoot(CodegenFacts.Fact self) {
        if (self == null) return true;
        String role = self.role == null ? "" : self.role.toLowerCase(Locale.ROOT);
        if (role.equals("document") || role.equals("rootwebarea") || role.equals("webarea")) return true;
        String tag = self.tag == null ? "" : self.tag.toLowerCase(Locale.ROOT);
        return tag.equals("html") || tag.equals("body");
    }

    /**
     * 读取元素的 AX 语义（{@code Accessibility.getPartialAXTree}）。
     *
     * <p>这是 role/name 的<b>权威来源</b>：页面侧 {@code roleOf} 只是标签推断，无法识别 {@code <label>}
     * 关联的 radio、自定义 {@code role} 之外的可交互语义；而 Playwright {@code getByRole} 走的就是同一棵 AX 树，
     * 用 AX 值生成的 {@code @RoleElement} 才能真正命中。
     *
     * <p>失败/被忽略（{@code ignored=true}，如纯装饰节点）一律返回 {@code null}，调用方回退页面侧推断——
     * 拾取不能因为 AX 不可用而中断。
     */
    private AxSemantics axSemantics(String objectId) {
        if (objectId == null || objectId.isEmpty()) return null;
        try {
            JsonObject payload = sender.send("Accessibility.getPartialAXTree",
                    Map.of("objectId", objectId, "fetchRelatives", false));
            JsonArray nodes = CdpJson.arrayField(payload, "nodes");
            if (nodes == null || nodes.isEmpty() || !nodes.get(0).isJsonObject()) return null;
            JsonObject node = nodes.get(0).getAsJsonObject();
            JsonElement ignored = CdpJson.field(node, "ignored");
            if (ignored != null && ignored.isJsonPrimitive() && ignored.getAsJsonPrimitive().isBoolean()
                    && ignored.getAsBoolean()) {
                return null;
            }
            String role = CdpJson.stringField(CdpJson.objectField(node, "role"), "value");
            String name = CdpJson.stringField(CdpJson.objectField(node, "name"), "value");
            if ((role == null || role.isBlank()) && (name == null || name.isBlank())) return null;
            return new AxSemantics(role, name);
        } catch (Exception e) {
            log.debug("[codegen][cdp] getPartialAXTree failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 用 AX 语义重写元素事实的 role/name，其余字段（tag/id/testid/placeholder/cssSelector/shadowHosts…）
     * 保留页面侧采集值。
     *
     * <p>取值规则：
     * <ul>
     *   <li>role：AX role 能映射到确切 {@code AriaRole} 时以 AX 为准（radio/link/textbox…）；
     *       否则保留页面侧推断（AX 对纯文本/装饰节点会报 StaticText/InlineTextBox，不能据此把可定位的推断弄丢）；</li>
     *   <li>name：AX 可访问名优先（含 {@code <label>} 关联文本、{@code aria-label}、{@code alt}），空则用推断值。</li>
     * </ul>
     *
     * @param ax {@code null} 表示 AX 不可用 ⇒ 原样返回
     */
    static CodegenFacts.Fact withAxSemantics(CodegenFacts.Fact js, AxSemantics ax) {
        if (js == null || ax == null) return js;
        String role = AriaRoles.isLocatable(ax.role()) ? ax.role() : js.role;
        String name = (ax.name() != null && !ax.name().isBlank()) ? ax.name() : js.name;
        if (Objects.equals(role, js.role) && Objects.equals(name, js.name)) return js;
        return new CodegenFacts.Fact(role, name, js.tag, js.id, js.testid, js.type, js.placeholder,
                js.alt, js.title, js.value, js.text, js.label, js.nth, js.cssSelector, js.shadowHosts,
                js.className, js.xpath, js.nameAttr);
    }

    /**
     * 该条目是否需要在<b>生成前</b>补做 role 唯一性复核。
     *
     * <p>判据：策略是 role 系列、有可访问名（复核靠 {@code getByRole(role, name)}），且元素在主 frame
     * （子 frame 元素本就不会走 role 策略）。{@code extract} 从不做同步复核，故判据为真即表示<b>欠一次复核</b>。
     *
     * <p>调用方拿到 {@code true} 后必须保证：<b>任何产物输出前</b>都先用 {@link #countRoleMatches(CodegenEntry)}
     * 复核一次，非 1 命中则用 {@code RoleRevision#toCss} 回退 CSS。这是"生成的 {@code @RoleElement} 真能命中"
     * 的唯一保证。
     */
    public boolean requiresDeferredRoleVerification(CodegenEntry entry) {
        if (entry == null) return false;
        boolean roleStrategy = LocatorStrategy.ROLE.equals(entry.strategy)
                || LocatorStrategy.ROLE_WITH_NAME.equals(entry.strategy);
        return roleStrategy && entry.name != null && !entry.name.isEmpty() && entry.framePath.isEmpty();
    }

    /**
     * 用真实 Playwright 引擎按 {@code getByRole(role, {name, exact:true})} 复核条目的 role 定位是否唯一。
     *
     * <p>与框架 {@code @RoleElement} 运行期同源（同一引擎实现）：
     * <ul>
     *   <li>{@code 1} ⇒ 唯一命中，保留 role 策略；</li>
     *   <li>{@code 0} ⇒ 该 role/name 在引擎侧不成立（AX 与 {@code roleUtils} 口径分歧 / 元素对 ARIA 隐藏 /
     *       名字口径不同）；</li>
     *   <li>{@code >1} ⇒ 运行期会触发 strict mode violation；</li>
     *   <li>{@code -1} ⇒ <b>不可判定</b>（CDP/页面异常、入参缺失）。调用方必须按"放行"处理——
     *       环境抖动不该把可靠产物退化成结构 CSS。</li>
     * </ul>
     *
     * @param entry 拾取条目；{@code null} 视为不可判定
     * @return 命中数；{@code -1} 表示不可判定
     */
    public int countRoleMatches(CodegenEntry entry) {
        return entry == null ? -1 : countRoleMatches(entry.role, entry.name);
    }

    /**
     * 同上（直接给 role/name），供只有语义字段、没有完整条目的调用方复用。
     *
     * @return 命中数；{@code -1} 表示不可判定
     */
    public int countRoleMatches(String role, String name) {
        if (role == null || role.isBlank() || name == null || name.isBlank()) return -1;
        try {
            int hits = counter.countByRole(role, name);
            if (hits == 0) {
                log.info("[codegen][cdp] role locator does not resolve (role={}, name='{}') — "
                        + "AX 与 Playwright roleUtils 口径不一致或元素对 ARIA 隐藏，回退 CSS", role, name);
            } else if (hits > 1) {
                log.info("[codegen][cdp] role locator is ambiguous (role={}, name='{}', hits={}) — 回退 CSS",
                        role, name, hits);
            } else if (hits < 0) {
                log.debug("[codegen][cdp] role count unavailable (role={}, name='{}', hits={}) — 保留角色定位",
                        role, name, hits);
                return -1;
            }
            return hits;
        } catch (Exception e) {
            log.debug("[codegen][cdp] role count failed (role={}): {}", role, e.getMessage());
            return -1;
        }
    }

    /**
     * 对元素对象求值自包含事实函数。
     *
     * @return 事实 JSON；{@code objectId} 已失效（上下文销毁）或求值失败时返回 {@code null}（由调用方回退）
     */
    private JsonObject infoOf(String objectId) {
        JsonObject remote = CdpJson.remoteObject(sender.send("Runtime.callFunctionOn", Map.of(
                "objectId", objectId,
                "functionDeclaration", SELF_CONTAINED_GET_INFO_FN,
                "returnByValue", true)));
        return CdpJson.objectField(remote, "value");
    }

    /** 跨 frame 穿透的最大层数（防御恶意/异常 DOM 形成的超深嵌套）。 */
    private static final int MAX_FRAME_DEPTH = 8;

    /**
     * 跨 frame 穿透路径（outermost-first）：元素位于（跨源）iframe 内时，沿父 frame 上行取每层
     * {@code <iframe>} 宿主元素的 CSS 选择器；元素在主 frame 时返回空列表。
     *
     * <p><b>归属来源</b>：优先用调用方给的 {@code frameIdHint}（由 {@link CdpFrameLocator} 逐 frame 求值、
     * 或 {@code DOM.getNodeForLocation} 给出，<b>跨域 iframe 同样可靠</b>）；为空时回退
     * {@code DOM.describeNode} 的 {@code node.frameId}（CDP 只对 frame owner 元素填充，普通元素可能为空）。
     *
     * <p><b>上行链路</b>：不再逐层读 {@code describeNode.frameId}，改用 {@code Page.getFrameTree} 的
     * {@code parentId}，因此每一层（含跨源 iframe）都能正确走到主 frame；每层仍用 {@code DOM.getFrameOwner}
     * + 自包含 {@link #IFRAME_OWNER_FN} 生成宿主选择器。任一步失败即停止并回退（不伪造部分路径）。
     */
    private List<String> resolveFramePath(Frames frames, String frameId) {
        List<String> path = new ArrayList<>();
        if (frames == null || frameId == null) return path;
        try {
            if (frameId.equals(frames.mainFrameId())) return path;
            path = hostPathTo(frames, frameId);
            if (!path.isEmpty()) {
                log.info("[codegen][cdp] frame path resolved ({} level(s), outermost-first): {}", path.size(), path);
            }
        } catch (Exception e) {
            log.debug("[codegen][cdp] resolveFramePath failed: {}", e.getMessage());
        }
        return path;
    }

    /**
     * 读一次 frame 树（失败返回 {@code null}）。
     *
     * <p>不阻断拾取：读不到只是无法生成穿透路径、无法判定 frame 归属，此时按历史语义当主 frame 处理
     * （role 复核照旧执行，行为与引入 scope 之前一致）。
     */
    private Frames loadFramesSafely() {
        try {
            Frames frames = loadFrames();
            if (frames.mainFrameId() == null) {
                log.debug("[codegen][cdp] Page.getFrameTree returned no main frame");
                return null;
            }
            return frames;
        } catch (Exception e) {
            log.debug("[codegen][cdp] Page.getFrameTree failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 归并 {@link ElementRef.FrameScope}：定位阶段的结论优先，未知时才用 frame 树判定。
     *
     * @return 永不为 {@code null}；判不出时返回 {@link ElementRef.FrameScope#UNKNOWN}（按历史语义当主 frame）
     */
    private static ElementRef.FrameScope resolveScope(ElementRef.FrameScope hint, Frames frames, String frameId) {
        if (hint == ElementRef.FrameScope.MAIN || hint == ElementRef.FrameScope.SUB) return hint;
        if (frames == null || frames.mainFrameId() == null || frameId == null) {
            return ElementRef.FrameScope.UNKNOWN;
        }
        return frameId.equals(frames.mainFrameId())
                ? ElementRef.FrameScope.MAIN : ElementRef.FrameScope.SUB;
    }

    /** 从元素所在 frame 沿 {@code parentId} 上行，收集每层 iframe 宿主选择器，返回 outermost-first。 */
    private List<String> hostPathTo(Frames frames, String frameId) {
        List<String> path = new ArrayList<>();
        String current = frameId;
        int depth = 0;
        while (current != null && !current.equals(frames.mainFrameId()) && depth < MAX_FRAME_DEPTH) {
            depth++;
            Long ownerBn = CdpJson.longField(
                    sender.send("DOM.getFrameOwner", Map.of("frameId", current)), "backendNodeId");
            if (ownerBn == null) break;
            String ownerSel = iframeOwnerSelector(ownerBn);
            if (ownerSel == null || ownerSel.isEmpty()) break;
            path.add(ownerSel);
            current = frames.parentByFrame().get(current);
        }
        Collections.reverse(path); // outermost-first
        return path;
    }

    /** 读一次 {@code Page.getFrameTree}：主 frame id + 每个 frame 的父 frameId。 */
    private Frames loadFrames() {
        Map<String, String> parentByFrame = new HashMap<>();
        String[] mainFrameId = {null};
        JsonObject tree = CdpJson.objectField(sender.send("Page.getFrameTree", Map.of()), "frameTree");
        collectFrames(tree, null, parentByFrame, mainFrameId);
        return new Frames(mainFrameId[0], parentByFrame);
    }

    /** 深度优先展开 {@code FrameTree}，登记每个 frame 的父 frameId（首个子节点即主 frame）。 */
    private static void collectFrames(JsonObject frameTree, String parentFrameId,
                                      Map<String, String> parentByFrame, String[] mainFrameId) {
        if (frameTree == null) return;
        String id = CdpJson.stringField(CdpJson.objectField(frameTree, "frame"), "id");
        if (id != null && !id.isEmpty()) {
            if (mainFrameId[0] == null) mainFrameId[0] = id;
            if (parentFrameId != null) parentByFrame.put(id, parentFrameId);
        }
        JsonArray children = CdpJson.arrayField(frameTree, "childFrames");
        if (children == null) return;
        for (JsonElement child : children) {
            if (child != null && child.isJsonObject()) {
                collectFrames(child.getAsJsonObject(), id, parentByFrame, mainFrameId);
            }
        }
    }

    /**
     * 元素所属 frameId 的兜底来源（{@code DOM.describeNode} 的 {@code node.frameId}）。
     *
     * <p>CDP 仅对 frame owner 元素（{@code <iframe>/<frame>}）可靠填充该字段；普通元素的归属必须由定位过程
     * （{@link CdpFrameLocator} 逐 frame 求值 / {@code DOM.getNodeForLocation}）提供。
     */
    private String frameIdOfNode(long backendNodeId) {
        JsonObject desc = sender.send("DOM.describeNode", Map.of("backendNodeId", backendNodeId));
        JsonObject node = CdpJson.objectField(desc, "node");
        if (node == null) node = desc;
        return CdpJson.stringField(node, "frameId");
    }

    /** 用自包含函数求 iframe 宿主元素的 CSS 选择器。 */
    private String iframeOwnerSelector(long ownerBackendNodeId) {
        try {
            JsonObject object = CdpJson.objectField(
                    sender.send("DOM.resolveNode", Map.of("backendNodeId", ownerBackendNodeId)), "object");
            if (object == null || !object.has("objectId")) return null;
            JsonObject remote = CdpJson.remoteObject(sender.send("Runtime.callFunctionOn", Map.of(
                    "objectId", object.get("objectId").getAsString(),
                    "functionDeclaration", IFRAME_OWNER_FN,
                    "returnByValue", true)));
            return CdpJson.stringField(remote, "value");
        } catch (Exception e) {
            log.debug("[codegen][cdp] iframeOwnerSelector failed: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 性能可观测性（拾取耗时分解）
    // ------------------------------------------------------------------

    /** 单次拾取超过该阈值（毫秒）即以 WARN 输出分解，避免"偶发卡顿"无从定位；可用系统属性覆盖。 */
    private static final long SLOW_PICK_WARN_MS = Long.getLong("codegen.pick.slow.ms", 300L);

    /**
     * 记一次拾取的耗时分解（阶段纳秒差 + 计数器调用次数）。
     *
     * <p>常态 DEBUG（不污染生产日志），只有在超过 {@link #SLOW_PICK_WARN_MS} 时才 WARN。字段含义：
     * {@code resolve}=取对象、{@code facts}=页面侧事实、{@code ax}=Accessibility 语义、{@code score}=打分与
     * 唯一性计数、{@code role}=引擎复核、{@code tail}=frame 树与穿透路径；{@code cssCounts}/{@code roleCounts}
     * 用于区分「瓶颈在 CSS 计数」还是「瓶颈在 role 复核」——这两者的优化手段完全不同。
     */
    private void logProfile(long started, long afterResolve, long afterFacts, long afterAx,
                            long afterScore, long afterRole, CountingCounter counting, CodegenEntry entry) {
        long end = System.nanoTime();
        long totalMs = (end - started) / 1_000_000L;
        if (totalMs < SLOW_PICK_WARN_MS && !log.isDebugEnabled()) return;
        String detail = String.format(Locale.ROOT,
                "pick profile: total=%dms resolve=%dms facts=%dms ax=%dms score=%dms role=%dms tail=%dms"
                        + " | cssCounts=%d roleCounts=%d | strategy=%s framePath=%d",
                totalMs,
                (afterResolve - started) / 1_000_000L,
                (afterFacts - afterResolve) / 1_000_000L,
                (afterAx - afterFacts) / 1_000_000L,
                (afterScore - afterAx) / 1_000_000L,
                (afterRole - afterScore) / 1_000_000L,
                (end - afterRole) / 1_000_000L,
                counting.cssCalls, counting.roleCalls,
                entry.strategy, entry.framePath.size());
        if (totalMs >= SLOW_PICK_WARN_MS) {
            log.warn("[codegen][cdp] SLOW {}", detail);
        } else {
            log.debug("[codegen][cdp] {}", detail);
        }
    }

    /** 计数装饰器：只在一次 extract 内统计，把"慢"归因到 CSS 计数还是 role 复核。 */
    private static final class CountingCounter implements LocatorCounter {
        private final LocatorCounter delegate;
        private int cssCalls;
        private int roleCalls;

        CountingCounter(LocatorCounter delegate) {
            this.delegate = delegate;
        }

        @Override
        public int count(String cssSelector) {
            cssCalls++;
            return delegate.count(cssSelector);
        }

        @Override
        public int countByRole(String role, String name) {
            roleCalls++;
            return delegate.countByRole(role, name);
        }
    }

    /** 组装展示/代码生成用的定位器字符串（role=...[name=...] 或 key=）。 */
    static String buildSelector(String role, String name, String nlsKey) {
        StringBuilder sb = new StringBuilder("role=").append(role);
        if (nlsKey != null && !nlsKey.isEmpty()) {
            sb.append("[name=Nls.t(\"").append(nlsKey).append("\")]");
        } else if (name != null && !name.isEmpty()) {
            sb.append("[name=\"").append(name.replace("\"", "\\\"")).append("\"]");
        }
        return sb.toString();
    }
}

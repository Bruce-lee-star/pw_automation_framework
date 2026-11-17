package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.LocatorCoreScript;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.AriaRoles;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenFacts;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.TriState;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * 在元素所属的 frame 执行上下文里完成求值。
     *
     * <p>内容 = {@code codegen/locator-core.js}（公共定位逻辑，唯一来源）+ {@code codegen/cdp-extract-fn.js}
     * 模板，由 {@link LocatorCoreScript} 在类加载期拼装并做结构校验。禁止在本类或模板里另存一份公共定位逻辑——
     * 两份副本的漂移不会有编译错误，只会让注入路径与拾取路径对同一元素给出不同结论。
     */
    private static final String SELF_CONTAINED_GET_INFO_FN = LocatorCoreScript.cdp();

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
                // 动态/随机 id（Radix/Headless UI/react-select/MUI/antd 等）：不参与选择器，降级结构链
                function isDynamicId(id) {
                    if (!id || !id.length) return false;
                    if (/^_R_/.test(id) || /_R_[A-Za-z0-9]{4,}_/.test(id)) return true;
                    if (/:r\\d+:/.test(id)) return true;
                    if (/^headlessui-/.test(id)) return true;
                    if (/^react-select-/.test(id)) return true;
                    if (/^mui-\\d+$/.test(id)) return true;
                    if (/^[a-z]+(-[a-z]+)*-\\d{4,}$/.test(id)) return true;
                    if (/\\d{4,}-id$/.test(id)) return true;
                    if (/^[a-z]+(-[a-z]+)*\\d{4,}$/.test(id)) return true; // 通用: 段前缀 + ≥4 位尾随数字（无连字符）
                    if (/[0-9a-f]{8,}/i.test(id)) return true;            // 十六进制/哈希 token
                    if (/[A-Za-z0-9]{8,}/.test(id)) {                      // 高熵 base62 token：≥8 位无分隔符，且（含大小写+≥2 数字）或（≥3 数字），且数字非纯尾随
                        var d = (id.match(/[0-9]/g) || []).length;
                        if (/[0-9][A-Za-z]/.test(id) && ((/[A-Z]/.test(id) && /[a-z]/.test(id) && d >= 2) || d >= 3)) return true;
                    }
                    return false;
                }
                function unique(sel) {
                    try { return sel && document.querySelectorAll(sel).length === 1; } catch (e) { return false; }
                }
                function stableIdPrefix(id) {
                    var m = id.match(/^(.*?)(?:_R_[A-Za-z0-9]{4,}_|:r\\d+:|-\\d{4,}|\\d{4,}$|[0-9a-f]{8,})/i);
                    return m ? m[1] : '';
                }
                // 动态 id 的稳定后缀：截掉开头随机段后，剩余尾部在组件语义上稳定。
                function stableIdSuffix(id) {
                    var m = id.match(/^(?:_R_[A-Za-z0-9]{4,}_|:r\\d+:|\\d{4,}-|[0-9a-f]{8,})(.*)$/i);
                    return m ? m[1] : '';
                }
                function attrEsc(v) {
                    return String(v).replace(/["\\\\]/g, function (c) { return '\\\\' + c; });
                }
                // 动态 id 的稳定前缀/后缀/包含选择器兜底（唯一才用）：dropdown-list-_R_x_ → [id^="dropdown-list-"]；
                // a3f9-input-name → [id$="input-name"]。
                function dynamicIdFallbackSel(id) {
                    var prefix = stableIdPrefix(id);
                    if (prefix) {
                        var byPrefix = '[id^="' + attrEsc(prefix) + '"]';
                        if (unique(byPrefix)) return byPrefix;
                    }
                    var suffix = stableIdSuffix(id);
                    if (suffix) {
                        var bySuffix = '[id$="' + attrEsc(suffix) + '"]';
                        if (unique(bySuffix)) return bySuffix;
                    }
                    var core = (prefix || suffix).replace(/^[-_]+|[-_]+$/g, '');
                    if (core) {
                        var byCore = '[id*="' + attrEsc(core) + '"]';
                        if (unique(byCore)) return byCore;
                    }
                    return '';
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
                        if (n.id && !isDynamicId(n.id)) {
                            var withId = '#' + esc(n.id) + (parts.length > 1 ? ' > ' + parts.slice(1).join(' > ') : '');
                            if (unique(withId)) return withId;
                        }
                        if (n.id) {
                            var pf = dynamicIdFallbackSel(n.id);
                            if (pf && parts.length === 1) return pf;
                            if (pf) { var withPf = pf + (parts.length > 1 ? ' > ' + parts.slice(1).join(' > ') : ''); if (unique(withPf)) return withPf; }
                        }
                        var chain = parts.join(' > ');
                        if (unique(chain)) return chain;
                        n = n.parentElement;
                    }
                    return parts.join(' > ');
                }
                var t = (el.tagName ? el.tagName.toLowerCase() : '');
                if (t !== 'iframe' && t !== 'frame') return cssStruct(el);
                if (el.id && !isDynamicId(el.id)) {
                    var byId = '#' + esc(el.id);
                    if (unique(byId)) return byId;
                }
                if (el.id) {
                    var byPf = dynamicIdFallbackSel(el.id);
                    if (byPf) return byPf;
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
        return extract(backendNodeId, null, ElementRef.FrameScope.UNKNOWN, null, null, null);
    }

    /**
     * 同上，并由调用方给出元素<b>所属 frameId</b>（{@link ElementRef#frameId()}）。
     *
     * <p>这是跨域 iframe 可运行的关键：{@code DOM.describeNode} 的 {@code node.frameId} 只对 frame owner 元素
     * 填充，普通元素拿不到归属；由定位过程（选择器逐 frame 求值）告知后，
     * 才能沿 {@code Page.getFrameTree} 的 {@code parentId} 链生成穿透路径。
     *
     * @param frameIdHint 元素所属 frameId；{@code null} 表示未知（回退 {@code node.frameId}，仅 frame owner 可靠）
     */
    public CodegenEntry extract(long backendNodeId, String frameIdHint) {
        return extract(backendNodeId, frameIdHint, ElementRef.FrameScope.UNKNOWN, null, null, null);
    }

    /**
     * 同上，并带上元素相对 frame 树的归属（{@link ElementRef.FrameScope}）。
     *
     * @param backendNodeId 目标元素
     * @param frameIdHint   元素所属 frameId；{@code null} 表示未知
     * @param scopeHint     归属结论（定位阶段给出）；{@link ElementRef.FrameScope#UNKNOWN} 表示未知
     */
    public CodegenEntry extract(long backendNodeId, String frameIdHint, ElementRef.FrameScope scopeHint) {
        return extract(backendNodeId, frameIdHint, scopeHint, null, null, null);
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
        return extract(ref, null);
    }

    /**
     * 生产入口（拾取路径）：附带<b>点击捕获阶段</b>的元素快照。
     *
     * <p>抽取是异步的（拾取引擎线程），等它执行时页面自身的 click 处理器早已跑完。提交类按钮
     * 「点一下就变 loading」，抽到的会是 loading 文案，产物因此变成
     * {@code @RoleElement(name = "Loading, please wait.")} + 字段名 {@code loadingPleaseWaitBtn}，
     * 回放时那一步的按钮其实叫 {@code "Continue"} ⇒ 定位器打空。
     *
     * <p>捕获阶段（window capture，先于页面处理器）同步采集的 {@code name} / {@code text} 才是
     * 「这一步要操作的那个元素」的样子；是否采信由 {@link PickNamePolicy} 判定（有证据才动）。
     *
     * @param ref 定位阶段给出的元素引用
     * @param preClick 捕获阶段快照；{@code null} 表示无证据（行为与 {@link #extract(ElementRef)} 完全一致）
     */
    public CodegenEntry extract(ElementRef ref, PreClickSnapshot preClick) {
        if (ref == null) return null;
        return extract(ref.backendNodeId(), ref.frameId(), ref.scope(), ref.objectId(), ref.frames(), preClick);
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
     * @param preClick      点击捕获阶段的元素快照（见 {@link PreClickSnapshot}）；{@code null} 表示无证据
     * @return 抽取结果；任一环节失败返回 {@code null}（调用方按"未拾取"处理，绝不产出半成品）
     */
    public CodegenEntry extract(long backendNodeId, String frameIdHint, ElementRef.FrameScope scopeHint,
                                String objectIdHint, Frames framesHint, PreClickSnapshot preClick) {
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
                    log.debug("[codegen][cdp] objectId {} unusable (context destroyed?) - falling back to "
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
            CodegenFacts.Fact axSelf = withAxSemantics(facts.self, axSemantics(objectId));
            // 名字收窄（有证据才动，规则见 PickNamePolicy）：
            //   ① 状态播报区（aria-live）文本混进 AX 名 ⇒ 取稳定标签（"Loading, please wait. Continue" → "Continue"）；
            //   ② 点击改写了元素自身文案（按钮变 loading）⇒ 用捕获阶段的名字还原（否则产物与字段名都变成 loading）。
            // 必须早于打分/NLS 反查/字段命名：它们全部消费 name。
            CodegenFacts.Fact namedSelf = resolvePickedName(axSelf, facts.self, preClick);
            if (!Objects.equals(namedSelf.name, axSelf.name)) {
                log.info("[codegen][cdp] picked name normalized for role={}: '{}' -> '{}' "
                                + "(statusText='{}', preClickName='{}')",
                        namedSelf.role, axSelf.name, namedSelf.name, facts.self.statusText,
                        preClick == null ? null : preClick.name());
            }
            CodegenFacts scoredFacts = new CodegenFacts(namedSelf, facts.ancestors, facts.robustXpaths, facts.list);
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
            // 容器根（document / RootWebArea / <html> / <body>）不是真实交互目标：命中它们，实际是点了
            // 页面背景或弹窗遮罩，生成出来的条目既无意义又会污染 Elements 面板，且其聚合出的整页文案还会触发
            // 巨名 / 伪 NLS 键（见 NlsReverseLookup.MAX_REVERSE_NAME 与 CodegenFieldPlanner.MAX_NAME_IDENTIFIER）。
            // 直接判为无效拾取：调用方据此跳过条目、动作（若此前未被拾取）一并忽略，背景点击不污染产物。
            if (isContainerRoot(self)) {
                log.info("[codegen][cdp] rejected container-root pick role='{}' tag='{}' - "
                                + "not a real interaction target (background/backdrop), entry skipped",
                        self.role, self.tag);
                return null;
            }
            // 优先用元素自身 data-i18n（字面即 key，最精确、消歧多 key 同值）；否则按可见文案反查。
            String dataI18n = (self.attrs != null) ? self.attrs.get("data-i18n") : null;
            String nlsKey = nls.lookup(name, dataI18n);
            if (nlsKey == null || nlsKey.isEmpty()) {
                // 交互控件本身通常不带 data-i18n：HSBC 把 i18n 标在按钮文案的 <span> 上
                // （{@code <button><span data-i18n="continue">Continue</span></button>}）。
                // 拾取归一后按钮才是拾取对象，自身属性里没有 key；此时若只按可见文案反查，
                // 同一文案对应多个 key（reset.json 里 8 个 key 都是 "Continue"）时会"文件顺序先到先得"，
                // 拿到语义不对的 key。故借目标自身采集的后代锚点（childAnchors）取回那个显式 key。
                nlsKey = nls.lookupByDescendantKey(name, descendantI18nKey(self.childAnchors));
            }
            // 集合项自动成组：目标本身是 ARIA 集合里的一项（如密保问题下拉的 li[role=option]）时，
            // 旧的「多次拾取同一 role|name」成组判据永远不成立（每一项的名字都不同），产物只能退化成
            // 「点中第 5 个选项」的单元素。注入脚本已把「整组定位器 + 组内序号」一并带出，
            // 这里落成 autoList：Page 侧生成 PageElementList，Step 侧用 get(index) 指回用户实际点的那一项。
            CodegenFacts.ListHint listHint = facts.list != null && facts.list.usable() ? facts.list : null;
            // 自动成组时 css 是「命中整组」的定位器（来自注入脚本，相对元素自身文档 / shadow root）。
            String css = listHint != null
                    ? listHint.selector()
                    : (scored.uniqueCss == null || scored.uniqueCss.isEmpty() ? "html" : scored.uniqueCss);
            // 序号语义随成组来源而不同：自动成组 = 用户实际点的那一项在组内的序号；否则沿用同级兄弟位置。
            int index = listHint != null ? listHint.index() : self.nth;
            String sigKey = role + "|" + (name == null ? "" : name) + "#" + index;
            if (listHint != null) {
                log.info("[codegen][cdp] auto list detected: role={} index={} count={} selector='{}'",
                        role, index, listHint.count(), css);
            }

            if (scored.roleLocatable && inSubFrame) {
                log.debug("[codegen][cdp] role verification skipped (element lives in sub frame {}): "
                        + "page-level getByRole only searches the main frame", frameId);
            }

            // role 只作为「候选」给出，拾取阶段不做引擎复核：候选来自浏览器 AX 树，而 getByRole 走 Playwright
            // 自己的 ARIA 实现（roleUtils），边缘元素上两者并不等价（典型：generic 只有显式 role="generic" 才成立）。
            // 一次页级计数在按钮密集的大页面上要 10~90ms，压在点击响应里明显发涩；故改由调用方在任何产物
            // 输出前用 countRoleMatches 复核（1 命中才保留 role，否则 RoleRevision.toCss 回退 CSS）。
            // 子 frame 元素连候选都不给：页级 getByRole 对它们恒 0 命中，role 策略无从校验，直接走穿透 CSS。
            // 自动成组的条目同样不给 role 候选：getByRole(role, name) 命中的是「某一个选项」，
            // 而 PageElementList 需要「命中整组」的定位器，两者语义冲突，只能走整组 CSS。
            boolean roleLocatable = scored.roleLocatable && !inSubFrame && listHint == null;
            if (scored.roleLocatable && !inSubFrame && listHint == null) {
                log.debug("[codegen][cdp] role verification deferred to generation for role={} name='{}'",
                        role, name);
            }
            long afterRole = System.nanoTime();

            String strategy = roleLocatable
                    ? ((nlsKey != null && !nlsKey.isEmpty()) ? LocatorStrategy.ROLE_WITH_NAME : LocatorStrategy.ROLE)
                    : LocatorStrategy.CSS;
            // 自动成组时产物用的是整组 CSS，展示用的 selector 也必须跟着改，否则面板/日志会显示
            // 一条「role=option[name=第 5 项]」的误导信息。
            String selector = listHint != null ? css : buildSelector(role, name, nlsKey);
            // 自动成组的命中数取「整组成员数」（listHint 已在 usable() 里保证 >= 2）：
            // 它既让生成侧走 PageElementList，也让产物注释能说明整组有多大。
            int matchCount = listHint != null ? listHint.count() : scored.matchCount;

            // 可观测性：CSS 候选链最终 0 命中（或计数不可判定）时，运行期会一直等不到元素。
            // 不改变产物（拾取瞬间元素可能只是尚未渲染），但必须留下可检索的告警，
            // 而不是像以前那样把 0 命中当作「唯一」静默采用。
            if (!roleLocatable && matchCount <= 0) {
                log.warn("[codegen][cdp] locator '{}' matched {} element(s) at pick time{} - "
                                + "the generated locator may time out at replay",
                        css, matchCount, inSubFrame ? " (sub frame)" : "");
            }

            // 两个引擎的计数各归各的字段（评审 P0-1）：
            //   · matchCount = CSS 候选链唯一化后的命中数，无论最终策略是什么都如实保留（回退 CSS 时直接用得上）；
            //   · roleHits   = role 引擎还没跑 ⇒ UNVERIFIED；CSS 策略下不适用 ⇒ NOT_APPLICABLE。
            // 生成侧只经 CodegenEntry#effectiveMatchCount() 按「最终策略」取数，
            // 绝不会拿 CSS 计数去判 role 条目的单元素 / 一组语义。
            int roleHits = roleLocatable
                    ? CodegenEntry.ROLE_HITS_UNVERIFIED
                    : CodegenEntry.ROLE_HITS_NOT_APPLICABLE;

            // 跨 frame 穿透：元素若位于（跨源）iframe 内，沿父 frame 上行取每层 <iframe> 宿主选择器
            List<String> framePath = inSubFrame ? resolveFramePath(frames, frameId) : List.of();
            // shadow 宿主链随事实一并带出：CDP 侧按选择器反查时 document.querySelector 不穿透 shadow 边界
            CodegenEntry entry = new CodegenEntry(sigKey, role, name, strategy, scored.score, selector, css,
                    nlsKey, index, framePath, self.shadowHosts, matchCount, self.tag, self.checked,
                    roleHits, listHint != null);
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
     * 页面级容器根不是真实交互目标：命中它们实为点了页面背景 / 弹窗遮罩，不该生成条目。
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

    /** {@code childAnchors} 里 {@code [data-i18n="xxx"]} 段的匹配式，捕获 key。 */
    private static final Pattern DESCENDANT_I18N_ANCHOR =
            Pattern.compile("\\[data-i18n=\"([^\"]+)\"]");

    /**
     * 从目标自身的后代锚点里取第一个显式 NLS key（{@code data-i18n}）。
     *
     * <p><b>为什么需要</b>：交互控件自身通常不带 {@code data-i18n} —— HSBC 把 i18n 标在按钮文案的
     * {@code <span>} 上（{@code <button><span data-i18n="continue">Continue</span></button>}）。
     * 拾取归一后拾取对象成了 button，自身属性里没有 key，只能按可见文案反查；而同一文案往往对应多个 key
     * （reset.json 里 8 个 key 的值都是 "Continue"），反查只能"文件顺序先到先得"，拿到的 key 语义不对
     * （例如拿到 {@code reset_password_5} 而不是 {@code continue}）。借后代锚点就能取回开发者显式绑定的那个 key。
     *
     * <p>后代锚点按 BFS 采集（浅层优先），故"最近的那个 data-i18n"胜出；最终是否采纳由
     * {@code NlsReverseLookup.lookupByDescendantKey} 用「值必须与该元素可访问名互相包含」再校验一次，
     * 防止把装饰性子节点的 key（图标 title 之类）误当成本元素的 key。
     *
     * @return 第一个后代 {@code data-i18n} 的值；没有则 {@code null}
     */
    static String descendantI18nKey(List<String> childAnchors) {
        if (childAnchors == null || childAnchors.isEmpty()) return null;
        for (String anchor : childAnchors) {
            if (anchor == null) continue;
            Matcher m = DESCENDANT_I18N_ANCHOR.matcher(anchor);
            if (m.find()) return m.group(1);
        }
        return null;
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
        // 拷贝必须带 childAnchors / statusText：前者供 NLS 反查「后代 data-i18n 显式 key」
        // （HSBC 把 i18n 标在按钮文案的 <span> 上），后者供名字收窄校验。历史实现漏了它们，
        // 导致 AX 存在时（常规路径）后代 key 与播报文本双双丢失。
        return new CodegenFacts.Fact(role, name, js.tag, js.id, js.testid, js.type, js.placeholder,
                js.alt, js.title, js.value, js.text, js.label, js.nth, js.cssSelector, js.shadowHosts,
                js.className, js.xpath, js.nameAttr, js.attrs, js.src, js.checked,
                js.childAnchors, js.statusText);
    }

    /**
     * 把「AX 权威名 + 页面侧事实 + 点击捕获快照」收敛成最终名字。
     *
     * <p>规则全部委托 {@link PickNamePolicy}（纯函数、有证据才动）：
     * <ol>
     *   <li>状态播报区文本混进 AX 名 ⇒ 取稳定标签；</li>
     *   <li>点击改写了元素自身文案 ⇒ 用捕获阶段的名字还原。</li>
     * </ol>
     *
     * <p>无证据时返回<b>同一对象</b>{@code ax}（不拷贝、零行为变更）。
     *
     * @param ax  AX 语义合并后的事实（可含播报文本 / 点击后的 loading 文案）
     * @param js  页面侧事实（含 {@code statusText} 与点击后自身文本 {@code text}）
     * @param pre 捕获阶段快照；{@code null} 表示无证据
     */
    static CodegenFacts.Fact resolvePickedName(CodegenFacts.Fact ax, CodegenFacts.Fact js, PreClickSnapshot pre) {
        if (ax == null) return null;
        String stable = PickNamePolicy.stableName(ax.name,
                js == null ? null : js.name, js == null ? null : js.statusText);
        String name = PickNamePolicy.restorePreClickName(stable, js == null ? null : js.text, pre);
        if (Objects.equals(name, ax.name)) return ax;
        return new CodegenFacts.Fact(ax.role, name, ax.tag, ax.id, ax.testid, ax.type, ax.placeholder,
                ax.alt, ax.title, ax.value, ax.text, ax.label, ax.nth, ax.cssSelector, ax.shadowHosts,
                ax.className, ax.xpath, ax.nameAttr, ax.attrs, ax.src, ax.checked, ax.childAnchors, ax.statusText);
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
                log.info("[codegen][cdp] role locator does not resolve (role={}, name='{}') - "
                        + "AX and Playwright roleUtils disagree or element is ARIA-hidden, falling back to CSS", role, name);
            } else if (hits > 1) {
                log.info("[codegen][cdp] role locator is ambiguous (role={}, name='{}', hits={}) - falling back to CSS",
                        role, name, hits);
            } else if (hits < 0) {
                log.debug("[codegen][cdp] role count unavailable (role={}, name='{}', hits={}) - keeping role locator",
                        role, name, hits);
                return -1;
            }
            return hits;
        } catch (Exception | LinkageError e) {
            log.debug("[codegen][cdp] role count failed (role={}): {}", role, e.toString());
            return -1;
        }
    }

    /**
     * 用 CSS 引擎计数（供「role 复核失败 ⇒ 回退 CSS」时刷新 {@code matchCount}）。
     *
     * <p>为什么必须刷新：拾取时的 {@code matchCount} 是那一刻的快照，而复核发生在生成前，
     * 期间页面可能已变化（SPA 渲染 / 面板展开 / 列表增删）。回退成 CSS 之后，
     * 生成侧要按 CSS 的<b>当前</b>命中数决定「单元素 / {@code PageElementList}」，
     * 缺了这一步会把一条本该是列表的定位器生成成单元素（运行期 strict violation），或反之。
     *
     * <p>只对主 frame 元素使用（role 复核本就只在主 frame 发生）；
     * 页级 {@code locator()} 会穿透 open shadow root，与生成物语义一致。
     *
     * @param cssSelector 回退后的 CSS/XPath 定位器；{@code null}/空白视为不可判定
     * @return 命中数；{@code -1} 表示不可判定
     */
    public int countCssMatches(String cssSelector) {
        if (cssSelector == null || cssSelector.isBlank()) return -1;
        try {
            return counter.count(cssSelector);
        } catch (Exception | LinkageError e) {
            log.debug("[codegen][cdp] css count failed for '{}': {}", cssSelector, e.toString());
            return -1;
        }
    }

    /**
     * 「拾取时的那个元素，此刻还在、还看得见吗」——用于判定 role 复核的 0 命中是否可信。
     *
     * <p><b>为什么必须有这一步</b>：role 复核被后置到生成前（省掉点击热路径上每次 10~90ms 的引擎计数），
     * 但生成往往发生在用户又点了几步之后。HSBC 重置密码是<b>同一 document 内</b>的多步向导：点 Continue 后
     * 同一个页面里的上一步被 {@code display:none} 隐藏，于是 {@code getByRole(BUTTON, "Continue")} 命中 0。
     * 若据此把定位器改写成 {@code div:nth-of-type(5) ... > button[class*='float-right']}，
     * 用户手里的 Continue 就永远拿不到 role 定位器——即使它在使用的那个步骤里确实唯一。
     *
     * <p>判据：拾取时的 CSS 当前仍命中元素（{@code count>0}）<b>且</b>该元素当前可见 ⇒ 状态未变，
     * 0 命中是真实的名字口径问题，可以回退 CSS；否则说明页面已推进，本次计数无意义。
     *
     * @return {@link TriState#TRUE}=状态未变；{@link TriState#FALSE}=元素已不在/不可见；
     *         {@link TriState#UNKNOWN}=不可判定（调用方须按保守方向处理：保留 role、标未校验）
     */
    public TriState isPickStateIntact(CodegenEntry entry) {
        if (entry == null || entry.cssSelector == null || entry.cssSelector.isBlank()) return TriState.UNKNOWN;
        int now = countCssMatches(entry.cssSelector);
        if (now < 0) return TriState.UNKNOWN;        // 计数不可判定：既不确认也不否认
        if (now == 0) return TriState.FALSE;
        return counter.isVisible(entry.cssSelector);
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
     * <p><b>归属来源</b>：优先用调用方给的 {@code frameIdHint}（由 {@link CdpFrameLocator} 逐 frame 求值给出，
     * <b>跨域 iframe 同样可靠</b>）；为空时回退
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
     * （{@link CdpFrameLocator} 逐 frame 求值）提供。
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
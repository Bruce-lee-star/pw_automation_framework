package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨 frame 元素定位器：按 CSS 选择器在<b>所有 frame（含跨域 iframe）</b>里查找元素，并带回其所属 frameId。
 *
 * <p><b>为什么必须逐 frame 查</b>：
 * <ul>
 *   <li>主 frame 的 {@code document.querySelector} 看不到 iframe 内部文档 —— 旧兜底恒为 null；</li>
 *   <li>{@code DOM.getNodeForLocation} 命中的是<b>根 frame 坐标</b>下的节点；跨源 iframe（OOPIF）里只会命中
 *       宿主 {@code <iframe>} 元素，甚至直接返回空 —— 这就是「坐标法打空」的根因；</li>
 *   <li>而页面侧上报的 cssSelector 是相对于元素<b>自身文档</b>算出来的（同一 frame 内唯一），
 *       所以在该 frame 的执行上下文里求值必然命中。</li>
 * </ul>
 *
 * <p><b>实现要点</b>：
 * <ul>
 *   <li>frame 列表来自 {@code Page.getFrameTree} 的深度优先展开（主 frame 在最前，绝大多数点击一次命中）；</li>
 *   <li>每个 frame 先用 {@code Page.createIsolatedWorld} 取隔离世界的 {@code executionContextId} 再求值：
 *       隔离世界共享 DOM 且不受页面脚本污染，跨源 frame 同样可用；</li>
 *   <li>contextId 按 frameId 缓存（导航会令其失效）：求值失败即摘除缓存并重试一次（自愈，不残留坏缓存）；</li>
 *   <li>元素若在 open shadow root 内，{@code document.querySelector} <b>不穿透 shadow 边界</b>：需用页面侧给出的
 *       宿主链拼成 {@code document.querySelector(h0)?.shadowRoot?.querySelector(h1)…} 后求值；
 *       closed shadow root 的 {@code shadowRoot} 为 {@code null}，属不可达（按未命中处理，由坐标兜底）。</li>
 * </ul>
 *
 * <p>线程安全：由拾取执行器（单线程）调用；内部缓存用并发容器，避免误用带来的可见性问题。
 */
public final class CdpFrameLocator {

    private static final Logger log = LoggerFactory.getLogger(CdpFrameLocator.class);

    /** 隔离世界名（固定值：同一 frame 复用同一世界，避免无限增殖）。 */
    private static final String WORLD_NAME = "__codegen_probe__";

    private final CdpTransport transport;
    private final Map<String, Integer> contextByFrame = new ConcurrentHashMap<>();
    /**
     * DOM 文档树是否已推给 agent（{@code DOM.getDocument} 成功过）。
     *
     * <p>{@code volatile}：拾取执行器单线程写、偶发排障读取；此处只需可见性，无需互斥（置 true 是幂等操作）。
     */
    private volatile boolean documentPrimed;
    /** 缓存的 frame 树快照（导航 / iframe 增删会令其失效，见 {@link #invalidateTree}）。 */
    private volatile FrameTree cachedTree;

    public CdpFrameLocator(CodegenCdpSession session) {
        this(Objects.requireNonNull(session, "session")::send);
    }

    /** 测试 seam：直接注入 CDP 发送通道。 */
    CdpFrameLocator(CdpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 在所有 frame 中按 CSS 选择器查找元素（主 frame 优先）。
     *
     * @param cssSelector 页面侧对该元素算出的 CSS 选择器（相对其自身文档）
     * @return 命中的元素引用（含所属 frameId）；未命中返回 {@code null}
     */
    public ElementRef findBySelector(String cssSelector) {
        return findBySelector(cssSelector, List.of());
    }

    /**
     * 在所有 frame 中按「shadow 宿主链 + 内层选择器」查找元素（主 frame 优先）。
     *
     * <p>{@code document.querySelector} <b>不穿透 shadow 边界</b>：元素若在 open shadow root 内，只有把页面侧给出的
     * 宿主链拼成 {@code document.querySelector(h0)?.shadowRoot?.querySelector(h1)…?.shadowRoot?.querySelector(css)}
     * 才能命中。宿主与内层选择器都相对「自身文档 / 外层 shadow root」计算，故可逐层拼接。
     *
     * <p><b>closed shadow root 不可达</b>（{@code host.shadowRoot} 为 {@code null}）：可选链使表达式返回
     * {@code undefined}，等价「未命中」而不抛异常，由坐标兜底。
     *
     * @param cssSelector 页面侧算出的内层 CSS 选择器（相对其自身文档 / 最内层 shadow root）
     * @param shadowHosts 宿主选择器链（outermost-first）；{@code null}/空等价于普通文档内查找
     * @return 命中的元素引用（含所属 frameId）；未命中或宿主链不可信返回 {@code null}
     */
    public ElementRef findBySelector(String cssSelector, List<String> shadowHosts) {
        if (cssSelector == null || cssSelector.isBlank()) return null;
        List<String> hosts = shadowHosts == null ? List.of() : shadowHosts;
        for (String host : hosts) {
            if (host == null || host.isBlank()) {
                // 宿主链不可信（拼进表达式就是非法选择器）：放弃选择器路径，让调用方走坐标兜底。
                log.debug("[codegen][cdp] unusable shadow host chain {} for selector '{}' — skipped",
                        hosts, cssSelector);
                return null;
            }
        }
        // 取（缓存的）frame 树：枚举 frame 逐 frame 求值，并产出 parent 映射快照随 ElementRef 带给抽取阶段。
        FrameTree t = tree();
        if (t == null || t.frameIds.isEmpty()) {
            log.warn("[codegen][cdp] Page.getFrameTree returned no frame — CDP 会话不可用，选择器反查跳过");
            return null;
        }
        int degradedFrames = 0;
        for (int i = 0; i < t.frameIds.size(); i++) {
            String frameId = t.frameIds.get(i);
            Probe outcome = probe(frameId, cssSelector, hosts);
            if (outcome.hit() != null) {
                if (i > 0) {
                    log.info("[codegen][cdp] selector '{}' matched in sub frame {} ({}/{} frame(s) probed)",
                            cssSelector, frameId, i + 1, t.frameIds.size());
                }
                if (!hosts.isEmpty()) {
                    log.debug("[codegen][cdp] selector '{}' resolved through {} shadow host(s) in frame {}",
                            cssSelector, hosts.size(), frameId);
                }
                ElementRef.FrameScope scope = (i == 0)
                        ? ElementRef.FrameScope.MAIN : ElementRef.FrameScope.SUB;
                ElementRef hit = outcome.hit();
                ElementRef.Frames snap = new ElementRef.Frames(t.frameIds.get(0), t.parentByFrame);
                return new ElementRef(hit.backendNodeId(), hit.frameId(), scope, hit.objectId(), snap);
            }
            if (outcome.degraded()) degradedFrames++;
        }
        if (degradedFrames == t.frameIds.size()) {
            // 全部 frame 都是"基础设施失败"而非"元素不存在"：必须显性告警，否则看起来和"页面上没这个元素"一样，
            // 排查时无从区分（这是历史上 selector 反查静默失效、全部退化到坐标法的原因）。
            log.warn("[codegen][cdp] selector '{}' could not be probed in any of {} frame(s) — "
                            + "隔离世界/CDP 求值整体失败（页面导航中 / frame 未就绪 / CDP 会话异常），本次退回坐标 hit-test",
                    cssSelector, t.frameIds.size());
        } else {
            log.debug("[codegen][cdp] selector '{}' matched no element in {} frame(s)", cssSelector, t.frameIds.size());
        }
        return null;
    }

    /**
     * 已知元素所属 frame 路径（outer→inner 的 iframe 序号）时，<b>只探那一帧</b>，把每拾取 CDP 往返从
     * {@code 2×iframe数 + 1} 降到约 {@code 2}。路径无法自描述（跨源 iframe 内 frameElement 为 null）时为 null，
     * 直接回退 {@link #findBySelector(String, List)} 的全帧扫描，保持跨源正确性。
     * 路径过期（iframe 增删）导致解析失败或命中帧内未命中时，同样回退全帧扫描兜底。
     *
     * @param framePath 浏览器侧算出的 iframe 序号链（主 frame 为 []）；{@code null}/不可信 ⇒ 回退
     */
    public ElementRef findBySelector(String cssSelector, List<String> shadowHosts, List<Integer> framePath) {
        List<String> hosts = shadowHosts == null ? List.of() : shadowHosts;
        for (String host : hosts) {
            if (host == null || host.isBlank()) {
                // 宿主链不可信：放弃选择器路径，直接回退全帧扫描（由被调方法走坐标兜底）。
                log.debug("[codegen][cdp] unusable shadow host chain {} for selector '{}' — fall back",
                        hosts, cssSelector);
                return findBySelector(cssSelector, shadowHosts);
            }
        }
        if (framePath == null) return findBySelector(cssSelector, hosts);
        FrameTree t = tree();
        String frameId = (t == null) ? null : resolveFrameId(t, framePath);
        if (frameId != null) {
            Probe outcome = probe(frameId, cssSelector, hosts);
            if (outcome.hit() != null) {
                ElementRef hit = outcome.hit();
                ElementRef.FrameScope scope = frameId.equals(t.mainFrameId)
                        ? ElementRef.FrameScope.MAIN : ElementRef.FrameScope.SUB;
                ElementRef.Frames snap = new ElementRef.Frames(t.mainFrameId, t.parentByFrame);
                log.debug("[codegen][cdp] resolved by frame-path {} → frame {} '{}'", framePath, frameId, cssSelector);
                return new ElementRef(hit.backendNodeId(), hit.frameId(), scope, hit.objectId(), snap);
            }
            log.debug("[codegen][cdp] frame-path {} → {} but selector '{}' not hit — fall back to full scan",
                    framePath, frameId, cssSelector);
        } else {
            log.debug("[codegen][cdp] frame-path {} could not be resolved (stale tree?) — fall back to full scan",
                    framePath);
        }
        return findBySelector(cssSelector, hosts);
    }

    /** 按 iframe 序号链解析出 CDP frameId（outer→inner）；任一层序号越界/无匹配返回 null。 */
    private static String resolveFrameId(FrameTree t, List<Integer> path) {
        String cur = t.mainFrameId;
        if (cur == null) return null;
        for (int idx : path) {
            String child = childAt(t, cur, idx);
            if (child == null) return null;
            cur = child;
        }
        return cur;
    }

    /** 取 parent 的第 idx 个子 iframe（按文档顺序，与 Page.getFrameTree 的 childFrames 一致）。 */
    private static String childAt(FrameTree t, String parent, int idx) {
        int seen = 0;
        for (String id : t.frameIds) {
            if (parent.equals(t.parentByFrame.get(id))) {
                if (seen == idx) return id;
                seen++;
            }
        }
        return null;
    }

    /** 取（并缓存）frame 树：主 frameId + 父子映射 + 文档顺序的 frameId 列表（主 frame 在最前）。 */
    private FrameTree tree() {
        FrameTree t = cachedTree;
        if (t != null) return t;
        synchronized (this) {
            t = cachedTree;
            if (t != null) return t;
            JsonObject node = CdpJson.objectField(transport.send("Page.getFrameTree", Map.of()), "frameTree");
            if (node == null) return null;
            List<String> ids = new ArrayList<>();
            Map<String, String> parent = new HashMap<>();
            flatten(node, null, ids, parent);
            String main = ids.isEmpty() ? null : ids.get(0);
            t = new FrameTree(main, parent, ids);
            cachedTree = t;
            return t;
        }
    }

    /** 使缓存的 frame 树失效（导航 / iframe 增删后由 {@link #contextId} 触发重建）。 */
    private void invalidateTree() {
        cachedTree = null;
    }

    /** 缓存的 frame 树快照：主 frameId + 父子映射 + 文档顺序的 frameId 列表（主 frame 在最前）。 */
    private static final class FrameTree {
        final String mainFrameId;
        final Map<String, String> parentByFrame;
        final List<String> frameIds;
        FrameTree(String mainFrameId, Map<String, String> parentByFrame, List<String> frameIds) {
            this.mainFrameId = mainFrameId;
            this.parentByFrame = parentByFrame;
            this.frameIds = frameIds;
        }
    }

    /**
     * 在<b>指定 frame 的隔离世界</b>里一次性统计一批候选选择器的命中数。
     *
     * <p>为什么不用 {@code page.locator(css).count()}：
     * <ul>
     *   <li><b>作用域错</b>：页级定位器只搜索主 frame，iframe 内元素恒 0 命中，而 {@code count=0} 被
     *       "视为唯一"⇒ 唯一性等于从未校验；这里按元素所属 frame（+ shadow 宿主链）计数，口径与生成的
     *       {@code frame=… :: <css>} 定位器一致；</li>
     *   <li><b>往返多</b>：Playwright 每次 {@code count()} 都是一次独立往返（大页面上毫秒级），
     *       而候选链动辄 5~20 个。这里合并成<b>一次</b> {@code Runtime.evaluate}。</li>
     * </ul>
     *
     * <p>求值失败（导航中 / 上下文销毁 / 表达式异常）返回空 Map，调用方据此回退到 Playwright 计数，
     * 绝不把"查不到"当成"唯一"。
     *
     * @param frameId     元素所属 frameId（{@code null}/空表示未知，直接返回空 Map 交给调用方兜底）
     * @param shadowHosts 元素所在的 open shadow 宿主链（outermost-first），主文档元素为空
     * @param selectors   待统计的选择器（支持 {@code xpath=} 前缀；其它按 CSS 计数）
     * @return 选择器 → 命中数；无法判定时该选择器不出现在结果里
     */
    Map<String, Integer> countSelectors(String frameId, List<String> shadowHosts, List<String> selectors) {
        if (frameId == null || frameId.isEmpty() || selectors == null || selectors.isEmpty()) return Map.of();
        List<String> hosts = shadowHosts == null ? List.of() : shadowHosts;
        String expression = buildCountExpression(hosts, selectors);
        int contextId = contextId(frameId);
        if (contextId <= 0) return Map.of();
        JsonObject evaluated = transport.send("Runtime.evaluate", Map.of(
                "expression", expression,
                "contextId", contextId,
                "returnByValue", true));
        if (evaluated == null) {
            contextByFrame.remove(frameId);   // 上下文随导航销毁：摘缓存，下一次重建
            return Map.of();
        }
        if (CdpJson.field(evaluated, "exceptionDetails") != null) {
            log.debug("[codegen][cdp] count evaluate threw in frame {}: {}", frameId,
                    CdpJson.stringField(CdpJson.objectField(evaluated, "exceptionDetails"), "text"));
            return Map.of();
        }
        JsonObject remote = CdpJson.remoteObject(evaluated);
        JsonElement value = remote == null ? null : CdpJson.field(remote, "value");
        if (value == null || !value.isJsonArray()) return Map.of();
        JsonArray counts = value.getAsJsonArray();
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < selectors.size() && i < counts.size(); i++) {
            JsonElement c = counts.get(i);
            if (c == null || c.isJsonNull()) continue;
            try {
                int n = c.getAsInt();
                if (n >= 0) out.put(selectors.get(i), n);
            } catch (RuntimeException ignore) {
                // 非数值（页面被冻结/返回非预期）⇒ 该选择器不可判定，交给调用方兜底
            }
        }
        return out;
    }

    /**
     * 拼批量计数表达式：在 frame（+ shadow 链）作用域内统计每个选择器的命中数。
     *
     * <p>返回 {@code -1} 表示该选择器无法求值（选择器非法 / XPath 语法错），调用方视为"不可判定"；
     * {@code xpath=} 前缀按文档作用域求值（与 {@code LocatorFactory} 的 XPath 解析口径一致——
     * 生成的 XPath 是绝对路径 {@code /html/...}）。
     *
     * <p>宿主链不可达（closed shadow root / 宿主被移除）时整体返回 {@code null} ⇒ 调用方回退兜底。
     */
    static String buildCountExpression(List<String> shadowHosts, List<String> selectors) {
        JsonArray hostsJson = new JsonArray();
        for (String h : shadowHosts) hostsJson.add(h == null ? "" : h);
        JsonArray selectorsJson = new JsonArray();
        for (String s : selectors) selectorsJson.add(s == null ? "" : s);
        return "(" + COUNT_FN + ")(" + hostsJson + "," + selectorsJson + ")";
    }

    /** 自包含计数函数（不依赖页面上的任何全局变量，隔离世界里直接可执行）。 */
    private static final String COUNT_FN = "function(hosts, sels){"
            + "var root = document;"
            + "for (var i = 0; i < hosts.length; i++) {"
            + "  var host = root.querySelector(hosts[i]);"
            + "  if (!host || !host.shadowRoot) return null;"
            + "  root = host.shadowRoot;"
            + "}"
            + "var out = [];"
            + "for (var j = 0; j < sels.length; j++) {"
            + "  var sel = sels[j];"
            + "  try {"
            + "    if (sel.indexOf('xpath=') === 0) {"
            + "      var r = document.evaluate(sel.substring(6), document, null, 7, null);"
            + "      out.push(r.snapshotLength);"
            + "    } else {"
            + "      out.push(root.querySelectorAll(sel).length);"
            + "    }"
            + "  } catch (e) { out.push(-1); }"
            + "}"
            + "return out;"
            + "}";

    /**
     * 深度优先展开 {@code FrameTree}（{@code frame} + {@code childFrames}），按文档顺序收集 frameId，
     * 并登记每个 frame 的父 frameId（首节点即主 frame，parent 为 {@code null}）。
     */
    static void flatten(JsonObject frameTree, String parentFrameId, List<String> out,
                        Map<String, String> parentByFrame) {
        if (frameTree == null) return;
        String id = CdpJson.stringField(CdpJson.objectField(frameTree, "frame"), "id");
        if (id != null && !id.isEmpty()) {
            out.add(id);
            if (parentFrameId != null) parentByFrame.put(id, parentFrameId);
        }
        JsonArray children = CdpJson.arrayField(frameTree, "childFrames");
        if (children != null) {
            for (JsonElement child : children) {
                if (child != null && child.isJsonObject()) {
                    flatten(child.getAsJsonObject(), id, out, parentByFrame);
                }
            }
        }
    }

    /**
     * 单 frame 探测结果。
     *
     * @param hit      命中元素；{@code null} 表示未命中
     * @param degraded {@code true} 表示未命中是<b>基础设施失败</b>（隔离世界取不到 / CDP 求值失败 / 节点解析失败），
     *                 而非"该选择器在这个 frame 里没有元素"——两者排障含义完全不同
     */
    private record Probe(ElementRef hit, boolean degraded) {
    }

    /** 在指定 frame 的隔离世界里求值；contextId 失效时重建并重试一次。 */
    private Probe probe(String frameId, String cssSelector, List<String> shadowHosts) {
        String expression = buildExpression(cssSelector, shadowHosts);
        boolean degraded = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            int contextId = contextId(frameId);
            if (contextId <= 0) return new Probe(null, true);
            JsonObject evaluated = transport.send("Runtime.evaluate", Map.of(
                    "expression", expression,
                    "contextId", contextId,
                    "returnByValue", false));
            if (evaluated == null) {
                // 上下文已随导航销毁：摘掉缓存，下一轮重建隔离世界。
                contextByFrame.remove(frameId);
                degraded = true;
                continue;
            }
            if (CdpJson.field(evaluated, "exceptionDetails") != null) {
                log.debug("[codegen][cdp] evaluate threw in frame {} for '{}': {}", frameId, cssSelector,
                        CdpJson.stringField(CdpJson.objectField(evaluated, "exceptionDetails"), "text"));
                return new Probe(null, false);   // 选择器/表达式在该 frame 无效 ⇒ 视作未命中
            }
            JsonObject remote = CdpJson.remoteObject(evaluated);
            String objectId = remote == null ? null : CdpJson.stringField(remote, "objectId");
            if (objectId == null) return new Probe(null, false);   // 未命中：querySelector 返回 null（无 objectId）
            Long backendNodeId = backendNodeIdOf(objectId);
            if (backendNodeId == null) {
                log.debug("[codegen][cdp] node resolution failed in frame {} for '{}' (objectId={})",
                        frameId, cssSelector, objectId);
                return new Probe(null, true);
            }
            // objectId 随引用带出：抽取阶段可直接 callFunctionOn（省一次 DOM.resolveNode）。
            return new Probe(new ElementRef(backendNodeId, frameId, ElementRef.FrameScope.UNKNOWN, objectId),
                    false);
        }
        log.debug("[codegen][cdp] evaluate failed twice in frame {} (context destroyed?) for '{}'",
                frameId, cssSelector);
        return new Probe(null, degraded);
    }

    /**
     * 拼求值表达式：无 shadow 宿主时退化为 {@code document.querySelector(css)}；
     * 有宿主链时拼接 {@code document.querySelector(h0)?.shadowRoot?.querySelector(h1)…?.shadowRoot?.querySelector(css)}。
     *
     * <p>用可选链（{@code ?.}）而非裸取属性：closed shadow root 的 {@code shadowRoot} 为 {@code null}，
     * 裸取会抛 {@code TypeError} 变成 {@code exceptionDetails}（语义上被误判为"选择器非法"），
     * 可选链则得到 {@code undefined}（无 {@code objectId}）⇒ 正确的"未命中"。
     *
     * <p>选择器一律经 {@link CdpJson#jsStringLiteral} 转义，宿主链来自页面侧事实，不排除含引号的 id。
     */
    static String buildExpression(String cssSelector, List<String> shadowHosts) {
        if (shadowHosts == null || shadowHosts.isEmpty()) {
            return "document.querySelector(" + CdpJson.jsStringLiteral(cssSelector) + ")";
        }
        StringBuilder sb = new StringBuilder("document.querySelector(")
                .append(CdpJson.jsStringLiteral(shadowHosts.get(0)))
                .append(')');
        for (int i = 1; i < shadowHosts.size(); i++) {
            sb.append("?.shadowRoot?.querySelector(")
                    .append(CdpJson.jsStringLiteral(shadowHosts.get(i)))
                    .append(')');
        }
        return sb.append("?.shadowRoot?.querySelector(")
                .append(CdpJson.jsStringLiteral(cssSelector))
                .append(')')
                .toString();
    }

    /**
     * RemoteObject(objectId) → backendNodeId。
     *
     * <p><b>一次 {@code DOM.describeNode}，不再经 {@code DOM.requestNode} 中转</b>：{@code requestNode} 只有在
     * DOM agent 已经持有文档树时才有效（否则恒返回 {@code nodeId=0}，历史缺陷即由此产生——每次拾取都先白跑
     * 一遍注定失败的选择器探测，再退回坐标 hit-test，跨源 iframe 内元素因此完全无法按选择器命中）。
     * {@code describeNode} 直接接受 {@code objectId}，少一次往返且不依赖节点表。
     *
     * <p>仍然保留自愈：万一当前 CDP 版本不支持该形态（或文档树尚未建立），拉一次
     * {@code DOM.getDocument} 后重试，绝不静默退化成“每次都走坐标”。
     */
    private Long backendNodeIdOf(String objectId) {
        Long backendNodeId = describeBackendNodeId(objectId);
        if (backendNodeId != null) return backendNodeId;
        if (ensureDocument()) {
            backendNodeId = describeBackendNodeId(objectId);
            if (backendNodeId != null) return backendNodeId;
        }
        return null;
    }

    private Long describeBackendNodeId(String objectId) {
        JsonObject described = transport.send("DOM.describeNode", Map.of("objectId", objectId));
        if (described == null) return null;
        JsonObject node = CdpJson.objectField(described, "node");
        if (node == null) node = described;
        Long backendNodeId = CdpJson.longField(node, "backendNodeId");
        return (backendNodeId == null || backendNodeId <= 0) ? null : backendNodeId;
    }

    /**
     * 让 DOM agent 建立当前文档的节点表（{@code DOM.getDocument}）。
     *
     * <p>{@code DOM.enable} 只是订阅域事件，<b>不会</b>把文档树推给 agent；凡是走
     * {@code DOM.requestNode} / {@code DOM.querySelector} 一类「按节点表查」的命令，都必须先有这一步。
     * 成功一次即置位（导航后由 {@link #describeBackendNodeId} 的失败自愈再补），避免每次拾取都多发一条命令。
     *
     * @return {@code true} 表示文档树已就绪
     */
    boolean ensureDocument() {
        if (documentPrimed) return true;
        JsonObject payload = transport.send("DOM.getDocument", Map.of());
        boolean ok = payload != null && CdpJson.objectField(payload, "root") != null;
        documentPrimed = ok;
        if (ok) {
            log.debug("[codegen][cdp] DOM document primed (getDocument)");
        } else {
            log.debug("[codegen][cdp] DOM.getDocument failed — selector probing may degrade to coordinates");
        }
        return ok;
    }

    /** 取（并缓存）frame 的隔离世界 executionContextId；失败返回 {@code -1}。 */
    private int contextId(String frameId) {
        Integer cached = contextByFrame.get(frameId);
        if (cached != null && cached > 0) return cached;
        JsonObject created = transport.send("Page.createIsolatedWorld",
                Map.of("frameId", frameId, "worldName", WORLD_NAME));
        Long id = CdpJson.longField(created, "executionContextId");
        if (id == null) {
            log.debug("[codegen][cdp] createIsolatedWorld failed for frame {} — frame 可能已失效，令帧树失效待重建",
                    frameId);
            invalidateTree();   // 建隔离世界失败 ⇒ 该 frame 多半已随导航/移除消失，帧树可能过期，下次 tree() 重建
            return -1;
        }
        contextByFrame.put(frameId, id.intValue());
        return id.intValue();
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link CdpFrameLocator} 契约：逐 frame 求值（主 frame 优先）、隔离世界上下文缓存、失效自愈。
 *
 * <p>这是「跨源 iframe 内元素拾取」的核心：主 frame 的 {@code document.querySelector} 看不到 iframe 内部文档，
 * 必须在该 frame 自己的执行上下文里求值；命中后还要带回 frameId 供穿透路径使用。
 */
public class CdpFrameLocatorTest {

    @Test
    public void findsElementInsideSubFrameAndReportsFrameId() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("F1", "MAIN", "#userName", 300L);

        ElementRef ref = new CdpFrameLocator(cdp).findBySelector("#userName");

        assertNotNull(ref);
        assertEquals(300L, ref.backendNodeId());
        assertEquals("F1", ref.frameId());
        assertEquals("子 frame 命中必须带出 SUB 归属（下游据此跳过页级 role 复核）",
                ElementRef.FrameScope.SUB, ref.scope());
        assertEquals("主 frame 应先被探测，未命中再进入子 frame", List.of("MAIN", "F1"), cdp.probedFrames);
    }

    /** 主 frame 命中 ⇒ 归属 MAIN（下游可据此免读 frame 树）。 */
    @Test
    public void mainFrameHitReportsMainScope() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("MAIN", null, "#userName", 300L);

        ElementRef ref = new CdpFrameLocator(cdp).findBySelector("#userName");

        assertNotNull(ref);
        assertEquals(ElementRef.FrameScope.MAIN, ref.scope());
        assertFalse("主 frame 元素不应被判为子 frame", ref.inSubFrame());
    }

    /**
     * DOM agent 尚未持有文档树时（历史缺陷形态：{@code DOM.enable} 之后没调 {@code DOM.getDocument}），
     * {@code DOM.describeNode} 拿不到 backendNodeId —— 必须自愈一次后成功，绝不静默退回坐标法。
     */
    @Test
    public void nodeResolutionSelfHealsByPrimingDocument() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("MAIN", null, "#userName", 300L);
        cdp.requireDocumentForNodeResolution = true;

        ElementRef ref = new CdpFrameLocator(cdp).findBySelector("#userName");

        assertNotNull("拉取文档树后重试必须成功", ref);
        assertEquals(300L, ref.backendNodeId());
        assertEquals("只补一次 DOM.getDocument", 1, cdp.getDocumentCalls);
    }

    /** 文档树成功建立后再次拾取不再重复拉取（避免每次拾取多发一条命令）。 */
    @Test
    public void documentIsPrimedOnlyOnce() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("MAIN", null, "#userName", 300L);
        cdp.requireDocumentForNodeResolution = true;
        CdpFrameLocator locator = new CdpFrameLocator(cdp);

        locator.findBySelector("#userName");
        locator.findBySelector("#userName");

        assertEquals("DOM.getDocument 是幂等预热，不应每次拾取都发", 1, cdp.getDocumentCalls);
    }

    /** 节点解析彻底失败（文档树也拉不到）⇒ 返回 null 让调用方走坐标兜底，不抛异常。 */
    @Test
    public void nodeResolutionFailureReturnsNull() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("MAIN", null, "#userName", 300L);
        cdp.failNodeResolution = true;

        assertNull(new CdpFrameLocator(cdp).findBySelector("#userName"));
        assertEquals("失败时也应尝试过补文档树（一次性）", 1, cdp.getDocumentCalls);
    }

    /**
     * 批量计数：整条候选链只发<b>一次</b> {@code Runtime.evaluate}，并按选择器返回命中数。
     *
     * <p>这是 CSS 唯一性校验的性能与作用域双重修复：页级 {@code page.locator} 逐候选一次往返
     * （大页面每个 3~6ms），且只看主 frame（iframe 元素恒 0 命中 ⇒ 未校验）。
     */
    @Test
    public void countSelectorsUsesSingleEvaluateAndReturnsPerSelectorCounts() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("F1", "MAIN", "#save", 300L);
        cdp.countsBySelector.put("#save", 1);
        cdp.countsBySelector.put("button.btn", 4);

        Map<String, Integer> counts = new CdpFrameLocator(cdp)
                .countSelectors("F1", List.of("#host"), List.of("#save", "button.btn"));

        assertEquals(1, counts.get("#save").intValue());
        assertEquals(4, counts.get("button.btn").intValue());
        assertEquals("整条候选链只允许一次求值", 1, cdp.evaluateCalls);
    }

    /** 求值失败（导航中 / 上下文销毁）⇒ 返回空 Map，让调用方回退页级计数而不是把"查不到"当唯一。 */
    @Test
    public void countSelectorsReturnsEmptyOnEvaluateFailure() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("MAIN", null, "#save", 300L);
        cdp.failBatchEvaluate = true;

        Map<String, Integer> counts = new CdpFrameLocator(cdp)
                .countSelectors("MAIN", List.of(), List.of("#save"));

        assertEquals("不可判定必须回退，不能伪造成唯一", 0, counts.size());
    }

    /** frameId 未知时不发无用协议请求（调用方直接回退页级计数）。 */
    @Test
    public void countSelectorsWithoutFrameIdSkipsCdp() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("MAIN", null, "#save", 300L);

        Map<String, Integer> counts = new CdpFrameLocator(cdp)
                .countSelectors(null, List.of(), List.of("#save"));

        assertEquals(0, counts.size());
        assertEquals(0, cdp.evaluateCalls);
    }

    /** 计数表达式必须走 shadow 宿主链，且 XPath 候选按文档作用域（{@code document.evaluate}）统计。 */
    @Test
    public void buildCountExpressionHandlesShadowChainAndXpath() {
        String expression = CdpFrameLocator.buildCountExpression(
                List.of("#host"), List.of("#inner", "xpath=/html/body/div[1]"));

        assertTrue("必须沿 shadowRoot 下钻", expression.contains("shadowRoot"));
        assertTrue("XPath 候选必须用 document.evaluate",
                expression.contains("document.evaluate"));
        assertTrue("选择器要按 JSON 数组下发", expression.contains("\"#inner\""));
    }

    @Test
    public void isolatedWorldIsCachedPerFrame() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("F1", "MAIN", "#userName", 300L);
        CdpFrameLocator locator = new CdpFrameLocator(cdp);

        locator.findBySelector("#userName");
        locator.findBySelector("#userName");

        assertEquals("每个 frame 只应创建一个隔离世界", List.of("MAIN", "F1"), cdp.createdWorlds);
    }

    @Test
    public void unknownSelectorReturnsNull() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("F1", "MAIN", "#userName", 300L);

        assertNull(new CdpFrameLocator(cdp).findBySelector("#notThere"));
    }

    @Test
    public void staleContextIsRebuiltOnce() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("F1", "MAIN", "#userName", 300L);
        cdp.failFirstEvaluateFor.add("F1");

        ElementRef ref = new CdpFrameLocator(cdp).findBySelector("#userName");

        assertNotNull("上下文失效后应重建隔离世界并重试", ref);
        assertEquals("F1", ref.frameId());
        assertEquals("F1 的隔离世界应被重建一次", 2L, cdp.createdWorlds.stream().filter("F1"::equals).count());
    }

    @Test
    public void shadowHostChainIsEvaluatedAsChainedQuery() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("F1", "MAIN", "#inner", 500L);

        ElementRef ref = new CdpFrameLocator(cdp).findBySelector("#inner", List.of("#host"));

        assertNotNull("open shadow root 内元素应能经宿主链命中", ref);
        assertEquals("F1", ref.frameId());
        assertEquals("document.querySelector(\"#host\")?.shadowRoot?.querySelector(\"#inner\")",
                cdp.lastExpression);
    }

    @Test
    public void buildExpressionNestsMultipleShadowHostsOutermostFirst() {
        assertEquals("document.querySelector(\"#h0\")?.shadowRoot?.querySelector(\"#h1\")"
                        + "?.shadowRoot?.querySelector(\"#inner\")",
                CdpFrameLocator.buildExpression("#inner", List.of("#h0", "#h1")));
        assertEquals("document.querySelector(\"#inner\")",
                CdpFrameLocator.buildExpression("#inner", List.of()));
        assertEquals("document.querySelector(\"#inner\")",
                CdpFrameLocator.buildExpression("#inner", null));
    }

    @Test
    public void buildExpressionEscapesQuotesInHostAndInnerSelectors() {
        assertEquals("document.querySelector(\"[data-v=\\\"1\\\"]\")?.shadowRoot?.querySelector(\"#a\\\\b\")",
                CdpFrameLocator.buildExpression("#a\\b", List.of("[data-v=\"1\"]")));
    }

    @Test
    public void unusableShadowChainSkipsCdpRoundTrip() {
        FakeFrames cdp = new FakeFrames();
        cdp.addFrame("F1", "MAIN", "#inner", 500L);

        assertNull("宿主链含空项时不可信，应放弃选择器路径（回退坐标兜底）",
                new CdpFrameLocator(cdp).findBySelector("#inner", List.of("#host", "")));
        assertNull(cdp.lastExpression);
        assertEquals("不应发起任何 frame 探测", List.of(), cdp.probedFrames);
    }

    @Test
    public void flattenFollowsDepthFirstOrder() {
        JsonObject tree = JsonParser.parseString("{\"frame\":{\"id\":\"MAIN\"},\"childFrames\":["
                + "{\"frame\":{\"id\":\"F1\"},\"childFrames\":[{\"frame\":{\"id\":\"F2\"}}]},"
                + "{\"frame\":{\"id\":\"F3\"}}]}").getAsJsonObject();
        List<String> ids = new ArrayList<>();
        Map<String, String> parentByFrame = new LinkedHashMap<>();

        CdpFrameLocator.flatten(tree, null, ids, parentByFrame);

        assertEquals(List.of("MAIN", "F1", "F2", "F3"), ids);
        // 同时校验 parent 映射（穿透路径上行依赖）：首节点 MAIN 无父，其余指向各自父 frame。
        assertEquals(Map.of("F1", "MAIN", "F2", "F1", "F3", "MAIN"), parentByFrame);
    }

    /** 假 frame 树 + 假隔离世界：只为覆盖定位算法，不建真实 CDP 会话。 */
    private static final class FakeFrames implements CdpTransport {

        final Map<String, String> parentByFrame = new LinkedHashMap<>();
        /** frameId → 该 frame 文档里存在的选择器。 */
        final Map<String, String> selectorByFrame = new HashMap<>();
        final Map<String, Long> nodeByFrame = new HashMap<>();
        final Map<Integer, String> frameByContext = new HashMap<>();
        final List<String> probedFrames = new ArrayList<>();
        final List<String> createdWorlds = new ArrayList<>();
        final Set<String> failFirstEvaluateFor = new HashSet<>();
        int nextContextId = 100;
        /** {@code DOM.getDocument} 被调用次数（锁定"预热一次"与"失败自愈一次"两条语义）。 */
        int getDocumentCalls;
        /**
         * true ⇒ 模拟「DOM agent 尚未持有文档树」：未预热前 {@code DOM.describeNode} 解析不到 backendNodeId。
         *
         * <p>这正是历史缺陷的形态：{@code DOM.enable} 只订阅事件、不推文档树，导致选择器反查整体失效。
         */
        boolean requireDocumentForNodeResolution;
        /** true ⇒ 无论是否预热都解析不到 backendNodeId（模拟节点已被移除 / 域不可用）。 */
        boolean failNodeResolution;
        /** 批量计数的应答（选择器 → 命中数；按插入顺序返回）。 */
        final Map<String, Integer> countsBySelector = new java.util.LinkedHashMap<>();
        /** true ⇒ 批量计数求值直接失败（模拟导航中 / 上下文销毁）。 */
        boolean failBatchEvaluate;
        /** {@code Runtime.evaluate} 被调用次数（用于锁定"批量计数只发一次"）。 */
        int evaluateCalls;
        /** 最近一次 {@code Runtime.evaluate} 的表达式（用于锁定 shadow 链拼接形态）。 */
        String lastExpression;

        FakeFrames() {
            parentByFrame.put("MAIN", null);
        }

        void addFrame(String frameId, String parentFrameId, String selector, long backendNodeId) {
            parentByFrame.put(frameId, parentFrameId);
            selectorByFrame.put(frameId, selector);
            nodeByFrame.put(frameId, backendNodeId);
        }

        @Override
        public JsonObject send(String method, Map<String, Object> params) {
            switch (method) {
                case "Page.getFrameTree": {
                    JsonObject payload = new JsonObject();
                    payload.add("frameTree", frameTreeOf("MAIN"));
                    return payload;
                }
                case "Page.createIsolatedWorld": {
                    // 假实现每次都新建上下文（真实 CDP 亦如此）：缓存完全由 CdpFrameLocator 负责，
                    // 这样「缓存命中」与「失效重建」两种路径才能被区分开。
                    String frameId = String.valueOf(params.get("frameId"));
                    createdWorlds.add(frameId);
                    int contextId = nextContextId++;
                    frameByContext.put(contextId, frameId);
                    return contextPayload(contextId);
                }
                case "Runtime.evaluate":
                    return evaluate(
                            ((Number) params.get("contextId")).intValue(),
                            String.valueOf(params.get("expression")),
                            Boolean.TRUE.equals(params.get("returnByValue")));
                case "DOM.getDocument": {
                    getDocumentCalls++;
                    JsonObject root = new JsonObject();
                    root.addProperty("nodeId", 1L);
                    JsonObject payload = new JsonObject();
                    payload.add("root", root);
                    return payload;
                }
                case "DOM.describeNode": {
                    // 生产路径用 describeNode(objectId) 直取 backendNodeId（不再经 requestNode 中转）。
                    if (failNodeResolution) return nodePayload(null);
                    if (requireDocumentForNodeResolution && getDocumentCalls == 0) return nodePayload(null);
                    String objectId = String.valueOf(params.get("objectId"));
                    String frameId = objectId.startsWith("obj-") ? objectId.substring("obj-".length()) : null;
                    return nodePayload(frameId == null ? null : nodeByFrame.get(frameId));
                }
                default:
                    return null;
            }
        }

        /** 求值：模拟 {@code document.querySelector} 在该 frame 文档内的结果（或批量计数的返回值）。 */
        private JsonObject evaluate(int contextId, String expression, boolean returnByValue) {
            String frameId = frameByContext.get(contextId);
            if (frameId == null) return null;
            evaluateCalls++;
            lastExpression = expression;
            probedFrames.add(frameId);
            if (failBatchEvaluate) return null;
            if (failFirstEvaluateFor.remove(frameId)) return null;   // 模拟上下文已随导航销毁
            if (returnByValue) {
                JsonArray counts = new JsonArray();
                for (Map.Entry<String, Integer> e : countsBySelector.entrySet()) {
                    if (expression.contains("\"" + e.getKey() + "\"")) counts.add(e.getValue());
                }
                JsonObject remote = new JsonObject();
                remote.addProperty("type", "object");
                remote.add("value", counts);
                JsonObject payload = new JsonObject();
                payload.add("result", remote);
                return payload;
            }
            String selector = selectorByFrame.get(frameId);
            JsonObject result = new JsonObject();
            JsonObject payload = new JsonObject();
            if (selector != null && expression.contains("\"" + selector + "\"")) {
                result.addProperty("type", "object");
                result.addProperty("objectId", "obj-" + frameId);
            } else {
                result.addProperty("type", "object");
                result.addProperty("subtype", "null");
            }
            payload.add("result", result);
            return payload;
        }

        /** {@code DOM.describeNode} 的应答形态：解析失败时 node 里不带 backendNodeId（真实 CDP 如此）。 */
        private static JsonObject nodePayload(Long backendNodeId) {
            JsonObject node = new JsonObject();
            if (backendNodeId != null) node.addProperty("backendNodeId", backendNodeId);
            JsonObject payload = new JsonObject();
            payload.add("node", node);
            return payload;
        }

        private JsonObject contextPayload(int contextId) {
            JsonObject payload = new JsonObject();
            payload.addProperty("executionContextId", contextId);
            return payload;
        }

        private JsonObject frameTreeOf(String frameId) {
            JsonObject frame = new JsonObject();
            frame.addProperty("id", frameId);
            JsonObject tree = new JsonObject();
            tree.add("frame", frame);
            JsonArray children = new JsonArray();
            for (Map.Entry<String, String> e : parentByFrame.entrySet()) {
                if (frameId.equals(e.getValue())) children.add(frameTreeOf(e.getKey()));
            }
            if (!children.isEmpty()) tree.add("childFrames", children);
            return tree;
        }
    }
}

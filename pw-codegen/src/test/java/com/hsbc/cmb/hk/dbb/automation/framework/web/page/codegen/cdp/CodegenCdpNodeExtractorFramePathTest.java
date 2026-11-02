package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls.NlsReverseLookup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score.LocatorCounter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodegenCdpNodeExtractor} 的 CDP payload 取值契约 + 跨 frame 穿透路径（纯单测，不需要浏览器）。
 *
 * <p>锁定的核心事实：
 * <ul>
 *   <li>Playwright 的 {@code CDPSession.send} 返回 JSON-RPC 的 {@code result} <b>本体</b>
 *       （{@code Page.getFrameTree} → {@code {frameTree:…}}、{@code DOM.describeNode} → {@code {node:…}}、
 *       {@code DOM.getFrameOwner} → {@code {backendNodeId,nodeId}}）；按「外面还有一层 result」取值会全部取不到；</li>
 *   <li>穿透路径上行靠 {@code Page.getFrameTree} 的 {@code parentId}（不依赖只对 frame owner 填充的
 *       {@code DOM.Node.frameId}），因此<b>跨域 iframe</b>（由定位阶段给出 frameId）也能生成完整链路。</li>
 * </ul>
 */
public class CodegenCdpNodeExtractorFramePathTest {

    @Test
    public void mainFrameElementHasEmptyFramePath() {
        FakeCdp cdp = new FakeCdp();
        cdp.frameByNode.put(300L, "MAIN");

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNotNull(entry);
        assertEquals(List.of(), entry.framePath);
        assertFalse("主 frame 元素不应去查 frame owner", cdp.calls.contains("DOM.getFrameOwner"));
    }

    @Test
    public void nestedIframesResolvedOutermostFirst() {
        CodegenEntry entry = extractor(nested()).extract(300L);

        assertNotNull(entry);
        assertEquals(List.of("#outerFrame", "iframe[name=\"inner\"]"), entry.framePath);
    }

    @Test
    public void frameIdHintResolvesPathWithoutDescribeFrameId() {
        // 跨域 iframe 的真实形态：DOM.describeNode 不给普通元素 frameId，frameId 来自定位阶段。
        FakeCdp cdp = nested();
        cdp.frameByNode.clear();

        CodegenEntry entry = extractor(cdp).extract(300L, "F2");

        assertNotNull(entry);
        assertEquals(List.of("#outerFrame", "iframe[name=\"inner\"]"), entry.framePath);
    }

    @Test
    public void wrappedResultPayloadStillResolvesFrames() {
        FakeCdp cdp = nested();
        cdp.wrapPayloads = true;

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNotNull(entry);
        assertEquals(List.of("#outerFrame", "iframe[name=\"inner\"]"), entry.framePath);
    }

    @Test
    public void unknownFrameIsTreatedAsMainFrame() {
        // 既无 frameId 提示，describeNode 也没给 frameId：按「不在 iframe 内」处理，绝不伪造穿透路径。
        CodegenEntry entry = extractor(new FakeCdp()).extract(300L);

        assertNotNull(entry);
        assertEquals(List.of(), entry.framePath);
    }

    /**
     * 定位阶段已给出「元素在主 frame」⇒ 抽取阶段不得再读 {@code Page.getFrameTree}。
     *
     * <p>定位阶段本来就读过一次 frame 树；重复读一次纯属浪费（大页面上每次拾取都多一条往返）。
     */
    @Test
    public void mainFrameScopeSkipsFrameTreeLookup() {
        FakeCdp cdp = new FakeCdp();

        CodegenEntry entry = extractor(cdp).extract(300L, "MAIN", ElementRef.FrameScope.MAIN);

        assertNotNull(entry);
        assertEquals(List.of(), entry.framePath);
        assertFalse("主 frame 归属已知时不应再读 frame 树", cdp.calls.contains("Page.getFrameTree"));
    }

    /**
     * 子 frame 内元素必须<b>跳过页级 role 复核</b>：{@code page.getByRole} 只在主 frame 搜索，对它恒 0 命中，
     * 那一次大页计数（10~90ms）必然换回 CSS —— 属于纯粹的确定性浪费。
     */
    @Test
    public void subFrameScopeSkipsPageLevelRoleVerification() {
        FakeCdp cdp = nested();
        cdp.facts = json("{\"self\":{\"role\":\"button\",\"name\":\"Save\",\"tag\":\"button\","
                + "\"cssSelector\":\"#save\"},\"ancestors\":[]}");
        int[] roleCalls = {0};
        CodegenCdpNodeExtractor extractor = new CodegenCdpNodeExtractor(cdp,
                new NlsReverseLookup(new String[0]), new LocatorCounter() {
            @Override
            public int count(String cssSelector) {
                return 1;
            }

            @Override
            public int countByRole(String role, String name) {
                roleCalls[0]++;
                return 0;
            }
        });

        CodegenEntry entry = extractor.extract(300L, "F2", ElementRef.FrameScope.SUB);

        assertNotNull(entry);
        assertEquals("子 frame 元素仍要产出可运行的穿透路径",
                List.of("#outerFrame", "iframe[name=\"inner\"]"), entry.framePath);
        assertEquals("页级 role 复核必须被跳过", 0, roleCalls[0]);
        assertEquals("跳过复核 ⇒ 直接 CSS 策略（与复核后必然回退的结果一致）",
                LocatorStrategy.CSS, entry.strategy);
    }

    /** 归属未知（坐标反查路径）时保持历史语义：读 frame 树判定，并按结论决定是否复核。 */
    @Test
    public void unknownScopeResolvesFrameScopeFromTree() {
        FakeCdp cdp = nested();
        cdp.facts = json("{\"self\":{\"role\":\"button\",\"name\":\"Save\",\"tag\":\"button\","
                + "\"cssSelector\":\"#save\"},\"ancestors\":[]}");
        int[] roleCalls = {0};
        CodegenCdpNodeExtractor extractor = new CodegenCdpNodeExtractor(cdp,
                new NlsReverseLookup(new String[0]), new LocatorCounter() {
            @Override
            public int count(String cssSelector) {
                return 1;
            }

            @Override
            public int countByRole(String role, String name) {
                roleCalls[0]++;
                return 1;
            }
        });

        CodegenEntry entry = extractor.extract(300L, "MAIN", ElementRef.FrameScope.UNKNOWN);

        assertNotNull(entry);
        assertEquals("拾取阶段不做 role 复核（欠账留到生成前）", 0, roleCalls[0]);
        assertEquals(LocatorStrategy.ROLE, entry.strategy);
        assertTrue("主 frame 的 role 条目必须标记为待复核",
                extractor.requiresDeferredRoleVerification(entry));
        assertEquals(List.of(), entry.framePath);
    }

    /**
     * 唯一性计数必须绑定元素<b>自身 frame</b>：页级 {@code page.locator()} 只看主 frame，对 iframe 内元素
     * 恒 0 命中，而 0 被"视为唯一"⇒ 唯一性等于从未校验。
     */
    @Test
    public void uniquenessCountingIsScopedToElementFrame() {
        FakeCdp cdp = nested();
        String[] capturedFrame = {null};
        List<String> capturedHosts = new ArrayList<>();
        CodegenCdpNodeExtractor extractor = new CodegenCdpNodeExtractor(cdp,
                new NlsReverseLookup(new String[0]), new LocatorCounter() {
            @Override
            public int count(String cssSelector) {
                return 1;
            }

            @Override
            public LocatorCounter forFrame(String frameId, List<String> shadowHosts) {
                capturedFrame[0] = frameId;
                capturedHosts.addAll(shadowHosts);
                return this;
            }
        });

        extractor.extract(300L, "F2", ElementRef.FrameScope.SUB);

        assertEquals("计数作用域必须是元素所属 frame", "F2", capturedFrame[0]);
        assertEquals("主文档元素没有 shadow 宿主链", 0, capturedHosts.size());
    }

    @Test
    public void extractAssemblesFactsAndFramePath() {
        FakeCdp cdp = nested();
        cdp.facts = json("{\"self\":{\"role\":\"textbox\",\"name\":\"Username\",\"tag\":\"input\","
                + "\"id\":\"userName\",\"cssSelector\":\"#userName\",\"nth\":2},\"ancestors\":[]}");

        CodegenEntry entry = extractor(cdp).extract(300L, "F2");

        assertNotNull(entry);
        assertEquals("textbox", entry.role);
        assertEquals("Username", entry.name);
        assertEquals("#userName", entry.cssSelector);
        // 元素位于子 frame（F2）⇒ 跳过页级 role 复核，直接产出 CSS 策略：
        // page.getByRole 只在主 frame 搜索，对子 frame 元素恒 0 命中，产出的 @RoleElement 跑不通。
        assertEquals(LocatorStrategy.CSS, entry.strategy);
        assertTrue(entry.selector.startsWith("role=textbox[name=\"Username\"]"));
        assertEquals(2, entry.index);
        assertEquals(List.of("#outerFrame", "iframe[name=\"inner\"]"), entry.framePath);
    }

    /** 目标节点 300 位于 F2；F2 的宿主 iframe(200) 位于 F1；F1 的宿主 iframe(100) 位于主 frame。 */
    private static FakeCdp nested() {
        FakeCdp cdp = new FakeCdp();
        cdp.addFrame("F1", "MAIN");
        cdp.addFrame("F2", "F1");
        cdp.frameByNode.put(300L, "F2");
        cdp.frameByNode.put(200L, "F1");
        cdp.frameByNode.put(100L, "MAIN");
        cdp.ownerByFrame.put("F2", 200L);
        cdp.ownerByFrame.put("F1", 100L);
        cdp.selectorByOwner.put(200L, "iframe[name=\"inner\"]");
        cdp.selectorByOwner.put(100L, "#outerFrame");
        return cdp;
    }

    private static CodegenCdpNodeExtractor extractor(FakeCdp cdp) {
        return new CodegenCdpNodeExtractor(cdp, new NlsReverseLookup(new String[0]), sel -> 1);
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    /** 按 CDP 命令各自的 payload 形态应答的假会话（不建真实 CDP 会话、不开浏览器）。 */
    private static final class FakeCdp implements CdpTransport {

        /** frameId → 父 frameId（主 frame 无父）；用 LinkedHashMap 保证树结构稳定。 */
        final Map<String, String> parentByFrame = new LinkedHashMap<>();
        final Map<Long, String> frameByNode = new HashMap<>();
        final Map<String, Long> ownerByFrame = new HashMap<>();
        final Map<Long, String> selectorByOwner = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        JsonObject facts = json("{\"self\":{\"role\":\"generic\",\"name\":\"\",\"tag\":\"div\"},\"ancestors\":[]}");
        /** true 时把每个响应再包一层 {@code {"result": …}}，覆盖「个别版本多包一层」的容错分支。 */
        boolean wrapPayloads;

        FakeCdp() {
            parentByFrame.put("MAIN", null);
        }

        void addFrame(String frameId, String parentFrameId) {
            parentByFrame.put(frameId, parentFrameId);
        }

        @Override
        public JsonObject send(String method, Map<String, Object> params) {
            calls.add(method);
            JsonObject payload = payload(method, params);
            if (payload == null || !wrapPayloads) return payload;
            JsonObject wrapped = new JsonObject();
            wrapped.add("result", payload);
            return wrapped;
        }

        private JsonObject payload(String method, Map<String, Object> params) {
            switch (method) {
                case "Page.getFrameTree": {
                    JsonObject payload = new JsonObject();
                    payload.add("frameTree", frameTreeOf("MAIN"));
                    return payload;
                }
                case "DOM.describeNode": {
                    Long bn = asLong(params.get("backendNodeId"));
                    JsonObject node = new JsonObject();
                    node.addProperty("backendNodeId", bn == null ? Long.valueOf(0L) : bn);
                    String frameId = bn == null ? null : frameByNode.get(bn);
                    if (frameId != null) node.addProperty("frameId", frameId);
                    JsonObject payload = new JsonObject();
                    payload.add("node", node);
                    return payload;
                }
                case "DOM.getFrameOwner": {
                    Long owner = ownerByFrame.get(String.valueOf(params.get("frameId")));
                    if (owner == null) return null;
                    JsonObject payload = new JsonObject();
                    payload.addProperty("backendNodeId", owner);
                    return payload;
                }
                case "DOM.resolveNode": {
                    Long bn = asLong(params.get("backendNodeId"));
                    JsonObject object = new JsonObject();
                    object.addProperty("objectId", "obj-" + bn);
                    JsonObject payload = new JsonObject();
                    payload.add("object", object);
                    return payload;
                }
                case "Runtime.callFunctionOn": {
                    String objectId = String.valueOf(params.get("objectId"));
                    Long bn = objectId.startsWith("obj-") ? asLong(objectId.substring(4)) : null;
                    String selector = bn == null ? null : selectorByOwner.get(bn);
                    JsonObject remote = new JsonObject();
                    remote.addProperty("type", selector == null ? "object" : "string");
                    if (selector == null) remote.add("value", facts);
                    else remote.addProperty("value", selector);
                    JsonObject payload = new JsonObject();
                    payload.add("result", remote);
                    return payload;
                }
                default:
                    return null;
            }
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

        private static Long asLong(Object v) {
            if (v instanceof Number) return ((Number) v).longValue();
            if (v == null) return null;
            try {
                return Long.valueOf(String.valueOf(v));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
    }
}

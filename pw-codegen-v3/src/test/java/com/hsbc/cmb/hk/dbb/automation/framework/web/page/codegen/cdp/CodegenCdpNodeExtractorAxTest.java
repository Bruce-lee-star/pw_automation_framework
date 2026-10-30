package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls.NlsReverseLookup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score.LocatorCounter;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodegenCdpNodeExtractor} 的 AX（Accessibility 域）语义集成契约。
 *
 * <p>这是"为什么很多元素没走 {@code @RoleElement}"的修复核心：页面侧 {@code roleOf} 只是标签推断，
 * {@code <label>}/{@code <div>}/{@code <span>} 全落成 {@code label}/{@code generic} —— 前者在
 * {@code AriaRole} 里没有常量、后者无语义，都只能退化成又长又脆的结构 CSS。而浏览器 AX 树里的 role
 * （radio/checkbox/textbox/link…）才是 Playwright {@code getByRole} 真正匹配的东西。
 */
public class CodegenCdpNodeExtractorAxTest {

    /** 页面侧事实：点的是 label，推断出的 role 不可定位。 */
    private static final String JS_LABEL_FACTS = "{\"self\":{\"role\":\"label\",\"name\":\"Physical Security Device\","
            + "\"tag\":\"label\",\"cssSelector\":\"label:nth-of-type(2)\",\"nth\":1},\"ancestors\":[]}";

    @Test
    public void axRoleAndNameOverrideTagInference() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json(JS_LABEL_FACTS);
        cdp.axNode = axNode("radio", "Physical Security Device", false);

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNotNull(entry);
        assertEquals("AX 给出的才是 getByRole 能命中的角色", "radio", entry.role);
        assertEquals("Physical Security Device", entry.name);
        assertEquals(LocatorStrategy.ROLE, entry.strategy);
        assertEquals("role=radio[name=\"Physical Security Device\"]", entry.selector);
    }

    @Test
    public void ignoredAxNodeFallsBackToPageSideFacts() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json(JS_LABEL_FACTS);
        cdp.axNode = axNode("LabelText", "Physical Security Device", true);

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNotNull(entry);
        assertEquals("ignored 节点的 AX 语义不可信，必须回退页面侧推断", "label", entry.role);
        assertEquals("role 不可定位 ⇒ 回退 CSS，绝不降级成 AriaRole.GENERIC",
                LocatorStrategy.CSS, entry.strategy);
        assertEquals("label:nth-of-type(2)", entry.cssSelector);
    }

    @Test
    public void unavailableAxKeepsPageSideFacts() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json(JS_LABEL_FACTS);
        cdp.axNode = null;   // Accessibility 域不可用 / 命令失败

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNotNull("AX 不可用不能导致拾取失败", entry);
        assertEquals("label", entry.role);
        assertEquals(LocatorStrategy.CSS, entry.strategy);
    }

    @Test
    public void axFailureDoesNotAbortExtraction() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json(JS_LABEL_FACTS);
        cdp.throwOnAx = true;

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNotNull(entry);
        assertEquals("label", entry.role);
    }

    @Test
    public void nonLocatableAxRoleDoesNotDiscardLocatableInference() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json("{\"self\":{\"role\":\"button\",\"name\":\"Next\",\"tag\":\"button\","
                + "\"cssSelector\":\"button:nth-of-type(1)\"},\"ancestors\":[]}");
        // AX 报 generic（容器语义）但名字为空：不能据此把可定位的 button 推断丢掉
        cdp.axNode = axNode("generic", "", false);

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNotNull(entry);
        assertEquals("button", entry.role);
        assertEquals("Next", entry.name);
        assertEquals(LocatorStrategy.ROLE, entry.strategy);
    }

    /**
     * 0 命中：拾取阶段只给 role 候选（并标记欠账），复核结果由调用方在生成前取，
     * 非唯一时必须回退 CSS —— 否则产物能编译但跑不通。
     */
    @Test
    public void zeroHitRoleCandidateIsFlaggedForCssFallback() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json(JS_LABEL_FACTS);
        cdp.axNode = axNode("radio", "Physical Security Device", false);

        CodegenCdpNodeExtractor extractor = extractor(cdp, 0);
        CodegenEntry entry = extractor.extract(300L);

        assertNotNull(entry);
        assertEquals("role 事实保留（动作派生需要它）", "radio", entry.role);
        assertEquals("拾取阶段只给候选，不做同步复核", LocatorStrategy.ROLE, entry.strategy);
        assertTrue("必须标记为待生成前复核", extractor.requiresDeferredRoleVerification(entry));
        assertEquals("引擎 0 命中 ⇒ 调用方据此回退 CSS", 0, extractor.countRoleMatches(entry));
    }

    /** 多命中：运行期会 strict violation，复核结果为 >1 ⇒ 调用方回退 CSS。 */
    @Test
    public void ambiguousRoleCandidateIsFlaggedForCssFallback() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json(JS_LABEL_FACTS);
        cdp.axNode = axNode("radio", "Physical Security Device", false);

        CodegenCdpNodeExtractor extractor = extractor(cdp, 2);
        CodegenEntry entry = extractor.extract(300L);

        assertNotNull(entry);
        assertEquals(LocatorStrategy.ROLE, entry.strategy);
        assertEquals("角色定位命中多个 ⇒ 调用方回退 CSS", 2, extractor.countRoleMatches(entry));
    }

    @Test
    public void uniqueRoleCandidateKeepsRoleStrategy() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json(JS_LABEL_FACTS);
        cdp.axNode = axNode("radio", "Physical Security Device", false);

        CodegenCdpNodeExtractor extractor = extractor(cdp, 1);
        CodegenEntry entry = extractor.extract(300L);

        assertNotNull(entry);
        assertEquals(LocatorStrategy.ROLE, entry.strategy);
        assertEquals("唯一命中 ⇒ 保留 role 策略", 1, extractor.countRoleMatches(entry));
    }

    /**
     * 容器根（document / RootWebArea / &lt;html&gt; / &lt;body&gt;）不是真实交互目标：坐标拾取命中它们
     * 实为点了页面背景或弹窗遮罩，必须判为无效拾取（不生成条目，动作一并忽略）。
     */
    @Test
    public void containerRootDocumentIsRejected() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json("{\"self\":{\"role\":\"document\",\"name\":\"whole page text...\","
                + "\"tag\":\"html\"},\"ancestors\":[]}");
        cdp.axNode = null;   // AX 不可用 ⇒ 保留页面侧推断的 document 角色

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNull("背景/遮罩点击不应生成条目", entry);
    }

    @Test
    public void containerRootBodyTagIsRejected() {
        FakeAx cdp = new FakeAx();
        cdp.facts = json("{\"self\":{\"role\":\"generic\",\"name\":\"\",\"tag\":\"body\"},\"ancestors\":[]}");
        cdp.axNode = null;

        CodegenEntry entry = extractor(cdp).extract(300L);

        assertNull("点击 <body> 背景不应生成条目", entry);
    }

    // ------------------------------------------------------------------

    private static CodegenCdpNodeExtractor extractor(FakeAx cdp) {
        return extractor(cdp, 1);
    }

    /** @param roleHits 真实引擎（{@code getByRole(role, {name, exact:true}).count()}）的命中数 */
    private static CodegenCdpNodeExtractor extractor(FakeAx cdp, int roleHits) {
        return new CodegenCdpNodeExtractor(cdp, new NlsReverseLookup(new String[0]), new LocatorCounter() {
            @Override
            public int count(String cssSelector) {
                return 1;
            }

            @Override
            public int countByRole(String role, String name) {
                return roleHits;
            }
        });
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static JsonObject axNode(String role, String name, boolean ignored) {
        JsonObject node = new JsonObject();
        node.addProperty("ignored", ignored);
        if (role != null) {
            JsonObject r = new JsonObject();
            r.addProperty("type", "role");
            r.addProperty("value", role);
            node.add("role", r);
        }
        if (name != null) {
            JsonObject n = new JsonObject();
            n.addProperty("type", "computedString");
            n.addProperty("value", name);
            node.add("name", n);
        }
        return node;
    }

    /** 最小 CDP 假会话：只应答抽取链路用到的命令。 */
    private static final class FakeAx implements CdpTransport {

        JsonObject facts = json("{\"self\":{\"role\":\"generic\",\"name\":\"\",\"tag\":\"div\"},\"ancestors\":[]}");
        JsonObject axNode;
        boolean throwOnAx;

        @Override
        public JsonObject send(String method, Map<String, Object> params) {
            switch (method) {
                case "DOM.resolveNode": {
                    JsonObject object = new JsonObject();
                    object.addProperty("objectId", "obj-1");
                    JsonObject payload = new JsonObject();
                    payload.add("object", object);
                    return payload;
                }
                case "Runtime.callFunctionOn": {
                    JsonObject remote = new JsonObject();
                    remote.addProperty("type", "object");
                    remote.add("value", facts);
                    JsonObject payload = new JsonObject();
                    payload.add("result", remote);
                    return payload;
                }
                case "Accessibility.getPartialAXTree": {
                    if (throwOnAx) throw new IllegalStateException("AX unavailable");
                    if (axNode == null) return null;
                    JsonArray nodes = new JsonArray();
                    nodes.add(axNode);
                    JsonObject payload = new JsonObject();
                    payload.add("nodes", nodes);
                    return payload;
                }
                case "Page.getFrameTree": {
                    JsonObject frame = new JsonObject();
                    frame.addProperty("id", "MAIN");
                    JsonObject tree = new JsonObject();
                    tree.add("frame", frame);
                    JsonObject payload = new JsonObject();
                    payload.add("frameTree", tree);
                    return payload;
                }
                case "DOM.describeNode": {
                    JsonObject node = new JsonObject();
                    JsonObject payload = new JsonObject();
                    payload.add("node", node);
                    return payload;
                }
                default:
                    return null;
            }
        }
    }
}

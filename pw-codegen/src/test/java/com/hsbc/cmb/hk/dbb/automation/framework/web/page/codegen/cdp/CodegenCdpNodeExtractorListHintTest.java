package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldPlanner;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls.NlsReverseLookup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score.LocatorCounter;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 「目标本身是 ARIA 集合里的一项」⇒ 自动成组（{@link CodegenEntry#autoList}）。
 *
 * <p><b>现场</b>：HSBC 重置密码的密保问题下拉里，10 个 {@code li[role=option]} 的可访问名各不相同。
 * 旧的成组判据以 {@code role|name} 为基座，名字不同 ⇒ 永远成不了组，用户点第 5 个选项只得到
 * 「点中第 5 个选项」的单元素 {@code @Element}，既看不到整组也无法按题号取项。
 *
 * <p>本类固化三件事：注入脚本给出的 list 提示被如实落成 autoList（整组定位器 + 拾取项序号）、
 * 这类条目不再走 role 候选（{@code getByRole(role, name)} 命中的是单个选项，与整组语义冲突）、
 * 以及不可信的提示一律退回单元素（组语义错误比"没有组"更糟）。
 */
public class CodegenCdpNodeExtractorListHintTest {

    private static final String GROUP_CSS = "ul[aria-labelledby=\"label_sq1\"] > li[role=\"option\"]";
    private static final String ITEM_CSS = "#security-question-1_option_4";
    private static final String ITEM_NAME = "Name a memorable city or country";

    /** 可信提示：整组 CSS + 拾取项序号，并且强制走 CSS（role 只命中单个选项）。 */
    @Test
    public void usableListHintBecomesAutoListWithGroupSelectorAndPickedIndex() {
        FakeCdp cdp = new FakeCdp(optionFacts(10, 4));
        CodegenCdpNodeExtractor extractor = extractor(cdp);

        CodegenEntry entry = extractor.extract(300L, "MAIN", ElementRef.FrameScope.MAIN);

        assertNotNull(entry);
        assertTrue("必须标记为自动成组", entry.autoList);
        assertEquals("整组定位器（命中 10 个选项）", GROUP_CSS, entry.cssSelector);
        assertEquals("序号 = 用户实际点的那一项（第 5 个 ⇒ 4），不是组内成员号", 4, entry.index);
        assertEquals("命中数 = 整组成员数，生成侧据此判 PageElementList", 10, entry.matchCount);
        assertEquals("整组 CSS 才是产物的定位依据", LocatorStrategy.CSS, entry.strategy);
        assertTrue("sigKey 的序号必须与组内序号一致（面板去重/分组同源）: " + entry.sigKey,
                entry.sigKey.startsWith("option|") && entry.sigKey.endsWith("#4"));
        assertTrue("面板载荷需带出该标记，便于排障", Boolean.TRUE.equals(entry.toMap().get("autoList")));
    }

    /** 自动成组的条目不得再欠 role 复核：getByRole(role, name) 命中的是「某一个选项」，与整组语义冲突。 */
    @Test
    public void autoListEntryNeverDefersRoleVerification() {
        CodegenCdpNodeExtractor extractor = extractor(new FakeCdp(optionFacts(10, 4)));

        CodegenEntry entry = extractor.extract(300L, "MAIN", ElementRef.FrameScope.MAIN);

        assertNotNull(entry);
        assertFalse(extractor.requiresDeferredRoleVerification(entry));
        assertEquals(CodegenEntry.ROLE_HITS_NOT_APPLICABLE, entry.roleHits);
    }

    /** 提示不可信（成员数 < 2）⇒ 退回单元素，行为与没有提示时完全一致。 */
    @Test
    public void untrustworthyListHintFallsBackToSingleElement() {
        CodegenEntry entry = extractor(new FakeCdp(optionFacts(1, 0)))
                .extract(300L, "MAIN", ElementRef.FrameScope.MAIN);

        assertNotNull(entry);
        assertFalse("只有 1 个成员不构成一组", entry.autoList);
        assertTrue("退回单个选项自身的 CSS: " + entry.cssSelector, entry.cssSelector.contains("option_4"));
        assertEquals("仍是 role 候选（待生成前复核）", LocatorStrategy.ROLE, entry.strategy);
        assertEquals(CodegenEntry.ROLE_HITS_UNVERIFIED, entry.roleHits);
    }

    /** 序号越界（index >= count）同样不可信：宁可没有组，也不能生成会点错项的 get(n)。 */
    @Test
    public void outOfRangeIndexFallsBackToSingleElement() {
        CodegenEntry entry = extractor(new FakeCdp(optionFacts(10, 10)))
                .extract(300L, "MAIN", ElementRef.FrameScope.MAIN);

        assertNotNull(entry);
        assertFalse(entry.autoList);
        assertTrue(entry.cssSelector.contains("option_4"));
    }

    /** 端到端：拾取到的自动成组条目，经字段规划后 Step 动作必须指回用户点的那一项。 */
    @Test
    public void autoListEntryGeneratesIndexedActionInStep() {
        CodegenEntry entry = extractor(new FakeCdp(optionFacts(10, 4)))
                .extract(300L, "MAIN", ElementRef.FrameScope.MAIN);
        assertNotNull(entry);

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(entry), new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        assertEquals("PageElementList", f.fieldType);
        assertTrue(f.annotation.contains("label_sq1"));
        assertEquals("必须点回第 5 个选项，而不是 get(0)", "get(4).click()", f.action);
        assertEquals("get(4).isVisible()", f.assertion);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static CodegenCdpNodeExtractor extractor(CdpTransport cdp) {
        return new CodegenCdpNodeExtractor(cdp, new NlsReverseLookup(new String[0]), countingCounter());
    }

    /** CSS 计数恒 1（单个选项确实是唯一的），role 计数 1；自动成组的判定不依赖这两个数。 */
    private static LocatorCounter countingCounter() {
        return new LocatorCounter() {
            @Override
            public int count(String cssSelector) {
                return 1;
            }

            @Override
            public int countByRole(String role, String name) {
                return 1;
            }
        };
    }

    /** 注入脚本 getInfo 的载荷：单个 option 的事实 + 集合项提示。 */
    private static JsonObject optionFacts(int count, int index) {
        JsonObject self = new JsonObject();
        self.addProperty("role", "option");
        self.addProperty("name", ITEM_NAME);
        self.addProperty("tag", "li");
        self.addProperty("cssSelector", ITEM_CSS);

        JsonObject list = new JsonObject();
        list.addProperty("selector", GROUP_CSS);
        list.addProperty("count", count);
        list.addProperty("index", index);

        JsonObject root = new JsonObject();
        root.add("self", self);
        root.add("ancestors", new JsonArray());
        root.add("list", list);
        return root;
    }

    /** 只应答抽取主流程所需的命令；frame 树 / AX 一律返回 null（走"主 frame / 无穿透 / 页面事实"分支）。 */
    private static final class FakeCdp implements CdpTransport {

        private final JsonObject facts;

        FakeCdp(JsonObject facts) {
            this.facts = facts;
        }

        @Override
        public JsonObject send(String method, Map<String, Object> params) {
            switch (method) {
                case "DOM.resolveNode": {
                    JsonObject object = new JsonObject();
                    object.addProperty("objectId", "obj-300");
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
                default:
                    return null;
            }
        }
    }
}

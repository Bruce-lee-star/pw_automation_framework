package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls.NlsReverseLookup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score.LocatorCounter;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * role 复核「拾取不做、生成前补」的契约。
 *
 * <p>背景：{@code page.getByRole(...).count()} 是"生成的 @RoleElement 真能命中"的唯一可信校验，
 * 但在按钮/链接密集的大页面上单次要 10~90ms（4k 元素实测 93ms）。故拾取阶段只给 AX 语义的候选，
 * 由调用方在任何产物输出前用 {@link CodegenCdpNodeExtractor#countRoleMatches(CodegenEntry)} 补做，
 * <b>行为固定、无开关</b>：既没有"同步复核"分支，也没有配置项可切。
 */
public class CodegenCdpNodeExtractorRoleDeferralTest {

    private static final String BUTTON_FACTS = "{\"self\":{\"role\":\"button\",\"name\":\"Save\","
            + "\"tag\":\"button\",\"cssSelector\":\"#save\"},\"ancestors\":[]}";

    /** 拾取阶段零复核（不付那 10~90ms），但必须标记为"待生成前复核"。 */
    @Test
    public void pickPhaseNeverVerifiesRoleButFlagsPending() {
        FakeCdp cdp = new FakeCdp();
        int[] roleCalls = {0};
        CodegenCdpNodeExtractor extractor = extractor(cdp, counter(roleCalls, 1));

        CodegenEntry entry = extractor.extract(300L, "MAIN", ElementRef.FrameScope.MAIN);

        assertNotNull(entry);
        assertEquals("拾取路径不得同步复核 role", 0, roleCalls[0]);
        assertEquals("策略仍按 AX 语义给出 role（待复核）", LocatorStrategy.ROLE, entry.strategy);
        assertTrue("必须标记为生成前待复核，否则产物可能跑不通",
                extractor.requiresDeferredRoleVerification(entry));
    }

    /** 子 frame 元素直接走穿透 CSS：页级 getByRole 对它们恒 0 命中，role 无从校验，也不产生欠账。 */
    @Test
    public void subFrameElementNeverGetsRoleStrategy() {
        FakeCdp cdp = new FakeCdp();
        int[] roleCalls = {0};
        CodegenCdpNodeExtractor extractor = extractor(cdp, counter(roleCalls, 1));

        CodegenEntry entry = extractor.extract(300L, "F1", ElementRef.FrameScope.SUB);

        assertNotNull(entry);
        assertEquals(0, roleCalls[0]);
        assertEquals(LocatorStrategy.CSS, entry.strategy);
        assertFalse(extractor.requiresDeferredRoleVerification(entry));
    }

    /** 复核命中数：1=唯一；0/多命中=必须回退 CSS；负数=不可判定。 */
    @Test
    public void countRoleMatchesReportsHits() {
        CodegenEntry entry = new CodegenEntry("button|Save#0", "button", "Save", LocatorStrategy.ROLE, 105,
                "role=button[name=\"Save\"]", "#save", null, 0);

        assertEquals(1, extractor(new FakeCdp(), counter(new int[]{0}, 1)).countRoleMatches(entry));
        assertEquals(0, extractor(new FakeCdp(), counter(new int[]{0}, 0)).countRoleMatches(entry));
        assertEquals(3, extractor(new FakeCdp(), counter(new int[]{0}, 3)).countRoleMatches(entry));
        assertEquals("不可判定必须显式返回 -1（调用方据此放行）",
                -1, extractor(new FakeCdp(), counter(new int[]{0}, -1)).countRoleMatches(entry));
        assertEquals("缺名字无法复核 ⇒ 不可判定", -1, extractor(new FakeCdp(), counter(new int[]{0}, 1))
                .countRoleMatches("button", ""));
        assertEquals("null 条目 ⇒ 不可判定", -1,
                extractor(new FakeCdp(), counter(new int[]{0}, 1)).countRoleMatches((CodegenEntry) null));
    }

    /** 计数本身抛异常 ⇒ 不可判定（环境抖动不该把可靠产物退化成结构 CSS）。 */
    @Test
    public void countRoleMatchesTreatsFailureAsUndeterminable() {
        LocatorCounter failing = new LocatorCounter() {
            @Override
            public int count(String cssSelector) {
                return 1;
            }

            @Override
            public int countByRole(String role, String name) {
                throw new IllegalStateException("page closed");
            }
        };

        assertEquals(-1, extractor(new FakeCdp(), failing).countRoleMatches("button", "Save"));
    }

    /** 定位阶段带出的 objectId 必须直传：省掉一次 DOM.resolveNode。 */
    @Test
    public void locatorObjectIdSkipsResolveNode() {
        FakeCdp cdp = new FakeCdp();
        ElementRef ref = new ElementRef(300L, "MAIN", ElementRef.FrameScope.MAIN, "obj-300");

        CodegenEntry entry = extractor(cdp, counter(new int[]{0}, 1)).extract(ref);

        assertNotNull(entry);
        assertEquals("objectId 直传不得再 resolveNode", 0, cdp.resolveNodeCalls);
        assertEquals("应直接对定位阶段的对象求值", 1, cdp.callFunctionOnCalls);
    }

    /** objectId 已失效（导航/上下文销毁）⇒ 自动回退 DOM.resolveNode，绝不让拾取失败。 */
    @Test
    public void staleObjectIdFallsBackToResolveNode() {
        FakeCdp cdp = new FakeCdp();
        cdp.staleObjectIds = Set.of("obj-stale");
        ElementRef ref = new ElementRef(300L, "MAIN", ElementRef.FrameScope.MAIN, "obj-stale");

        CodegenEntry entry = extractor(cdp, counter(new int[]{0}, 1)).extract(ref);

        assertNotNull("失效的加速提示不得让拾取失败", entry);
        assertEquals("必须回退一次 resolveNode", 1, cdp.resolveNodeCalls);
        assertEquals("回退后应重新求值", 2, cdp.callFunctionOnCalls);
    }

    private static CodegenCdpNodeExtractor extractor(CdpTransport cdp, LocatorCounter counter) {
        return new CodegenCdpNodeExtractor(cdp, new NlsReverseLookup(new String[0]), counter);
    }

    private static LocatorCounter counter(int[] roleCalls, int roleHits) {
        return new LocatorCounter() {
            @Override
            public int count(String cssSelector) {
                return 1;
            }

            @Override
            public int countByRole(String role, String name) {
                roleCalls[0]++;
                return roleHits;
            }
        };
    }

    /** 只应答抽取主流程所需的命令；frame 树/AX 一律返回 null（走"主 frame / 无穿透 / 页面事实"分支）。 */
    private static final class FakeCdp implements CdpTransport {

        int resolveNodeCalls;
        int callFunctionOnCalls;
        Set<String> staleObjectIds = Set.of();

        @Override
        public JsonObject send(String method, Map<String, Object> params) {
            switch (method) {
                case "DOM.resolveNode": {
                    resolveNodeCalls++;
                    JsonObject object = new JsonObject();
                    object.addProperty("objectId", "obj-300");
                    JsonObject payload = new JsonObject();
                    payload.add("object", object);
                    return payload;
                }
                case "Runtime.callFunctionOn": {
                    callFunctionOnCalls++;
                    Object objectId = params.get("objectId");
                    JsonObject payload = new JsonObject();
                    if (objectId != null && staleObjectIds.contains(String.valueOf(objectId))) {
                        // 句柄已被回收：无 value ⇒ 抽取侧据此回退 DOM.resolveNode
                        payload.add("result", new JsonObject());
                        return payload;
                    }
                    JsonObject remote = new JsonObject();
                    remote.addProperty("type", "object");
                    remote.add("value", JsonParser.parseString(BUTTON_FACTS).getAsJsonObject());
                    payload.add("result", remote);
                    return payload;
                }
                default:
                    return null;
            }
        }
    }
}

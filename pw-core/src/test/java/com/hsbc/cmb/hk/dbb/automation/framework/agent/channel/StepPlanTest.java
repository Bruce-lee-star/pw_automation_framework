package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StepPlanTest {

    private static final String VALID = "{"
            + "\"schemaVersion\":1,"
            + "\"planId\":\"plan-demo\","
            + "\"steps\":["
            + "  {\"action\":\"NAVIGATE\",\"params\":{\"url\":\"https://example.com/login\"}},"
            + "  {\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"},"
            + "  {\"action\":\"FILL\",\"target\":\"role=textbox;name=username\",\"params\":{\"value\":\"alice\"}},"
            + "  {\"action\":\"ASSERT_VISIBLE\",\"target\":\"role=button;name=Submit\"}"
            + "]}";

    @Test
    public void parsesValidPlan() {
        StepPlan plan = StepPlan.parse(VALID);
        assertEquals(1, plan.schemaVersion());
        assertEquals("plan-demo", plan.planId());
        assertEquals(4, plan.steps().size());
        assertEquals(Command.ActionType.NAVIGATE, plan.steps().get(0).action());
        assertEquals("role=button;name=Submit", plan.steps().get(1).targetRef());
        assertEquals("alice", plan.steps().get(2).params().get("value"));
    }

    @Test
    public void rejectsUnknownRootField() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\",\"steps\":[],\"note\":\"evil\"}"));
    }

    @Test
    public void rejectsUnknownStepField() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\",\"foo\":\"bar\"}]}"));
    }

    @Test
    public void rejectsUnknownActionType() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"EXEC\",\"target\":\"role=button;name=Submit\"}]}"));
    }

    @Test
    public void rejectsNavigateWithTarget() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"NAVIGATE\",\"target\":\"role=button;name=Submit\"}]}"));
    }

    @Test
    public void rejectsClickWithoutTarget() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\",\"steps\":[{\"action\":\"CLICK\"}]}"));
    }

    @Test
    public void rejectsAssertWithWriteParams() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"ASSERT_VISIBLE\",\"target\":\"role=button;name=Submit\","
                + "\"params\":{\"value\":\"x\"}}]}"));
    }

    @Test
    public void planDigestStableAndSensitive() {
        String d1 = StepPlan.parse(VALID).planDigest();
        String d2 = StepPlan.parse(VALID).planDigest();
        assertEquals(d1, d2);
        assertNotNull(d1);
        assertEquals(16, d1.length());

        String tampered = VALID.replace("\"alice\"", "\"bob\"");
        String d3 = StepPlan.parse(tampered).planDigest();
        assertNotEquals(d1, d3);
    }

    private static final String WITH_SCOPE = "{"
            + "\"schemaVersion\":1,"
            + "\"planId\":\"plan-scoped\","
            + "\"scope\":{\"value\":{\"allow\":[\"alice\",\"bob\"]},\"option\":{\"regex\":\"^[0-9]+$\"}},"
            + "\"steps\":["
            + "  {\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"},"
            + "  {\"action\":\"FILL\",\"target\":\"role=textbox;name=username\",\"params\":{\"value\":\"alice\"}},"
            + "  {\"action\":\"SELECT\",\"target\":\"role=combobox;name=role\",\"params\":{\"option\":\"42\"}}"
            + "]}";

    private static final String WITH_STEP_SCOPE = "{"
            + "\"schemaVersion\":1,"
            + "\"planId\":\"plan-step-scoped\","
            + "\"scope\":{\"value\":{\"allow\":[\"plan-only\"]}},"
            + "\"steps\":["
            + "  {\"action\":\"FILL\",\"target\":\"role=textbox;name=username\",\"params\":{\"value\":\"x\"}},"
            + "  {\"action\":\"FILL\",\"target\":\"role=textbox;name=password\",\"params\":{\"value\":\"y\"},"
            + "\"scope\":{\"value\":{\"allow\":[\"step-only\"]}}}"
            + "]}";

    @Test
    public void parsesPlanWithScope() {
        StepPlan plan = StepPlan.parse(WITH_SCOPE);
        assertFalse(plan.scope().isEmpty());
        assertTrue(plan.scope().constraintFor("value").orElseThrow().allows("alice"));
        assertTrue(plan.scope().constraintFor("option").orElseThrow().allows("42"));
        assertFalse(plan.scope().constraintFor("option").orElseThrow().allows("abc"));
    }

    @Test
    public void scopeChangesDigest() {
        String withScope = VALID.replace("\"steps\"",
                "\"scope\":{\"value\":{\"allow\":[\"alice\"]}},\"steps\"");
        String d1 = StepPlan.parse(VALID).planDigest();
        String d2 = StepPlan.parse(withScope).planDigest();
        assertNotEquals("scope 必须改变指纹", d1, d2);
        assertEquals("同一带 scope 计划指纹稳定", d2, StepPlan.parse(withScope).planDigest());
    }

    @Test
    public void illegalScopeConstraintRejected() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"scope\":{\"value\":{\"allow\":[\"a\"],\"regex\":\"b\"}},"
                + "\"steps\":[{\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"}]}"));
    }

    @Test
    public void scopeDeclaredButUnknownFieldStillRejected() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\",\"scope\":{},\"foo\":\"bar\","
                + "\"steps\":[{\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"}]}"));
    }

    @Test
    public void parsesPlanWithStepScope() {
        StepPlan plan = StepPlan.parse(WITH_STEP_SCOPE);
        // plan 级 scope
        assertTrue(plan.scope().constraintFor("value").orElseThrow().allows("plan-only"));
        // step 0 无 step scope → resolved = plan scope
        assertTrue(plan.resolvedScope(0).constraintFor("value").orElseThrow().allows("plan-only"));
        assertFalse(plan.resolvedScope(0).constraintFor("value").orElseThrow().allows("step-only"));
        // step 1 有 step scope → resolved = step scope（覆盖 plan）
        assertTrue(plan.resolvedScope(1).constraintFor("value").orElseThrow().allows("step-only"));
        assertFalse(plan.resolvedScope(1).constraintFor("value").orElseThrow().allows("plan-only"));
    }

    @Test
    public void stepScopeChangesDigest() {
        String noStepScope = "{"
                + "\"schemaVersion\":1,\"planId\":\"d\","
                + "\"steps\":["
                + "  {\"action\":\"FILL\",\"target\":\"role=textbox;name=u\",\"params\":{\"value\":\"a\"}}"
                + "]}";
        String withStepScope = "{"
                + "\"schemaVersion\":1,\"planId\":\"d\","
                + "\"steps\":["
                + "  {\"action\":\"FILL\",\"target\":\"role=textbox;name=u\",\"params\":{\"value\":\"a\"},"
                + "\"scope\":{\"value\":{\"allow\":[\"a\"]}}}"
                + "]}";
        String d1 = StepPlan.parse(noStepScope).planDigest();
        String d2 = StepPlan.parse(withStepScope).planDigest();
        assertNotEquals("per-step scope 必须改变指纹", d1, d2);
        assertEquals("同一带 step scope 计划指纹稳定", d2, StepPlan.parse(withStepScope).planDigest());
    }

    // ---- 按 ActionType 的参数键 schema（§4.3 缺口补齐，v3.20） ----

    @Test
    public void rejectsUnknownParamKeyForAction() {
        // 模型/外部输入最常见的臆造：给 CLICK 塞 value
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\","
                + "\"params\":{\"value\":\"x\"}}]}"));
    }

    @Test
    public void rejectsMissingRequiredParam() {
        // NAVIGATE 缺 url
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\",\"steps\":[{\"action\":\"NAVIGATE\"}]}"));
        // FILL 缺 value
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"FILL\",\"target\":\"role=textbox;name=u\"}]}"));
        // SELECT 缺 option
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"SELECT\",\"target\":\"role=combobox;name=role\"}]}"));
    }

    @Test
    public void rejectsBlankRequiredParam() {
        assertThrowsIllegal(() -> StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"FILL\",\"target\":\"role=textbox;name=u\","
                + "\"params\":{\"value\":\"  \"}}]}"));
    }

    @Test
    public void acceptsOmittedOptionalParam() {
        // WAIT 的 ms 是可选参数：省略仍合法
        StepPlan plan = StepPlan.parse("{\"schemaVersion\":1,\"planId\":\"x\","
                + "\"steps\":[{\"action\":\"WAIT\",\"target\":\"role=button;name=Submit\"}]}");
        assertEquals(1, plan.steps().size());
        assertTrue(plan.steps().get(0).params().isEmpty());
    }

    private static void assertThrowsIllegal(ThrowingRunnable r) {
        try {
            r.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 期望的 fail-closed 拒绝
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}

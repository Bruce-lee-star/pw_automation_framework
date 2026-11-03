package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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

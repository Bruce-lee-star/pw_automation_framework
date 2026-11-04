package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 锁定逐动作策略元数据（v3.10，浏览器无关，9 个 ActionType 全覆盖）。
 */
public class ActionTypePolicyTest {

    @Test
    public void allNineActionsAreMapped() {
        for (Command.ActionType a : Command.ActionType.values()) {
            assertNotNull("policy missing for " + a, ActionTypePolicy.of(a));
        }
    }

    @Test
    public void sideEffectingMatchesThreatModeledPrimitives() {
        assertTrue(ActionTypePolicy.of(Command.ActionType.NAVIGATE).sideEffecting());
        assertTrue(ActionTypePolicy.of(Command.ActionType.FILL).sideEffecting());
        assertTrue(ActionTypePolicy.of(Command.ActionType.SELECT).sideEffecting());
        assertTrue(ActionTypePolicy.of(Command.ActionType.CHECK).sideEffecting());
        assertTrue(ActionTypePolicy.of(Command.ActionType.UNCHECK).sideEffecting());
        assertFalse(ActionTypePolicy.of(Command.ActionType.CLICK).sideEffecting());
        assertFalse(ActionTypePolicy.of(Command.ActionType.WAIT).sideEffecting());
        assertFalse(ActionTypePolicy.of(Command.ActionType.ASSERT_VISIBLE).sideEffecting());
        assertFalse(ActionTypePolicy.of(Command.ActionType.ASSERT_TEXT).sideEffecting());
    }

    @Test
    public void writeActionsAreNotIdempotentAndNeverRetried() {
        assertFalse(ActionTypePolicy.of(Command.ActionType.CLICK).idempotent());
        assertFalse(ActionTypePolicy.of(Command.ActionType.FILL).idempotent());
        assertFalse(ActionTypePolicy.of(Command.ActionType.SELECT).idempotent());
        assertEquals(0, ActionTypePolicy.of(Command.ActionType.CLICK).maxRetries());
        assertEquals(0, ActionTypePolicy.of(Command.ActionType.FILL).maxRetries());
        assertEquals(0, ActionTypePolicy.of(Command.ActionType.SELECT).maxRetries());
    }

    @Test
    public void readAndToggleActionsAreIdempotentWithLimitedRetry() {
        assertTrue(ActionTypePolicy.of(Command.ActionType.WAIT).idempotent());
        assertTrue(ActionTypePolicy.of(Command.ActionType.CHECK).idempotent());
        assertTrue(ActionTypePolicy.of(Command.ActionType.NAVIGATE).idempotent());
        assertEquals(1, ActionTypePolicy.of(Command.ActionType.WAIT).maxRetries());
        assertEquals(1, ActionTypePolicy.of(Command.ActionType.CHECK).maxRetries());
        assertEquals(1, ActionTypePolicy.of(Command.ActionType.NAVIGATE).maxRetries());
    }

    @Test
    public void defaultTimeoutsArePositive() {
        for (Command.ActionType a : Command.ActionType.values()) {
            Duration t = ActionTypePolicy.of(a).defaultTimeout();
            assertTrue(a + " timeout must be positive", t != null && t.toMillis() > 0);
        }
    }
}

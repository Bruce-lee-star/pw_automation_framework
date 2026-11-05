package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 动作授权 + 按 ActionType 参数 schema 测试（落地 §11.1 两处明列缺口）。
 */
public class IntentActionSchemaTest {

    @Test
    public void switchToPopupIsNotAuthorized() {
        assertFalse("框架内部 popup 切换原语不得由录制/模型输入注入",
                IntentActionSchema.isAuthorized(Command.ActionType.SWITCH_TO_POPUP));
        assertNull(IntentActionSchema.specOf(Command.ActionType.SWITCH_TO_POPUP));
    }

    @Test
    public void allOtherActionTypesAreAuthorized() {
        for (Command.ActionType a : Command.ActionType.values()) {
            if (a == Command.ActionType.SWITCH_TO_POPUP) {
                continue;
            }
            assertTrue("missing authorization for " + a, IntentActionSchema.isAuthorized(a));
            assertNotNull("missing schema for " + a, IntentActionSchema.specOf(a));
        }
        assertEquals(Command.ActionType.values().length - 1,
                IntentActionSchema.authorizedActions().size());
    }

    @Test
    public void authorizedSetIsImmutable() {
        Set<Command.ActionType> set = IntentActionSchema.authorizedActions();
        try {
            set.clear();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void navigateRequiresUrlAndNoTarget() {
        assertReason(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                () -> IntentActionSchema.validate(Command.ActionType.NAVIGATE, null, Map.of()));
        assertReason(IntentTranslationException.Reason.TARGET_VIOLATION,
                () -> IntentActionSchema.validate(Command.ActionType.NAVIGATE,
                        "role=button;name=x", Map.of("url", "https://example.com")));
        IntentActionSchema.validate(Command.ActionType.NAVIGATE, null,
                Map.of("url", "https://example.com"));
    }

    @Test
    public void rejectsUnknownParamKey() {
        assertReason(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                () -> IntentActionSchema.validate(Command.ActionType.FILL,
                        "role=textbox;name=u", Map.of("value", "alice", "executeJs", "alert(1)")));
    }

    @Test
    public void rejectsDisallowedAction() {
        assertReason(IntentTranslationException.Reason.ACTION_NOT_ALLOWED,
                () -> IntentActionSchema.validate(Command.ActionType.SWITCH_TO_POPUP, null, Map.of()));
        assertReason(IntentTranslationException.Reason.ACTION_NOT_ALLOWED,
                () -> IntentActionSchema.validate(null, null, Map.of()));
    }

    @Test
    public void elementActionsRequireTarget() {
        assertReason(IntentTranslationException.Reason.TARGET_VIOLATION,
                () -> IntentActionSchema.validate(Command.ActionType.CLICK, null, Map.of()));
        IntentActionSchema.validate(Command.ActionType.CLICK, "role=button;name=ok", Map.of());
    }

    @Test
    public void assertActionsRejectWriteParams() {
        assertReason(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                () -> IntentActionSchema.validate(Command.ActionType.ASSERT_VISIBLE,
                        "role=heading;name=x", Map.of("value", "injected")));
    }

    @Test
    public void waitAllowsOnlyMs() {
        IntentActionSchema.validate(Command.ActionType.WAIT, "role=status;name=loading", Map.of("ms", "500"));
        assertReason(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                () -> IntentActionSchema.validate(Command.ActionType.WAIT,
                        "role=status;name=loading", Map.of("seconds", "5")));
    }

    private static void assertReason(IntentTranslationException.Reason expected, Runnable action) {
        try {
            action.run();
            fail("expected IntentTranslationException with reason " + expected);
        } catch (IntentTranslationException e) {
            assertEquals(expected, e.reason());
        }
    }
}

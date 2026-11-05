package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 统一计划门禁测试（R6：任何来源的建议都过同一 schema/策略/可执行性校验）。
 */
public class IntentPlanValidatorTest {

    private static final String PAGE = "https://example.com/login";

    private static StepPlan validPlan() {
        Command nav = Command.navigate(PAGE);
        Command fill = Command.fill(LocatorSpec.parse("role=textbox;name=u"), "alice");
        ParamScope planScope = new ParamScope(Map.of("url", ParamConstraint.allowedValues(PAGE)));
        return new StepPlan(1, "intent-1", List.of(nav, fill), planScope,
                List.of(ParamScope.EMPTY, ParamScope.forAssetWriteValue(Command.ActionType.FILL, "alice")));
    }

    @Test
    public void acceptsValidPlan() {
        IntentPlanValidator.Report report = IntentPlanValidator.inspect(validPlan());
        assertTrue("violations=" + report.violations(), report.accepted());
        assertTrue(report.violations().isEmpty());
        IntentPlanValidator.validate(validPlan());
    }

    @Test
    public void rejectsNullPlan() {
        IntentPlanValidator.Report report = IntentPlanValidator.inspect(null);
        assertFalse(report.accepted());
        assertEquals(IntentTranslationException.Reason.INVALID_INPUT,
                report.reasons().iterator().next());
    }

    @Test
    public void rejectsEmptySteps() {
        StepPlan empty = new StepPlan(1, "p", List.of(), ParamScope.EMPTY, List.of());
        IntentPlanValidator.Report report = IntentPlanValidator.inspect(empty);
        assertFalse(report.accepted());
        assertTrue(report.reasons().contains(IntentTranslationException.Reason.BOUNDS_VIOLATION));
    }

    @Test
    public void rejectsUnsupportedSchemaVersion() {
        StepPlan p = new StepPlan(99, "p",
                List.of(Command.navigate(PAGE)),
                new ParamScope(Map.of("url", ParamConstraint.allowedValues(PAGE))),
                List.of(ParamScope.EMPTY));
        assertTrue(IntentPlanValidator.inspect(p).reasons()
                .contains(IntentTranslationException.Reason.UNSUPPORTED_SCHEMA));
    }

    @Test
    public void rejectsUnauthorizedAction() {
        StepPlan p = new StepPlan(1, "p", List.of(Command.switchToPopup()),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        IntentPlanValidator.Report report = IntentPlanValidator.inspect(p);
        assertFalse(report.accepted());
        assertTrue(report.reasons().contains(IntentTranslationException.Reason.ACTION_NOT_ALLOWED));
    }

    @Test
    public void rejectsUndeclaredSideEffectParam() {
        // FILL 的 value 没有声明 step 级取值域 → ParamScopePolicy 拒绝
        StepPlan p = new StepPlan(1, "p",
                List.of(Command.fill(LocatorSpec.parse("role=textbox;name=u"), "alice")),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        IntentPlanValidator.Report report = IntentPlanValidator.inspect(p);
        assertFalse(report.accepted());
        assertTrue("reasons=" + report.reasons(),
                report.reasons().contains(IntentTranslationException.Reason.POLICY_VIOLATION));
    }

    @Test
    public void rejectsPlanWithMisalignedStepScopes() {
        // stepScopes 少于 steps → planDigest 不可计算（非可执行计划）
        StepPlan p = new StepPlan(1, "p",
                List.of(Command.click(LocatorSpec.parse("role=button;name=ok")),
                        Command.click(LocatorSpec.parse("role=button;name=cancel"))),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        IntentPlanValidator.Report report = IntentPlanValidator.inspect(p);
        assertFalse(report.accepted());
        assertTrue(report.reasons().contains(IntentTranslationException.Reason.PLAN_NOT_EXECUTABLE));
    }

    @Test
    public void validateThrowsOnRejectedPlan() {
        StepPlan empty = new StepPlan(1, "p", List.of(), ParamScope.EMPTY, List.of());
        try {
            IntentPlanValidator.validate(empty);
            fail("expected IntentTranslationException");
        } catch (IntentTranslationException e) {
            assertTrue(e.getMessage().contains("rejected"));
        }
    }
}

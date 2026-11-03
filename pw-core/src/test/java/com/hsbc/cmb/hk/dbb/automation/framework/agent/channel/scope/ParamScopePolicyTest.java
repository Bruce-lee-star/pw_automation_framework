package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.fail;

public class ParamScopePolicyTest {

    private static final LocatorSpec USER = LocatorSpec.parse("role=textbox;name=username");

    @Test
    public void compliantPlanPasses() {
        // FILL.value / SELECT.option 均在 scope 声明 → 合规
        StepPlan plan = new StepPlan(1, "p", List.of(
                Command.fill(USER, "alice"),
                Command.select(LocatorSpec.parse("role=combobox;name=r"), "42")),
                new ParamScope(Map.of(
                        "value", ParamConstraint.allowedValues("alice", "bob"),
                        "option", ParamConstraint.inRange(1, 100))),
                List.of(ParamScope.EMPTY, ParamScope.EMPTY));
        ParamScopePolicy.validatePlan(plan); // 不抛
    }

    @Test
    public void sideEffectParamNotDeclaredRejected() {
        // FILL.value 未在 scope 声明 → 治理拒绝
        StepPlan plan = new StepPlan(1, "p", List.of(Command.fill(USER, "alice")),
                new ParamScope(Map.of("option", ParamConstraint.allowedValues("x"))),
                List.of(ParamScope.EMPTY));
        assertThrowsViolation(() -> ParamScopePolicy.validatePlan(plan));
    }

    @Test
    public void emptyScopePlanWithSideEffectRejected() {
        // 无 scope 的 FILL → 所有参数视为未声明 → 拒绝
        StepPlan plan = new StepPlan(1, "p", List.of(Command.fill(USER, "alice")),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        assertThrowsViolation(() -> ParamScopePolicy.validatePlan(plan));
    }

    @Test
    public void readOnlyAssertNotChecked() {
        // ASSERT_* 无写入型参数，不在治理范围
        StepPlan plan = new StepPlan(1, "p",
                List.of(Command.assertVisible(USER)), ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        ParamScopePolicy.validatePlan(plan); // 不抛
    }

    @Test
    public void stepScopeOverridesPlanScope() {
        // plan 级未声明 value，但 step 级声明了 → 合规
        StepPlan plan = new StepPlan(1, "p", List.of(Command.fill(USER, "alice")),
                ParamScope.EMPTY,
                List.of(new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice")))));
        ParamScopePolicy.validatePlan(plan); // 不抛（step 覆盖 plan）
    }

    private static void assertThrowsViolation(ThrowingRunnable r) {
        try {
            r.run();
            fail("expected ParamScopeViolationException");
        } catch (ParamScopeViolationException expected) {
            // 期望的治理拒绝
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}

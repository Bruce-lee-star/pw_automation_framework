package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.ActionTypePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * StepPlan 建议的<b>统一门禁</b>（设计 §4.3 / §9 P1 验收 / §10.4 R6）。
 *
 * <p><b>R6 落点</b>：本校验器<b>独立于任何 translator</b>。规则引擎产出、模型建议、
 * 手写计划、外部导入一律过同一门禁；「AI 只产不可信建议，执行器独立校验权限与动作」由此成立。
 * 校验通过≠自动执行：执行仍由 {@code StepDriver} + {@code ParamScopeGate} 在浏览器侧裁决。</p>
 *
 * <p>三合一校验：
 * <ul>
 *   <li><b>schema</b>：schemaVersion、planId、非空步骤、逐步 scope 可对齐（指纹可算）；</li>
 *   <li><b>策略</b>：{@link ParamScopePolicy#validatePlan(StepPlan)}（副作用参数必须声明取值域）；</li>
 *   <li><b>可执行性</b>：动作在授权集合内、按 ActionType 的参数 schema、target 规则、
 *       每个动作都有 {@link ActionTypePolicy} 策略、{@link StepPlan#planDigest()} 可计算。</li>
 * </ul>
 */
public final class IntentPlanValidator {

    /** 结构化校验报告（供等价性对账 / 诊断 / 测试断言，不含任何参数值）。 */
    public record Report(boolean accepted, List<String> violations, Set<IntentTranslationException.Reason> reasons) {
        public Report {
            violations = List.copyOf(violations);
            reasons = Set.copyOf(reasons);
        }
    }

    private IntentPlanValidator() {
    }

    /** fail-closed 校验：不合规即抛 {@link IntentTranslationException}。 */
    public static void validate(StepPlan plan) {
        Report report = inspect(plan);
        if (!report.accepted()) {
            IntentTranslationException.Reason reason = report.reasons().stream().findFirst()
                    .orElse(IntentTranslationException.Reason.PLAN_NOT_EXECUTABLE);
            throw new IntentTranslationException(reason,
                    "StepPlan rejected: " + String.join("; ", report.violations()));
        }
    }

    /** 非抛出版检查：返回全部违规项（不短路），便于一次给出完整诊断。 */
    public static Report inspect(StepPlan plan) {
        List<String> violations = new ArrayList<>();
        Set<IntentTranslationException.Reason> reasons = new LinkedHashSet<>();
        if (plan == null) {
            return new Report(false, List.of("plan is null"),
                    Set.of(IntentTranslationException.Reason.INVALID_INPUT));
        }
        if (plan.schemaVersion() != RecordedSkeleton.SUPPORTED_SCHEMA_VERSION) {
            violations.add("unsupported schemaVersion: " + plan.schemaVersion());
            reasons.add(IntentTranslationException.Reason.UNSUPPORTED_SCHEMA);
        }
        if (plan.planId() == null || plan.planId().isBlank()) {
            violations.add("planId is blank");
            reasons.add(IntentTranslationException.Reason.INVALID_INPUT);
        }
        if (plan.steps().isEmpty()) {
            violations.add("steps is empty");
            reasons.add(IntentTranslationException.Reason.BOUNDS_VIOLATION);
        }
        if (plan.steps().size() > RecordedSkeleton.MAX_STEPS) {
            violations.add("steps exceeds bound " + RecordedSkeleton.MAX_STEPS);
            reasons.add(IntentTranslationException.Reason.BOUNDS_VIOLATION);
        }
        try {
            plan.planDigest(); // stepScopes 与 steps 必须等长，否则此处抛
        } catch (RuntimeException e) {
            violations.add("planDigest not computable (stepScopes/steps length mismatch?)");
            reasons.add(IntentTranslationException.Reason.PLAN_NOT_EXECUTABLE);
        }
        for (int i = 0; i < plan.steps().size(); i++) {
            inspectStep(plan.steps().get(i), i, violations, reasons);
        }
        try {
            ParamScopePolicy.validatePlan(plan);
        } catch (ParamScopeViolationException e) {
            violations.add("policy: " + e.getMessage());
            reasons.add(IntentTranslationException.Reason.POLICY_VIOLATION);
        }
        return new Report(violations.isEmpty(), violations, reasons);
    }

    private static void inspectStep(Command command, int index, List<String> violations,
                                    Set<IntentTranslationException.Reason> reasons) {
        String at = "step[" + index + "]";
        if (command == null || command.action() == null) {
            violations.add(at + " action is null");
            reasons.add(IntentTranslationException.Reason.UNKNOWN_ACTION);
            return;
        }
        Command.ActionType action = command.action();
        if (!IntentActionSchema.isAuthorized(action)) {
            violations.add(at + " action not authorized: " + action);
            reasons.add(IntentTranslationException.Reason.ACTION_NOT_ALLOWED);
            return;
        }
        try {
            ActionTypePolicy.of(action);
        } catch (RuntimeException e) {
            violations.add(at + " action has no ActionTypePolicy: " + action);
            reasons.add(IntentTranslationException.Reason.PLAN_NOT_EXECUTABLE);
        }
        String target = command.target() == null ? null : command.targetRef();
        Map<String, String> params = command.params() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(command.params());
        try {
            IntentActionSchema.validate(action, target, params);
        } catch (IntentTranslationException e) {
            violations.add(at + " " + e.reason() + ": " + e.getMessage());
            reasons.add(e.reason());
        }
    }
}

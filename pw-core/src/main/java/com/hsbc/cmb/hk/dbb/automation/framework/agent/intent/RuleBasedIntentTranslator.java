package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 确定性规则引擎（{@link IntentTranslator} 默认实现，设计 §9 P1「录制骨架结构化 → StepPlan」）。
 *
 * <p><b>零 LLM / 零网络 / 零凭据</b>：纯字符串与对象映射，相同规范化输入恒产出相同
 * {@link StepPlan#planDigest()}，不依赖任何外部服务，可在回归 CI 中确定性重放。</p>
 *
 * <p><b>八道 fail-closed 门禁</b>（§9 P1 验收）：
 * <ol>
 *   <li>schemaVersion 由 {@link RecordedSkeleton} 构造/解析时校验；</li>
 *   <li>origin 必须在构造注入的批准集合中，否则 {@code UNAPPROVED_ORIGIN}；</li>
 *   <li>动作名必须映射到已知 {@link Command.ActionType}，否则 {@code UNKNOWN_ACTION}；</li>
 *   <li>动作必须过 {@link IntentActionSchema} 授权（禁 {@code SWITCH_TO_POPUP}）；</li>
 *   <li>参数键/必填项按 ActionType schema 校验，白名单外即拒；</li>
 *   <li>target canonical 必须可解析；NAVIGATE 不得带 target；</li>
 *   <li>NAVIGATE 的 {@code url} 必须等于骨架声明的 {@code pageRef}（防止塞入未批准目标逃逸）；</li>
 *   <li>产出计划经 {@link IntentPlanValidator} 的 schema / 策略 / 可执行性三合一校验。</li>
 * </ol>
 *
 * <p><b>参数范围自动声明</b>：plan 级 scope 声明 {@code url} 仅允许 {@code pageRef}；
 * FILL/SELECT 的写入值经 {@link ParamScope#forAssetWriteValue} 生成 step 级 scope，
 * 使 {@code ParamScopePolicy.validatePlan} 的「副作用参数必须声明取值域」治理规则通过。</p>
 *
 * <p>不可变、线程安全（批准集合防御性拷贝，无共享可变状态）。</p>
 */
public final class RuleBasedIntentTranslator implements IntentTranslator {

    private final Set<String> approvedOrigins;

    /**
     * @param approvedOrigins 批准的录制来源集合；空集合 = fail-closed（拒绝一切）
     */
    public RuleBasedIntentTranslator(Set<String> approvedOrigins) {
        Objects.requireNonNull(approvedOrigins, "approvedOrigins");
        this.approvedOrigins = Set.copyOf(approvedOrigins);
    }

    /** 便捷构造：批准单个来源。 */
    public static RuleBasedIntentTranslator approving(String... origins) {
        return new RuleBasedIntentTranslator(Set.of(origins));
    }

    @Override
    public StepPlan translate(RecordedSkeleton skeleton) {
        if (skeleton == null) {
            throw new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT,
                    "skeleton must not be null");
        }
        RecordedSkeleton s = skeleton.normalized();
        if (!approvedOrigins.contains(s.origin())) {
            throw new IntentTranslationException(IntentTranslationException.Reason.UNAPPROVED_ORIGIN,
                    "origin not approved: " + s.origin());
        }

        List<Command> commands = new ArrayList<>(s.steps().size());
        List<ParamScope> stepScopes = new ArrayList<>(s.steps().size());
        for (int i = 0; i < s.steps().size(); i++) {
            RecordedStep step = s.steps().get(i);
            Command.ActionType action = parseAction(step.action());
            IntentActionSchema.validate(action, step.target(), step.params());
            if (action == Command.ActionType.NAVIGATE
                    && !s.pageRef().equals(step.params().get("url"))) {
                throw new IntentTranslationException(IntentTranslationException.Reason.TARGET_VIOLATION,
                        "NAVIGATE url must equal pageRef (unapproved navigation target rejected)");
            }
            LocatorSpec target = parseTarget(action, step.target());
            commands.add(new Command(action, target, new LinkedHashMap<>(step.params())));
            stepScopes.add(scopeForStep(action, step.params()));
        }

        ParamScope planScope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues(s.pageRef())));
        StepPlan plan = new StepPlan(
                RecordedSkeleton.SUPPORTED_SCHEMA_VERSION,
                planIdOf(s),
                commands,
                planScope,
                stepScopes);
        IntentPlanValidator.validate(plan);
        return plan;
    }

    @Override
    public String id() {
        return "RuleBasedIntentTranslator";
    }

    private static Command.ActionType parseAction(String raw) {
        try {
            return Command.ActionType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IntentTranslationException(IntentTranslationException.Reason.UNKNOWN_ACTION,
                    "unknown ActionType: " + raw, e);
        }
    }

    private static LocatorSpec parseTarget(Command.ActionType action, String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        try {
            return LocatorSpec.parse(target);
        } catch (RuntimeException e) {
            throw new IntentTranslationException(IntentTranslationException.Reason.TARGET_VIOLATION,
                    "invalid locator canonical for " + action + ": " + target, e);
        }
    }

    private static ParamScope scopeForStep(Command.ActionType action, Map<String, String> params) {
        String value = switch (action) {
            case FILL -> params.get("value");
            case SELECT -> params.get("option");
            default -> null;
        };
        return ParamScope.forAssetWriteValue(action, value);
    }

    /** 由规范化内容派生的确定性 planId（不含敏感值；同一输入恒等）。 */
    private static String planIdOf(RecordedSkeleton s) {
        StringBuilder payload = new StringBuilder(s.originId()).append('\n').append(s.pageRef()).append('\n');
        for (RecordedStep step : s.steps()) {
            payload.append(step.action()).append('|')
                    .append(step.target() == null ? "" : step.target()).append('|');
            new java.util.TreeMap<>(step.params())
                    .forEach((k, v) -> payload.append(k).append('=').append(v).append(';'));
            payload.append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("intent-");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

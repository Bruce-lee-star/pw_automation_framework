package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.ActionParamSchema;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 意图翻译的<b>动作授权层</b>（设计 §4.3 / §9 P1 / §11.1「动作授权」）。
 *
 * <p><b>授权边界（越权 Action 拒绝）</b>：录制骨架与模型建议只允许产生用户可观察的 9 个原语，
 * <b>禁止 {@link Command.ActionType#SWITCH_TO_POPUP}</b> —— 该原语由框架在
 * {@code PlaywrightChannel} 的 {@code onPopup} 捕获新页面后内部产生（P0 第 4 项），
 * 外部输入凭空注入会绕过 popup 捕获状态、造成上下文切换越权。</p>
 *
 * <p><b>参数 schema 不在本类</b>：必填键/允许键/是否需 target 由通道协议侧的
 * {@link ActionParamSchema} 承载（{@code StepPlan.parse} 也用同一张表），本类只做
 * 「授权白名单 + 语义化异常」两件事，避免同一份策略在两处维护。</p>
 *
 * <p>不可变、线程安全；{@link RuleBasedIntentTranslator}、{@link IntentPlanValidator}
 * 与 {@code IntentPlanValidator} 共用本类，保证「生成」与「校验」不会策略漂移。</p>
 */
public final class IntentActionSchema {

    /**
     * 单步 schema 视图（必填参数键 + 允许参数键 + 是否要求 target）。
     *
     * <p>保留为独立类型仅为兼容既有调用方；内容直接来自 {@link ActionParamSchema}。</p>
     */
    public record ActionSpec(Set<String> requiredParams, Set<String> allowedParams, boolean requiresTarget) {
        public ActionSpec {
            requiredParams = Collections.unmodifiableSet(requiredParams);
            allowedParams = Collections.unmodifiableSet(allowedParams);
        }
    }

    /** 允许由外部输入（录制骨架 / 模型建议）产生的动作白名单。 */
    private static final Set<Command.ActionType> AUTHORIZED = Collections.unmodifiableSet(
            EnumSet.complementOf(EnumSet.of(Command.ActionType.SWITCH_TO_POPUP)));

    private IntentActionSchema() {
    }

    /** 该动作是否允许由录制骨架 / 模型建议产生。 */
    public static boolean isAuthorized(Command.ActionType action) {
        return action != null && AUTHORIZED.contains(action);
    }

    /** 授权动作集合（不可变副本，供诊断与测试）。 */
    public static Set<Command.ActionType> authorizedActions() {
        return AUTHORIZED;
    }

    /** 取动作 schema；未授权动作返回 {@code null}。 */
    public static ActionSpec specOf(Command.ActionType action) {
        if (!isAuthorized(action)) {
            return null;
        }
        ActionParamSchema.Spec spec = ActionParamSchema.specOf(action);
        return new ActionSpec(spec.requiredParams(), spec.allowedParams(), spec.requiresTarget());
    }

    /**
     * 校验单步的参数与 target 是否符合该 ActionType 的 schema；不合规则抛
     * {@link IntentTranslationException}（带精确 {@link IntentTranslationException.Reason}）。
     */
    public static void validate(Command.ActionType action, String target, Map<String, String> params) {
        if (!isAuthorized(action)) {
            throw new IntentTranslationException(IntentTranslationException.Reason.ACTION_NOT_ALLOWED,
                    "action not authorized for intent translation: " + action);
        }
        ActionParamSchema.Spec spec = ActionParamSchema.specOf(action);
        if (spec.requiresTarget() && (target == null || target.isBlank())) {
            throw new IntentTranslationException(IntentTranslationException.Reason.TARGET_VIOLATION,
                    action + " requires target");
        }
        if (!spec.requiresTarget() && target != null && !target.isBlank()) {
            throw new IntentTranslationException(IntentTranslationException.Reason.TARGET_VIOLATION,
                    action + " must not carry target");
        }
        for (String key : params.keySet()) {
            if (!spec.allowedParams().contains(key)) {
                throw new IntentTranslationException(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                        action + " disallows param '" + key + "'");
            }
        }
        for (String key : spec.requiredParams()) {
            String v = params.get(key);
            if (v == null || v.isBlank()) {
                throw new IntentTranslationException(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                        action + " requires non-blank param '" + key + "'");
            }
        }
    }
}

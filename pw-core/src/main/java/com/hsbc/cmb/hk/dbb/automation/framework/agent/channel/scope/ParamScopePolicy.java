package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.ActionTypePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;

import java.util.Map;
import java.util.Objects;

/**
 * 参数范围治理规则（v3.7，独立于 fail-closed 门禁 {@link ParamScopeGate}）。
 *
 * <p>{@link ParamScopeGate} 仅在 scope 非空时核对取值；本策略负责更上层的
 * 「<b>副作用动作的参数必须显式声明取值域</b>」合规校验——它是执行前的治理前置，
 * 与门禁解耦，由治理层（而非每次 execute）按需调用。</p>
 *
 * <p><b>判定</b>：带写入型参数的副作用动作（{@code NAVIGATE/FILL/SELECT/CHECK/UNCHECK}）
 * 其每个非 {@code secretRef} 参数都必须在对应 step 的取值域（step 覆盖 plan）中声明，
 * 否则抛 {@link ParamScopeViolationException}。</p>
 */
public final class ParamScopePolicy {

    /** 引用型参数（R1）：仅作密钥引用，不要求声明取值域。 */
    private static final String SECRET_REF = "secretRef";

    private ParamScopePolicy() {
    }

    /**
     * 校验整个 StepPlan 的参数范围合规：任一副作用动作的未声明参数即拒。
     * 空 scope 计划中的副作用参数一律视为「未声明」→ 拒绝（治理 fail-closed）。
     */
    public static void validatePlan(StepPlan plan) {
        Objects.requireNonNull(plan, "plan");
        for (int i = 0; i < plan.steps().size(); i++) {
            Command c = plan.steps().get(i);
            if (!isSideEffecting(c)) {
                continue;
            }
            ParamScope scope = plan.resolvedScope(i);
            for (Map.Entry<String, String> e : c.params().entrySet()) {
                if (SECRET_REF.equals(e.getKey())) {
                    continue;
                }
                if (!scope.constraintFor(e.getKey()).isPresent()) {
                    throw new ParamScopeViolationException(
                            c.action() + " @ " + c.targetRef()
                                    + ": 副作用参数 '" + e.getKey() + "' 未在 scope 中声明"
                                    + "（治理规则：副作用动作必须声明取值域）");
                }
            }
        }
    }

    private static boolean isSideEffecting(Command c) {
        // 单一事实来源：逐动作副作用语义改由 ActionTypePolicy 持有（v3.10），此处不再硬编码
        return ActionTypePolicy.of(c.action()).sideEffecting();
    }
}

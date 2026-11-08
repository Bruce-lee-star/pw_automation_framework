package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * P0 受限 DSL 的验收执行策略（设计 §9 P0 第 7 项 / §10.3）。
 *
 * <p>复用既有<b>同一门禁边界</b>（{@link ParamScopePolicy#validatePlan} 治理预检 +
 * {@code channel.execute(step, plan.resolvedScope(i))} 参数范围 fail-closed），只补齐验收所需的三条语义：
 * <ol>
 *   <li><b>失败即停止</b>：任一步 {@code CommandResult.fail} 立即中断，后续步骤（尤其副作用动作）一律不执行；</li>
 *   <li><b>非幂等不重试</b>：失败步骤只执行一次，绝不自动重试——写操作是否已提交不确定时必须停下转人工，
 *       避免 trace 重放导致二次提交；</li>
 *   <li><b>诊断脱敏</b>：失败 message 经 {@link SensitiveDataSanitizer#sanitizeLogMessage} 收口后才进入
 *       {@link CommandResult#message()} / 诊断文本（R1/R7：敏感 canary 不得经异常/日志出域）。</li>
 * </ol>
 *
 * <p>另对 v3.8 有界协议 fail-closed：若某步返回的快照是 TRUNCATED / UNSUPPORTED，视为观测不足立即停止，
 * 不继续在未证明完整的观测上执行后续动作。</p>
 *
 * <p>本类不替代 {@link StepDriver#execute}——后者保留「收集全部步骤结果」语义供验证侧统计；
 * 二者共用同一门禁边界，不引入第二套授权或执行语义。</p>
 */
public final class DslAcceptanceRunner {

    private DslAcceptanceRunner() {
    }

    /**
     * @param results        已执行步骤的结果（message 已脱敏）；失败即停止时长度小于 plan.steps()
     * @param executedSteps  实际执行步数
     * @param stopped        是否因失败 / 观测不足而中断
     * @param diagnostics    脱敏后的中断诊断；未中断时为 {@code null}
     */
    public record Outcome(List<CommandResult> results, int executedSteps, boolean stopped, String diagnostics) {

        public Outcome {
            results = results == null ? List.of()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(results));
        }

        @Override
        public List<CommandResult> results() {
            return java.util.Collections.unmodifiableList(results);
        }
    }

    public static Outcome run(StepPlan plan, BrowserCommandChannel channel) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(channel, "channel");
        ParamScopePolicy.validatePlan(plan); // 治理预检：副作用动作须声明 scope，fail-fast

        List<CommandResult> results = new ArrayList<>(plan.steps().size());
        String diagnostics = null;
        boolean stopped = false;
        for (int i = 0; i < plan.steps().size(); i++) {
            Command step = plan.steps().get(i);
            // 与 StepDriver 完全相同的门禁边界（不再另起执行/授权语义）
            CommandResult raw = channel.execute(step, plan.resolvedScope(i));
            String safeMessage = raw.message() == null
                    ? null : SensitiveDataSanitizer.sanitizeLogMessage(raw.message());
            CommandResult sanitized = new CommandResult(raw.success(), raw.snapshot(), safeMessage);
            results.add(sanitized);

            if (!raw.success()) {
                stopped = true;
                diagnostics = safeMessage == null ? "step failed" : safeMessage;
                break; // 失败即停止：不重试该步、不执行后续副作用动作
            }
            if (raw.snapshot() != null && !raw.snapshot().isComplete()) {
                stopped = true;
                SnapshotEnvelope e = raw.snapshot().envelope();
                diagnostics = "snapshot incomplete " + e.status() + " (" + e.detail() + ") — abort before further actions";
                break; // 部分观测下继续动作会把「没看到」误当作事实
            }
        }
        return new Outcome(List.copyOf(results), results.size(), stopped, diagnostics);
    }
}

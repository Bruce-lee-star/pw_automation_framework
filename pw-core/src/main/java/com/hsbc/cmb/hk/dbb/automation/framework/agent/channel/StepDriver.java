package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.AgentTraceEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.AgentTraceRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.ActionTypePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 确定性步骤执行编排（v3.7，pw-core，浏览器无关）。
 *
 * <p>把已接好参数范围门禁的 {@link BrowserCommandChannel} 边界真正用起来：
 * 给定一个 {@link StepPlan} 与一个命令通道实现，先跑治理预检
 * {@link ParamScopePolicy#validatePlan(StepPlan)}（副作用动作须声明 scope），
 * 再逐步以「step 覆盖 plan」的最终 scope 调用 {@code channel.execute(step, resolvedScope)}——
 * 门禁在该边界 fail-closed（未声明 / 越界参数不执行该步）。</p>
 *
 * <p>本类只负责<b>编排 + 门禁接线</b>，不含联动断言 / 快照比对等业务逻辑（属更大专项）；
 * 门禁失败（{@link ParamScopeViolationException}）fail-fast 向上传播，不静默吞。</p>
 */
public final class StepDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(StepDriver.class);

    private StepDriver() {
    }

    /**
     * 执行整份 StepPlan：先治理预检，再逐步过门禁边界执行。
     *
     * @return 每步的 {@link CommandResult}（与 steps 顺序一致；门禁失败会在对应步前抛异常，后续步不执行）
     * @throws ParamScopeViolationException 任一步参数越界，或治理预检不通过（fail-fast）
     * @throws NullPointerException       plan / channel 为 null
     */
    public static List<CommandResult> execute(StepPlan plan, BrowserCommandChannel channel) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(channel, "channel");
        ParamScopePolicy.validatePlan(plan); // 治理预检（副作用动作须声明 scope）
        List<CommandResult> results = new ArrayList<>(plan.steps().size());
        for (int i = 0; i < plan.steps().size(); i++) {
            Command step = plan.steps().get(i);
            // 门禁边界：channel.execute(Command, ParamScope) 默认方法先 ParamScopeGate.check 再 execute(Command)
            long startedAt = System.nanoTime();
            CommandResult result = channel.execute(step, plan.resolvedScope(i));
            // 重试策略（P0 第 3 项）：仅幂等动作允许有限重试；写操作一次失败即停，避免 trace 重放二次提交
            ActionTypePolicy.ActionPolicy policy = ActionTypePolicy.of(step.action());
            int attempts = 1;
            while (!result.success() && policy.idempotent() && policy.maxRetries() > 0
                    && attempts <= policy.maxRetries()) {
                result = channel.execute(step, plan.resolvedScope(i));
                attempts++;
            }
            recordTrace(plan, step, i, startedAt, result);
            results.add(result);
        }
        return results;
    }

    /**
     * 执行单步（逐步门禁 + 执行 + 重试 + 审计），供 {@link StepVerifier} 逐步编排（D3 稳定语义需在每步
     * 执行后、断言前 settle + 重新采集稳定态快照）。
     *
     * <p>与 {@link #execute(StepPlan, BrowserCommandChannel)} 的逐步逻辑逐字一致，仅不再批量汇聚结果；
     * 调用方负责在执行前跑一次治理预检 {@link ParamScopePolicy#validatePlan(StepPlan)}（本方法不重复预检）。</p>
     *
     * @param plan   待执行的步骤计划
     * @param index  当前步索引（0-based）
     * @param channel 命令通道（含门禁边界 + settle 能力）
     * @return 该步的 {@link CommandResult}（门禁失败会在此步前抛异常）
     * @throws ParamScopeViolationException 该步参数越界（门禁 fail-fast）
     * @throws NullPointerException       plan / channel 为 null，或 index 越界
     */
    public static CommandResult executeStep(StepPlan plan, int index, BrowserCommandChannel channel) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(channel, "channel");
        Command step = plan.steps().get(index);
        long startedAt = System.nanoTime();
        CommandResult result = channel.execute(step, plan.resolvedScope(index));
        // 重试策略（P0 第 3 项）：仅幂等动作允许有限重试；写操作一次失败即停，避免 trace 重放二次提交
        ActionTypePolicy.ActionPolicy policy = ActionTypePolicy.of(step.action());
        int attempts = 1;
        while (!result.success() && policy.idempotent() && policy.maxRetries() > 0
                && attempts <= policy.maxRetries()) {
            result = channel.execute(step, plan.resolvedScope(index));
            attempts++;
        }
        recordTrace(plan, step, index, startedAt, result);
        return result;
    }

    /**
     * R9 审计埋点：逐步写最小元数据轨迹。经 {@link AgentTraceRegistry} 走 SPI，无实现时 no-op，
     * 且本方法自身吞掉一切异常——审计失败绝不能影响主执行链路。
     */
    private static void recordTrace(StepPlan plan, Command step, int index, long startedAt, CommandResult result) {
        try {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            AgentTraceRegistry.get().record(new AgentTraceEvent(
                    plan.planId() + "#" + index,
                    String.valueOf(plan.schemaVersion()),
                    step.action().name(),
                    step.params() == null ? null : step.params().get("url"),
                    durationMs,
                    result.success(),
                    plan.planDigest(),
                    null));
        } catch (Throwable t) {
            LOGGER.debug("[agent-trace] record skipped: {}", t.toString());
        }
    }
}

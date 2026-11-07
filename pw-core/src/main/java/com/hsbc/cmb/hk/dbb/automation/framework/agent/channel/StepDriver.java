package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfProbe;
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
     * 执行整份 StepPlan：先治理预检，再逐步过门禁边界执行。等价于
     * {@link #execute(StepPlan, BrowserCommandChannel, StepIdempotency)} 传入 {@link StepIdempotency#none()}。
     *
     * @return 每步的 {@link CommandResult}（与 steps 顺序一致；门禁失败会在对应步前抛异常，后续步不执行）
     * @throws ParamScopeViolationException 任一步参数越界，或治理预检不通过（fail-fast）
     * @throws NullPointerException       plan / channel 为 null
     */
    public static List<CommandResult> execute(StepPlan plan, BrowserCommandChannel channel) {
        return execute(plan, channel, StepIdempotency.none());
    }

    /**
     * 执行整份 StepPlan（带步骤级幂等上下文）。
     *
     * <p>逐步走 {@link #executeStep(StepPlan, int, BrowserCommandChannel, StepIdempotency)}：
     * 若该步此前已通过且命令内容未变，则复用缓存结果、不驱动浏览器；否则执行并在成功后记录后态指纹。</p>
     *
     * @param idempotency 幂等上下文；{@link StepIdempotency#none()} 即退化为「每次都执行」
     * @throws NullPointerException idempotency 为 null
     */
    public static List<CommandResult> execute(StepPlan plan, BrowserCommandChannel channel, StepIdempotency idempotency) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(idempotency, "idempotency");
        AgentPerfProbe.refresh(); // T9：每计划刷新一次采集开关（默认关），步骤级只读缓存
        ParamScopePolicy.validatePlan(plan); // 治理预检（副作用动作须声明 scope）
        List<CommandResult> results = new ArrayList<>(plan.steps().size());
        for (int i = 0; i < plan.steps().size(); i++) {
            results.add(executeStep(plan, i, channel, idempotency));
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
        return executeStep(plan, index, channel, StepIdempotency.none());
    }

    /**
     * 执行单步（带步骤级幂等上下文）。
     *
     * <p>若该步此前已通过且命令内容未变（{@link StepIdempotency#cachedPass} 命中），直接复用缓存的通过结果，
     * 不调用通道、不重试、不重放审计；否则执行（门禁 + 重试），成功后经 {@link StepIdempotency#recordPass}
     * 记录后态指纹。{@code idempotency} 为 {@link StepIdempotency#none()} 时与改造前逐字一致。</p>
     *
     * @param idempotency 幂等上下文（{@code null} 视为 {@link StepIdempotency#none()}）
     * @throws NullPointerException plan / channel 为 null，或 index 越界
     */
    public static CommandResult executeStep(StepPlan plan, int index, BrowserCommandChannel channel, StepIdempotency idempotency) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(channel, "channel");
        StepIdempotency idem = (idempotency == null) ? StepIdempotency.none() : idempotency;
        Command step = plan.steps().get(index);
        ParamScope resolvedScope = plan.resolvedScope(index);
        // T7 幂等：若此前已通过且命令内容未变，复用缓存结果，跳过浏览器驱动
        CommandResult cached = idem.cachedPass(plan, index, step, resolvedScope);
        if (cached != null) {
            return cached;
        }
        long startedAt = System.nanoTime();
        CommandResult result = channel.execute(step, resolvedScope);
        // 重试策略（P0 第 3 项）：仅幂等动作允许有限重试；写操作一次失败即停，避免 trace 重放二次提交
        ActionTypePolicy.ActionPolicy policy = ActionTypePolicy.of(step.action());
        int attempts = 1;
        while (!result.success() && policy.idempotent() && policy.maxRetries() > 0
                && attempts <= policy.maxRetries()) {
            result = channel.execute(step, resolvedScope);
            attempts++;
        }
        recordTrace(plan, step, index, startedAt, result);
        // T9：复用 R9 审计已有的 nanoTime 起点，关闭时立即返回（不额外取时间、不构造样本）
        AgentPerfProbe.recordStep(startedAt, result.success(), null);
        if (result.success()) {
            idem.recordPass(plan, index, step, resolvedScope, result.snapshotDigest());
        }
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

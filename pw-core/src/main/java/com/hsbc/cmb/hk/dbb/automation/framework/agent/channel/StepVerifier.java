package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 授权执行 + 执行后联动断言（验证驱动最小闭环，设计 §3.1.2.1 / §4.2）。
 *
 * <p>把两个已落地的最小构成块组合成一条可复用的链路：
 * <ol>
 *   <li>{@link StepDriver} —— 参数范围门禁闭环（治理预检 + 逐步过门禁边界）；</li>
 *   <li>{@link SnapshotVerifier} —— 绝对态字段级断言。</li>
 * </ol>
 * 本类不重新实现执行或断言，仅做<b>编排组合</b>：先经 {@code StepDriver} 拿到每步
 * {@link CommandResult}（门禁失败 fail-fast 直接向上传播，不进入断言），再对每步结果快照
 * 跑 {@code SnapshotVerifier}。验证期望以<b>伴随列表</b>传入（与执行规格 {@link StepPlan} 分离），
 * 不改动任何既有契约 / schema。</p>
 *
 * <p>门禁 fail-closed 贯穿：若任一步参数越界，{@code StepDriver} 抛
 * {@link ParamScopeViolationException}，本方法不捕获、不静默降级——未授权的执行不会进入联动断言。</p>
 *
 * <p><b>D3 稳定语义（I-03）</b>：逐行走联动断言前，若静默窗口配置开启（{@code >0}），先经
 * {@link BrowserCommandChannel#settle(long)} 等页面稳定，再<b>重新采集</b>快照后断言；关闭时（{@code <=0}）
 * 直接复用执行时快照，与改动前行为逐字一致。settle / 重新取快照失败一律回退执行时快照，辅助动作绝不拖垮断言——
 * 从而闭环「同元素快速重复断言抖动」红线。</p>
 */
public final class StepVerifier {

    private StepVerifier() {
    }

    /** D3 稳定语义（I-03）：验证前静默窗口配置键（默认 500ms；{@code <=0} 视为关闭，行为与现状一致）。 */
    private static final String AGENT_STEP_VERIFY_SETTLE_WINDOW_MS = "agent.step.verify.settle.window.ms";

    /**
     * 执行计划并对每步结果做联动断言。
     *
     * @param plan                    待执行的步骤计划（须通过参数范围治理预检）
     * @param channel                 命令通道（含门禁边界 + settle 能力）
     * @param expectationsPerStep     每步的快照字段期望；少于 steps 时，缺省步视为「无断言」
     * @return 每步的校验结果聚合（见 {@link StepVerificationResult}）
     * @throws ParamScopeViolationException 任一步参数越界（来自 StepDriver，fail-fast）
     * @throws NullPointerException        plan / channel / expectationsPerStep 为 null
     */
    public static StepVerificationResult verify(StepPlan plan, BrowserCommandChannel channel,
                                                List<List<SnapshotExpectation>> expectationsPerStep) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(expectationsPerStep, "expectationsPerStep");

        long settleWindowMs = resolveSettleWindowMs();
        // 治理预检（副作用动作须声明 scope）；逐步执行 + 每步执行后、断言前 settle + 重新采集稳定态快照，
        // 从而避免「批量执行后统一重快照」读到最终页面态、错位各步中间态的回归（D3 稳定语义）。
        ParamScopePolicy.validatePlan(plan);
        List<SnapshotVerificationResult> perStep = new ArrayList<>(plan.steps().size());
        for (int i = 0; i < plan.steps().size(); i++) {
            List<SnapshotExpectation> exps = (i < expectationsPerStep.size())
                    ? expectationsPerStep.get(i) : List.of();
            CommandResult r = StepDriver.executeStep(plan, i, channel);
            if (r.snapshot() == null) {
                perStep.add(new SnapshotVerificationResult(false,
                        List.of(new AssertionFailure("no snapshot for step " + i
                                + (r.message() != null ? " (" + r.message() + ")" : ""), "present", "absent")),
                        1, 0));
            } else {
                Snapshot snap = resolveVerificationSnapshot(channel, r, settleWindowMs);
                perStep.add(SnapshotVerifier.verify(snap, exps));
            }
        }
        return new StepVerificationResult(perStep);
    }

    /**
     * 执行计划并对每步结果做联动断言——以「可验证计划」载体为入口。
     *
     * <p>等价 {@link #verify(StepPlan, BrowserCommandChannel, List)}，但把计划 / 逐步期望 /
     * 页面引用打包进 {@link VerifiableStepPlan}（分析层 → 执行层的数据契约），使资产验证
     * 与资产生产流水线收敛到<b>同一个闭环入口</b>：两种来源产出的 {@code VerifiableStepPlan}
     * 都经 {@code StepDriver}（门禁执行）+ {@code SnapshotVerifier}（断言）验证。</p>
     *
     * @param vsp     可验证步骤计划（含执行计划、逐步期望、页面引用）
     * @param channel 命令通道（含门禁边界 + settle 能力）
     * @return 每步的校验结果聚合
     * @throws ParamScopeViolationException 任一步参数越界（来自 StepDriver，fail-fast）
     */
    public static StepVerificationResult verify(VerifiableStepPlan vsp, BrowserCommandChannel channel) {
        return verify(vsp.plan(), channel, vsp.expectationsPerStep());
    }

    /**
     * D3 稳定语义（I-03）：解析验证前静默窗口配置。默认 500ms；{@code <=0} 视为关闭。
     * 键经 {@link ConfigReader} 解析（系统属性 / 文件 / 默认值），解析失败回退默认 500ms。
     */
    private static long resolveSettleWindowMs() {
        // 系统属性优先（实时覆写，便于运行期调参与测试隔离），再回落到 ConfigReader 文件/默认解析
        String raw = System.getProperty(AGENT_STEP_VERIFY_SETTLE_WINDOW_MS);
        if (raw == null) {
            raw = ConfigReader.resolve(AGENT_STEP_VERIFY_SETTLE_WINDOW_MS, "500");
        }
        if (raw == null) {
            return 500L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 500L;
        }
    }

    /**
     * D3 稳定语义（I-03）：验证前先 settle 再取稳定态快照，消除「同元素快速重复断言抖动」。
     * 窗口关闭（{@code <=0}）时直接用执行时快照，与现状逐字一致；settle / 重新取快照失败一律回退执行时快照，
     * 辅助动作绝不拖垮断言。
     */
    private static Snapshot resolveVerificationSnapshot(BrowserCommandChannel channel, CommandResult r, long settleWindowMs) {
        if (settleWindowMs <= 0) {
            return r.snapshot();
        }
        try {
            channel.settle(settleWindowMs);
            Snapshot stable = channel.snapshot();
            return stable != null ? stable : r.snapshot();
        } catch (Throwable t) {
            return r.snapshot();
        }
    }
}

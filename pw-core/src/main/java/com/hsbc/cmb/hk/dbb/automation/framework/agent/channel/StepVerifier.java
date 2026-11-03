package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;
import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;
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
 */
public final class StepVerifier {

    private StepVerifier() {
    }

    /**
     * 执行计划并对每步结果做联动断言。
     *
     * @param plan                    待执行的步骤计划（须通过参数范围治理预检）
     * @param channel                 命令通道（含门禁边界）
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

        List<CommandResult> results = StepDriver.execute(plan, channel);
        List<SnapshotVerificationResult> perStep = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            List<SnapshotExpectation> exps = (i < expectationsPerStep.size())
                    ? expectationsPerStep.get(i) : List.of();
            CommandResult r = results.get(i);
            if (r.snapshot() == null) {
                perStep.add(new SnapshotVerificationResult(false,
                        List.of(new AssertionFailure("no snapshot for step " + i
                                + (r.message() != null ? " (" + r.message() + ")" : ""), "present", "absent")),
                        1, 0));
            } else {
                perStep.add(SnapshotVerifier.verify(r, exps));
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
     * @param channel 命令通道（含门禁边界）
     * @return 每步的校验结果聚合
     * @throws ParamScopeViolationException 任一步参数越界（来自 StepDriver，fail-fast）
     */
    public static StepVerificationResult verify(VerifiableStepPlan vsp, BrowserCommandChannel channel) {
        return verify(vsp.plan(), channel, vsp.expectationsPerStep());
    }
}

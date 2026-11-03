package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.util.List;

/**
 * 可验证步骤计划载体（闭环引擎 × 资产生产流水线的数据契约，无浏览器依赖）。
 *
 * <p>把「执行计划 {@link StepPlan}」「目标页面引用」打包——执行后期望已内联进 {@link StepPlan}
 * 的各命令（每步 = 动作 + 其期望后态，见 {@link Command#expectations()}），消除「计划 + 独立期望
 * 列表按索引对齐」的双轨耦合。资产生产层（pw-agent-analysis）产出的载体可被执行层（pw-web-ui 的
 * {@code StepPlanRunner}）直接消费做真实浏览器验证——数据经 pw-core 流动，
 * 避免分析层反向依赖 web-ui、把浏览器拉进分析层。</p>
 *
 * @param plan    执行计划（含参数范围治理域与内联的逐步期望）
 * @param pageRef 目标页面引用（喂给隔离靶场 harness 的导航目标）
 */
public record VerifiableStepPlan(StepPlan plan, String pageRef) {

    /** 派生逐步执行后期望（与 {@code plan.steps()} 对齐；命令无期望则为空列表）。 */
    public List<List<SnapshotExpectation>> expectationsPerStep() {
        return plan.steps().stream()
                .map(Command::expectations)
                .toList();
    }
}

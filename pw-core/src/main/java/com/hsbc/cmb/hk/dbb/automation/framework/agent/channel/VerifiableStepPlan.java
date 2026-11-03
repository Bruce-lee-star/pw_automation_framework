package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.util.List;

/**
 * 可验证步骤计划载体（闭环引擎 × 资产生产流水线的数据契约，无浏览器依赖）。
 *
 * <p>把「执行计划 {@link StepPlan}」「逐步执行后期望 {@link SnapshotExpectation}」「目标页面引用」
 * 三者打包，使资产生产层（pw-agent-analysis）产出的计划可被执行层（pw-web-ui 的
 * {@code StepPlanRunner}）直接消费做真实浏览器验证——数据经 pw-core 流动，
 * 避免分析层反向依赖 web-ui、把浏览器拉进分析层。</p>
 *
 * @param plan                执行计划（含参数范围治理域）
 * @param expectationsPerStep 逐步的执行后绝对态期望（与 {@code plan.steps()} 对齐；空列表 = 该步无断言）
 * @param pageRef             目标页面引用（喂给隔离靶场 harness 的导航目标）
 */
public record VerifiableStepPlan(StepPlan plan, List<List<SnapshotExpectation>> expectationsPerStep, String pageRef) {
}

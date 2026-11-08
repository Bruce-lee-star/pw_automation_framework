package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSinks;

/**
 * 生成 case 的 sink 生命周期收口（业务零钩子）。
 *
 * <p>绑定本身<b>不在这里</b>：生成的 Steps 在字段初始化期就调用
 * {@code StepPlanActionSinks.requireCurrent()}，该时点可能早于 {@code TestCaseStarted}，
 * 故由 {@code StepPlanActionSinks} 的 SPI 惰性装配兜底。本监听器只负责<b>清理</b>：</p>
 * <ul>
 *   <li>场景开始：先 clear，丢弃被复用 worker 线程上可能残留的上一场景 sink
 *       （跳过/中止的用例不一定发出 {@code TestCaseFinished}）；</li>
 *   <li>场景结束：clear，使下一个场景重新创建 sink。</li>
 * </ul>
 *
 * <p>{@code StepPlanActionSinks} 是 ThreadLocal，Cucumber 并行场景的事件在本场景 worker
 * 线程同步派发，故 clear 不会串到别的场景；此处不触碰 Page（场景结束事件晚于
 * {@code PlaywrightListener} 的 Context/Page 销毁）。</p>
 */
public final class SemanticSinkLifecycleListener implements FrameworkLifecycleListener {

    @Override
    public void onScenarioStarted(LifecycleScenarioInfo info) {
        StepPlanActionSinks.clear();
    }

    @Override
    public void onScenarioFinished(LifecycleScenarioResult result) {
        StepPlanActionSinks.clear();
    }
}

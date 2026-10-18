package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

/**
 * 框架原生生命周期状态（替代 serenity 的 {@code TestResult}）。
 *
 * <p>DEV-V5 迁移：serenity 的事件总线（{@code StepEventBus}）被框架原生生命周期总线取代，
 * 监听器不再消费 serenity 领域类型，改为消费本枚举与 {@link LifecycleStepResult} /
 * {@link LifecycleScenarioInfo} / {@link LifecycleScenarioResult}。</p>
 */
public enum LifecycleStatus {
    PASSED,
    FAILED,
    SKIPPED,
    IGNORED,
    PENDING,
    AMBIGUOUS
}

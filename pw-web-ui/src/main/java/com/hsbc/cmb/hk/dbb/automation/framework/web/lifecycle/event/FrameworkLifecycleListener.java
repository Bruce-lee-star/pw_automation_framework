package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

/**
 * 框架原生生命周期监听器（替代 serenity 的 {@code StepListener}）。
 *
 * <p>DEV-V5 迁移：原监听器层（{@code PlaywrightListener} / {@code StepFailureAggregator} /
 * {@code FailureScreenshotHandler} / {@code AxeCoreListener} / {@code AutoBrowserProcessor} 等）
 * 不再实现 serenity 的 {@code StepListener}，改为实现本接口，并消费框架原生事件类型。</p>
 *
 * <p>所有方法均为 default no-op，监听器按需覆写；派发时单监听器异常被隔离，不影响其它监听器。</p>
 */
public interface FrameworkLifecycleListener {

    /** 测试运行开始（等价于 serenity 的 testRunStarted）。 */
    default void onTestRunStarted() {
    }

    /** 场景开始（携带标签，供 AutoBrowser 等使用）。 */
    default void onScenarioStarted(LifecycleScenarioInfo info) {
    }

    /** 单个步骤开始（携带标题与行号；等价于 serenity 的 stepStarted）。 */
    default void onStepStarted(LifecycleStepInfo step) {
    }

    /** 单个步骤结束（含状态与异常；失败即等价于 serenity 的 stepFailed / lastStepFailed）。 */
    default void onStepFinished(LifecycleStepResult result) {
    }

    /** 场景结束（含最终状态与异常）。 */
    default void onScenarioFinished(LifecycleScenarioResult result) {
    }

    /** 测试运行结束。 */
    default void onTestRunFinished() {
    }
}

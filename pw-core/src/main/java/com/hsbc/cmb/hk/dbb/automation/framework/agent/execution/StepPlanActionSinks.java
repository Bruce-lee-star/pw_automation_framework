package com.hsbc.cmb.hk.dbb.automation.framework.agent.execution;

import java.util.Objects;

/**
 * {@link StepPlanActionSink} 的线程局部装配点。
 *
 * <p>生成的 Steps 类在构造时经 {@link #requireCurrent()} 取当前 sink；测试运行时（Cucumber {@code @Before}
 * 或 agent 装配器）须在场景执行前 {@link #setCurrent} 绑定实现、结束后 {@link #clear}。未绑定时抛出清晰异常，
 * 避免生成物在缺省下静默空跑。</p>
 */
public final class StepPlanActionSinks {

    private static final ThreadLocal<StepPlanActionSink> CURRENT = new ThreadLocal<>();

    private StepPlanActionSinks() {
    }

    public static void setCurrent(StepPlanActionSink sink) {
        CURRENT.set(Objects.requireNonNull(sink, "sink"));
    }

    public static StepPlanActionSink requireCurrent() {
        StepPlanActionSink sink = CURRENT.get();
        if (sink == null) {
            throw new IllegalStateException(
                    "StepPlanActionSink 未绑定：生成的 Steps 类需要测试运行时经 StepPlanActionSinks.setCurrent 注入执行实现");
        }
        return sink;
    }

    public static void clear() {
        CURRENT.remove();
    }
}

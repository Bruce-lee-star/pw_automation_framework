package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.LifecycleOrchestrator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 框架原生生命周期事件总线（替代 serenity 的 {@code StepEventBus}）。
 *
 * <p>进程内单派发器（非 ThreadLocal 单例，因为 Cucumber 并行引擎多线程派发）；监听器注册/注销线程安全。
 * 派发时对单个监听器的异常做隔离（记录并继续），避免一个监听器故障拖垮整个生命周期。</p>
 *
 * <p>事件源：{@link FrameworkCucumberEventListener}（Cucumber 原生事件适配器）。
 * 框架内部若需手动触发（如非 Cucumber 驱动的离线单测）也可直接调用本类的 {@code fire*} 方法。</p>
 */
public final class FrameworkLifecycleBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(FrameworkLifecycleBus.class);

    private static final CopyOnWriteArrayList<FrameworkLifecycleListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** 框架主生命周期监听器（Playwright 行为宿主）的单例，供原生总线驱动。 */
    public static final com.hsbc.cmb.hk.dbb.automation.framework.web.listener.PlaywrightListener PLAYWRIGHT_LISTENER =
            new com.hsbc.cmb.hk.dbb.automation.framework.web.listener.PlaywrightListener();

    private static volatile boolean defaultListenersRegistered = false;

    private FrameworkLifecycleBus() {
    }

    /**
     * 注册框架默认原生监听器集合（幂等）。顺序即派发顺序，至关重要：
     * <ol>
     *   <li>{@link ConsoleScenarioListener} —— 纯控制台彩色输出（场景开始/结果），置首使其在慢速浏览器初始化前即打印"正在执行哪条 case"；</li>
     *   <li>{@link LifecycleOrchestrator} —— 先做"跨 feature 清理 + 轮次横幅"，须在场景初始化之前；</li>
     *   <li>{@link com.hsbc.cmb.hk.dbb.automation.framework.web.listener.PlaywrightListener} —— 场景初始化/截图/失败聚合；</li>
     *   <li>{@link com.hsbc.cmb.hk.dbb.automation.framework.web.listener.AxeCoreListener} —— 无障碍扫描汇总。</li>
     * </ol>
     * 由 {@code FrameworkCucumberEventListener.setEventPublisher} 在进入 Cucumber 运行时时调用一次。
     */
    public static void ensureDefaultListeners() {
        if (defaultListenersRegistered) {
            return;
        }
        synchronized (FrameworkLifecycleBus.class) {
            if (defaultListenersRegistered) {
                return;
            }
            register(new ConsoleScenarioListener());
            register(LifecycleOrchestrator.INSTANCE);
            register(PLAYWRIGHT_LISTENER);
            register(com.hsbc.cmb.hk.dbb.automation.framework.web.listener.AxeCoreListener.INSTANCE);
            defaultListenersRegistered = true;
        }
    }

    public static boolean isDefaultListenersRegistered() {
        return defaultListenersRegistered;
    }

    /**
     * 测试隔离支持：清空所有已注册监听器并复位默认注册标记。
     * 仅用于单元测试在用例之间恢复干净的监听器集合，生产代码不得调用。
     */
    public static void resetForTest() {
        LISTENERS.clear();
        defaultListenersRegistered = false;
    }

    public static void register(FrameworkLifecycleListener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void unregister(FrameworkLifecycleListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static void fireTestRunStarted() {
        for (FrameworkLifecycleListener l : LISTENERS) {
            safe(l, "onTestRunStarted", () -> l.onTestRunStarted());
        }
    }

    public static void fireScenarioStarted(LifecycleScenarioInfo info) {
        for (FrameworkLifecycleListener l : LISTENERS) {
            safe(l, "onScenarioStarted", () -> l.onScenarioStarted(info));
        }
    }

    public static void fireStepStarted(LifecycleStepInfo step) {
        for (FrameworkLifecycleListener l : LISTENERS) {
            safe(l, "onStepStarted", () -> l.onStepStarted(step));
        }
    }

    public static void fireStepFinished(LifecycleStepResult result) {
        for (FrameworkLifecycleListener l : LISTENERS) {
            safe(l, "onStepFinished", () -> l.onStepFinished(result));
        }
    }

    public static void fireScenarioFinished(LifecycleScenarioResult result) {
        for (FrameworkLifecycleListener l : LISTENERS) {
            safe(l, "onScenarioFinished", () -> l.onScenarioFinished(result));
        }
    }

    public static void fireTestRunFinished() {
        for (FrameworkLifecycleListener l : LISTENERS) {
            safe(l, "onTestRunFinished", () -> l.onTestRunFinished());
        }
    }

    private static void safe(FrameworkLifecycleListener l, String method, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOGGER.warn("[FrameworkLifecycleBus] listener {} failed in {}: {}",
                    l.getClass().getName(), method, t.toString());
        }
    }
}

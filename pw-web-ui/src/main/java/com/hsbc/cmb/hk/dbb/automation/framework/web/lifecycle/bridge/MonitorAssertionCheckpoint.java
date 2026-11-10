package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionCheckpoint;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

/**
 * MONITOR 断言检查点的 web 侧适配：为 core 检查点提供<b>当前 case 的 BrowserContext</b>。
 *
 * <p>{@link PlaywrightManager#currentContextForThread()} 是无副作用 getter（不会懒创建
 * Context、不会触发框架初始化），适合在元素操作、等待循环等高频路径上调用；返回 null
 * 时 core 检查点直接放行，对非 route 场景零行为变化。</p>
 *
 * <p><b>为什么按 context 而不是按线程取数</b>：并行执行时同一线程可能先服务 A case、
 * 再服务 B case，而每个 case 的断言失败存放在各自 BrowserContext 对应的 runtime 里；
 * 以 context 为唯一归属键才能保证「只影响当前 case」。本适配层与 core 检查点均<b>不持有
 * 任何共享可变状态</b>，因此生命周期事件是否跨线程派发都不影响隔离性。</p>
 *
 * <p><b>执行模型差异</b>：Cucumber 原生 scenario 线程上 {@link #checkNow()} 会让当前步骤
 * 立即判红；在 {@code ConcurrentContextExecutor} 的 worker 线程上，异常会被包装成
 * {@code ContextTaskResult}，由编排线程在并发批次结束后回放判红（见
 * {@code LifecycleBusBridge.replayFailures}）。两者都不会终止 JVM 或中断整个 run。</p>
 *
 * @apiNote 框架内部 seam；业务代码无需直接调用，元素操作 / 页面等待已内置检查点。
 */
public final class MonitorAssertionCheckpoint {

    private MonitorAssertionCheckpoint() {
    }

    /**
     * 业务线程检查点：本 case 存在已定案的 MONITOR 断言失败时立即抛 {@link AssertionError}，
     * 使当前步骤判红、该 case 后续步骤被跳过；不影响其它 case 或整个 run。
     */
    public static void checkNow() {
        RouteAssertionCheckpoint.checkNow(PlaywrightManager.currentContextForThread());
    }

    /** 场景收尾：消费当前 context 剩余失败并留痕（不抛错，见 core 检查点契约）。 */
    public static void drainForEvidence() {
        RouteAssertionCheckpoint.drainForEvidence(PlaywrightManager.currentContextForThread());
    }
}

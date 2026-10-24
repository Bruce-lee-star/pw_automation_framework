package com.hsbc.cmb.hk.dbb.automation.framework.route.exec;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * V2-1 守卫（评审 23 号 P0）：DELAY 终结 / 响应轮询<b>不得</b>再落到
 * {@code CompletableFuture.delayedExecutor(ms, unit)} 的 {@code ForkJoinPool.commonPool()}。
 *
 * <p>该两参重载会污染 JVM 级共享池（并行度 = 核数 - 1），且与本模块"有界并发"原则冲突 ——
 * 改走 {@link RouteDelayScheduler}（模块自有、daemon、线程数有界）。</p>
 *
 * <p><b>可证伪</b>：若有人把调用点改回 {@code delayedExecutor} 两参，本测试对线程名前缀的断言即失败
 * （commonPool 的线程名为 {@code ForkJoinPool.commonPool-worker-N}）。</p>
 */
public class RouteDelaySchedulerTest {

    /** 核心守卫：任务必须跑在模块自有 daemon 线程上，绝不是 commonPool worker。 */
    @Test
    public void delayMustRunOnModuleOwnedDaemonThreadNotCommonPool() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Boolean> daemon = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        RouteDelayScheduler.delay(1L, () -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            done.countDown();
        });

        assertTrue("延迟任务必须在界内执行", done.await(5, TimeUnit.SECONDS));
        assertNotNull("必须捕获到线程名", threadName.get());
        assertTrue("V2-1：必须跑在模块自有线程（前缀 " + RouteDelayScheduler.THREAD_NAME_PREFIX + "），实际="
                + threadName.get(), threadName.get().startsWith(RouteDelayScheduler.THREAD_NAME_PREFIX));
        assertFalse("V2-1 回归：绝不能落到 ForkJoinPool.commonPool，实际=" + threadName.get(),
                threadName.get().startsWith("ForkJoinPool.commonPool"));
        assertTrue("必须是 daemon 线程（不得阻碍 JVM 退出）", daemon.get());
    }

    /** 负延迟按 0 处理，仍然执行（不抛）。 */
    @Test
    public void negativeDelayIsClampedAndStillRuns() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        RouteDelayScheduler.delay(-5L, done::countDown);
        assertTrue("负延迟必须按 0 处理并执行", done.await(5, TimeUnit.SECONDS));
    }

    /** null 任务安全忽略（绝不抛回事件线程）。 */
    @Test
    public void nullTaskIsIgnored() {
        RouteDelayScheduler.delay(1L, null);
    }

    /**
     * P1-7 收口：此前 {@code ScheduledThreadPoolExecutor} 默认无界队列 + AbortPolicy 下，
     * executor 级 {@code RejectedExecutionException} 只在 shutdown 后触发 —— 正常运营中
     * {@link RouteDelayScheduler#rejectedCount()} 恒为 0（死信号）。改为准入阶段软上限后，
     * 真实饱和（积压超 maxPending）必须触发丢弃并计数，让"调度池饱和"诊断信号可观测。
     */
    @Test
    public void schedulerSaturatesAndCountsRejections() throws Exception {
        final int savedMax = currentMaxPending();
        RouteDelayScheduler.setMaxPending(8); // 缩小软上限，构造真实饱和
        try {
            long before = RouteDelayScheduler.rejectedCount();
            // 一次性投递远超上限的微任务（50ms 延迟，2 线程来不及排空，积压必超 8）
            for (int i = 0; i < 2000; i++) {
                RouteDelayScheduler.delay(50L, () -> { /* 空任务：只占位，不碰连接 */ });
            }
            // 真实饱和下 rejectedCount 必须较基线增长（此前恒为 0，是死信号）
            assertTrue("P1-7：饱和时 rejectedCount 必须可触发（此前恒为 0，是死信号）",
                    RouteDelayScheduler.rejectedCount() > before);
            // 主动观测：backlog 已被软上限钳制，不应无限堆积（<= maxPending 量级）
            assertTrue("backlog 必须被软上限钳制，不得无限堆积",
                    RouteDelayScheduler.backlog() <= 8 + 64); // 容差：在途 2 + 排队上限 8 + 收尾抖动
        } finally {
            RouteDelayScheduler.setMaxPending(savedMax);
            Thread.sleep(120); // 让已排队的微任务排空，避免影响后续用例
        }
    }

    /** 读取当前软上限（同包可访问 setMaxPending，这里用反射兜底以免暴露 getter）。 */
    private static int currentMaxPending() {
        try {
            var f = RouteDelayScheduler.class.getDeclaredField("maxPending");
            f.setAccessible(true);
            return (int) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

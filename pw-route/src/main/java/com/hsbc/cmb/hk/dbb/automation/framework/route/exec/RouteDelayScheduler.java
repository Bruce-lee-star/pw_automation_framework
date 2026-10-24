package com.hsbc.cmb.hk.dbb.automation.framework.route.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模块自有的 daemon 延迟调度器（2026-09-29，评审 23 号 <b>V2-1</b> 修复）。
 *
 * <p><b>为什么不能再用 {@code CompletableFuture.delayedExecutor(ms, unit)}</b>：该<b>两参重载</b>会把任务
 * 落到 {@code ForkJoinPool.commonPool()} —— JVM 级共享、并行度 = 核数 - 1、且不受本模块"有界并发"原则约束。
 * 而 DELAY 终结（每条规则一次）与响应轮询（每 25ms × 每个在途观测请求）都会往那里塞任务，
 * 多 case 并行时会污染全 JVM 共享池（低核机甚至退化为每任务新线程），与本模块第 6 条原则自相矛盾。</p>
 *
 * <p>因此统一改走本类：<b>模块自有、daemon、线程数有界</b>的 {@link ScheduledExecutorService}。
 * 形态对齐模块既有先例（{@code RouteRuntimeImpl.sweeper} 的 per-runtime 单线程调度器、
 * {@code HangWatchdog.SCHED} 的进程级共享调度池）。</p>
 *
 * <p><b>为什么是进程级共享而非 per-runtime</b>：本类的两个调用点之一（{@code RouteDispatcher}）
 * 在调用链上没有 runtime 生命周期上下文，建进程级共享池可避免把调度器暴露到 SPI 接口上；
 * 池只做 O(1) 的极小任务（{@code existingResponse()} 为本地字段读取 + 一次 {@code resume}），
 * 线程数固定为 2 已足够，且 daemon 不阻碍 JVM 退出。</p>
 *
 * <p><b>拒绝即 fail-open，但必须可观测（P1-7 修订）</b>：默认 {@code ScheduledThreadPoolExecutor} 是
 * <b>无界</b> {@code DelayedWorkQueue} + {@code AbortPolicy}，但无界队列下 executor 级
 * {@link RejectedExecutionException} <b>只可能在 shutdown 后触发</b> —— 正常运营中 {@link #rejectedCount()}
 * 恒为 0（死信号）。故改为 <b>准入阶段软上限</b>：积压（{@link #backlog()}）超过 {@code maxPending} 即
 * fail-open 丢弃并计数，让"调度池饱和"信号在真实过载时可触发，且对内存有界（不再无限堆积）。本类吞掉异常/
 * 丢弃并计数告警，绝不把异常抛回事件线程 —— 延迟终结的兜底由 sweep 负责，请求不会悬挂。<b>计数不可省</b>：
 * 任务被丢弃意味着「这一次延迟/轮询没生效」，静默丢弃会让"延迟不准"变成一个查不出来的幽灵现象
 * （{@link #rejectedCount()} + {@link #backlog()}）。</p>
 *
 * <p><b>@apiNote</b> framework-internal（仅 route 内部使用，业务不得依赖）。</p>
 */
public final class RouteDelayScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDelayScheduler.class);

    /** 累计被拒绝（丢弃）的调度任务数：> 0 即说明调度池曾饱和 —— 延迟/轮询出现"少跑几次"的根因。 */
    private static final AtomicLong REJECTED = new AtomicLong(0);

    /**
     * 软上限（P1-7 修复）：在途+排队任务超过此值时，{@link #delay} 在<b>准入阶段</b>即 fail-open 丢弃并计数。
     *
     * <p>背景：{@code ScheduledThreadPoolExecutor} 默认是无界 {@code DelayedWorkQueue} + {@code AbortPolicy}，
     * 但无界队列下 {@code RejectedExecutionException} <b>只可能在 shutdown 后触发</b> —— 正常运营中
     * {@link #rejectedCount()} 恒为 0，"调度池饱和"这一诊断信号是<b>死信号</b>。故改为 admit 阶段软上限：
     * 积压超阈值即丢弃+计数，让饱和信号在真实过载时可触发，且对内存有界（不再无限堆积）。</p>
     */
    private static final int DEFAULT_MAX_PENDING = 1024;
    private static int maxPending = DEFAULT_MAX_PENDING;

    /** 测试接缝：覆盖软上限（默认 {@link #DEFAULT_MAX_PENDING}）。同包测试用。 */
    static void setMaxPending(int n) {
        maxPending = Math.max(1, n);
    }

    /** 线程名前缀：守卫测试据此断言"任务没有跑在 ForkJoinPool.commonPool 上"。 */
    public static final String THREAD_NAME_PREFIX = "route-delay";

    /** 有界线程数的 daemon 调度池（见类注释：任务极小，2 线程足够；绝不用 commonPool）。 */
    private static final ScheduledThreadPoolExecutor SCHED =
            (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(
            2, new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, THREAD_NAME_PREFIX + "-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });

    private RouteDelayScheduler() {
    }

    /**
     * 在模块自有 daemon 线程上延迟执行任务（替代 {@code delayedExecutor} 两参重载）。
     *
     * @param millis 延迟毫秒数（负值按 0 处理）
     * @param task   延迟任务（必须极短、非阻塞）
     */
    public static void delay(long millis, Runnable task) {
        if (task == null) {
            return;
        }
        //  P1-7：无界队列下，executor 级 RejectedExecutionException 只在 shutdown 后触发 → rejectedCount()
        //  在正常运营中恒为 0（死信号）。改为 admit 阶段软上限：积压（在途+排队）超 maxPending 即 fail-open
        //  丢弃并计数 —— 真实饱和时信号可触发，且对内存有界（不再无限堆积）。被丢弃的延迟/轮询由 sweep 兜底，
        //  请求不悬挂。
        if (SCHED.getQueue().size() >= maxPending) {
            long rejected = REJECTED.incrementAndGet();
            if (rejected <= 8 || (rejected & (rejected - 1)) == 0) {
                LOGGER.warn("[Route] delay/poll task rejected #{} (scheduler backlog {} >= maxPending={})"
                        + " -- fail-open, task dropped", rejected, SCHED.getQueue().size(), maxPending);
            }
            return;
        }
        try {
            SCHED.schedule(task, Math.max(0L, millis), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // shutdown 期的拒绝（常态下不会到这；软上限已先拦截）。fail-open：不把异常抛回事件线程；
            // 延迟终结的兜底由 sweep 负责。每条都计数（供诊断/断言），但只在前若干次打日志，避免刷爆。
            long rejected = REJECTED.incrementAndGet();
            if (rejected <= 8 || (rejected & (rejected - 1)) == 0) {
                LOGGER.warn("[Route] delay/poll task rejected #{} (scheduler shutting down)"
                        + " -- fail-open, task dropped", rejected);
            }
        }
    }

    /**
     * 累计被拒绝的任务数（进程级）。
     *
     * <p>诊断用：DELAY 到点误差异常、MONITOR 观测"漏结算"时先看这个数——大于 0 说明调度池曾饱和
     * （积压超过 {@code maxPending}，见 {@link #delay} 的准入软上限），那些"没生效的延迟/轮询"就是它造成的，
     * 而不是网络或驱动的问题。<b>P1-7 修复后该信号在真实过载时才会 > 0（此前因无界队列恒为 0）。</b></p>
     */
    public static long rejectedCount() {
        return REJECTED.get();
    }

    /**
     * 当前调度积压（在途+排队任务数，进程级）。
     *
     * <p>主动观测用（P1-7）：比 {@link #rejectedCount()} 更前置——积压持续攀升即预示将触达
     * {@code maxPending} 软上限、开始丢任务。O(1) 读取，可在监控/探针里周期采样。</p>
     */
    public static int backlog() {
        return SCHED.getQueue().size();
    }
}

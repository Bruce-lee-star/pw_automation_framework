package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link GuardedDriverCall} 默认实现：单连接专用驱动线程 + 主线程有界等待（行为零回归）。
 *
 * <p>经 {@code META-INF/services} 注册为 {@link GuardedDriverCall} 的 SPI 默认实现；
 * classpath 无显式注册时由 {@link GuardedDriverCallRegistry} 回退到本类。</p>
 *
 * <p><b>实例语义（2026-10-24 收口）—— 根治"驱动信道进程级单例"</b>：本类<b>不再持有任何 static 信道状态</b>，
 * 每条驱动线程执行器、代际、毒化/故障计数、不可用标记均为<b>实例字段</b>。信道按连接（{@code BrowserContext}）
 * 由 {@link GuardedDriverCallRegistry#channelFor} 创建与缓存，故<b>并行场景下每个用例（各自独立 Playwright 连接）
 * 拥有专属串行化线程</b>，互不干扰；某连接的超时 {@code shutdownNow()} 只取消<b>该连接</b>在途调用，
 * 不再牵连其它连接的在途调用（根除"跨用例信道毒化"）。全局查询 / 复位方法经
 * {@link GuardedDriverCallRegistry#currentChannel()}（ThreadLocal）定位"本线程所属连接的信道"。</p>
 *
 * <p><b>单线程化（T6，2026-09-29）</b>：Playwright 客户端要求对连接<b>外部串行</b>，
 * {@code Object doesn't exist} 是并发协议调用的直接产物。故所有同步驱动协议调用
 * （{@code context.route} / {@code unroute} 等）在本实例的<b>单一专用驱动线程</b>上收敛
 * （守护线程、随连接生命周期、context 关闭即由注册表清退）。</p>
 *
 * <p><b>毒化重置（T6，2026-09-29）</b>：某次调用在界内无回包（超时）→ 主线程按策略 fail-fast / 降级返回，
 * 同时<b>仅重置本实例驱动线程</b>：{@code compareAndSet} 把执行器换成新建实例、旧执行器 {@code shutdownNow()}
 * （卡死线程为 native 不可中断，仅遗弃为守护线程，随 GC 回收）。<b>绝不关闭 / 重建 BrowserContext 与 Page</b>
 * （Context 生命周期由 sessionKey 判据唯一掌管，见 FIX_PLAN I-9 / T2 裁定：路由是辅助设施，不得绑架会话）。</p>
 *
 * <p><b>范围说明</b>：本单线程化仅覆盖<b>同步</b>驱动协议调用（guarded bind/unroute）。
 * 拦截期 {@code route.fetch} / {@code page.request} 经 {@code GuardedRunner} 在调用线程就地执行
 * （串行化会回归并发请求吞吐，且其 channel 竞态已由 T2 的 claim / closeConfirmed 收敛），不在 T6 范围内。</p>
 *
 * <p><b>2026-09-29 全新评审（P0）—— 上文"毒化重置"段落中"换线程即自愈"已被取代</b>：{@code compareAndSet} 换新建实例的前提是
 * <b>在途调用确实结束了</b>。Native 调用不可中断时，换线程只会在同一连接上留下<b>第二个消息泵</b>，回包互相错取，
 * 实测新线程同样拿不到 ack（{@code reset#=2} 之后仍卡满 30s、用例依旧失败）。现行处置见 {@link #handleTimeout}：
 * 先摘后判，<b>未确认收尾则绝不新建线程</b>，复位交由会话层换 {@code Playwright} 实例（对外 API 里唯一"换连接"手段）。</p>
 */
public final class GuardedDriverCallImpl implements GuardedDriverCall {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardedDriverCallImpl.class);

    /** 驱动线程代际（毒化重建后递增，便于日志区分新旧信道）。 */
    private final AtomicInteger driverEpoch = new AtomicInteger(0);

    /** 毒化重置累计次数（可观测，对齐 AsyncPool 计数风格；供单测与指标消费）。 */
    private final AtomicLong poisonResets = new AtomicLong(0);

    /** 信道故障（超时）累计次数：单调递增，供会话层判断"本用例是否污染过信道"。 */
    private final AtomicLong channelFailures = new AtomicLong(0);

    /** 信道是否已被判定不可用（存在未收尾的在途调用）；成功调用后自动恢复。 */
    private final AtomicBoolean unusable = new AtomicBoolean(false);

    /** 超时后等待在途调用收尾的窗口（毫秒）：超过即认定"真卡死"，不再新建线程（避免第二个消息泵）。 */
    private static final long POISON_DRAIN_MS = 1_000L;

    /** 上一次按 ERROR 报过的 reset 时刻（毫秒）；同一故障窗口内的重复 reset 降级为 WARN，避免一次故障刷多条 ERROR。 */
    private final AtomicLong lastResetErrorMs = new AtomicLong(0L);

    /** 重复 reset 的降噪窗口（毫秒）：窗口内只报一次 ERROR。 */
    private static final long RESET_ERROR_DEDUP_MS = 10_000L;

    /** 本实例专属的单一专用驱动线程执行器（T6）：所有 guarded 驱动协议调用收敛于此线程。 */
    private final AtomicReference<ExecutorService> driver = new AtomicReference<>(newDriverExecutor(this));

    /** 构造：登记为当前线程的活动信道（供全局查询 / 复位方法定位"本连接的信道"）。 */
    public GuardedDriverCallImpl() {
        GuardedDriverCallRegistry.setCurrent(this);
    }

    private static ExecutorService newDriverExecutor(GuardedDriverCallImpl self) {
        int epoch = self.driverEpoch.incrementAndGet();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "route-driver-" + epoch);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        return Executors.newSingleThreadExecutor(tf);
    }

    @Override
    public <T> T guarded(String opName, long boundMs, OnTimeout policy, Callable<T> action) {
        ExecutorService driverThread = driver.get();
        Future<T> future;
        try {
            future = driverThread.submit(action);
        } catch (RejectedExecutionException rejected) {
            //  信道已被判定不可用（执行器已停）：绝不新起线程 —— 在途调用未收尾时另起线程会在同一连接上
            //  产生第二个消息泵，回包互相错取。按策略确定处置：不静默吞、也不把辅助设施的不确定性升级为
            //  Context 重建（复位由会话层换 Playwright 实例完成）。
            unusable.set(true);
            if (policy == OnTimeout.FAIL_FAST) {
                throw new IllegalStateException("[Route] driver channel unusable (an earlier call did not stop); "
                        + "call '" + opName + "' rejected — rebuild the session to reset the channel", rejected);
            }
            LOGGER.warn("[Route] driver channel unusable — call '{}' skipped (degrade, Context/Page untouched)",
                    opName);
            return null;
        }
        T result;
        try {
            result = future.get(boundMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            // 驱动对协议调用无响应：毒化重置（仅本实例驱动线程，不波及 Context），再按策略处置。
            handleTimeout(opName, boundMs);
            if (policy == OnTimeout.FAIL_FAST) {
                throw new IllegalStateException("[Route] driver call '" + opName
                        + "' 超时 " + boundMs + "ms 未收到回包（驱动忙 / 客户端共享连接被并发占用）；"
                        + "排查协议层收发请开 DEBUG=pw:channel");
            }
            LOGGER.warn("[Route] driver call '{}' exceeded {}ms — abandoning wait; "
                    + "driver thread poisoned & reset (Context/Page untouched), "
                    + "driver-side handler released on context close", opName, boundMs);
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (policy == OnTimeout.FAIL_FAST) {
                throw new IllegalStateException("[Route] driver call '" + opName
                        + "' 被中断（驱动线程重置）", interrupted);
            }
            LOGGER.warn("[Route] driver call '{}' interrupted — abandoning wait (degrade)", opName);
            return null;
        } catch (java.util.concurrent.ExecutionException executed) {
            Throwable cause = executed.getCause();
            if (policy == OnTimeout.WARN_AND_ABANDON) {
                LOGGER.warn("[Route] driver call '{}' failed: {}", opName, cause.toString());
                return null;
            }
            throw new IllegalStateException("[Route] driver call '" + opName + "' failed", cause);
        } catch (java.util.concurrent.CancellationException cancelled) {
            //  P0-1 缓解：本调用被「其它调用」的超时处置 shutdownNow() 取消（本实例内跨调用信道毒化）。
            //  任务尚未执行/被中断，并非本用例自身超时，故降级而非把别人的故障判成本用例的失败。
            Thread.currentThread().interrupt();
            if (policy == OnTimeout.FAIL_FAST) {
                LOGGER.warn("[Route] driver call '{}' was cancelled by another call's timeout reset "
                        + "(same-connection channel poisoning) — degrading this case instead of failing it", opName);
                return null;
            }
            LOGGER.warn("[Route] driver call '{}' cancelled (same-connection reset) — abandoning", opName);
            return null;
        }
        //  调用成功 ⇒ 信道可确证可用（超时标记自动恢复；信道故障计数保持单调，供会话层判断是否被污染）
        unusable.set(false);
        return result;
    }

    /**
     * 超时处置：先把在途调用从线程上摘掉，再**按"它是否真的结束"决定是否重建执行器**。
     *
     * <p><b>为什么不能无脑换线程（2026-09-29 全新评审，P0）</b>：Playwright Java 的客户端<b>没有独立读线程</b>
     * —— 谁发起协议调用，谁就负责把消息读干净（{@code Connection.sendMessage → ChannelOwner.runUntil →
     * processOneMessage}）。因此若在途调用<b>没有真正结束</b>就换一个线程，同一连接上就会<b>同时存在两个消息泵</b>，
     * 回包互相错取 ⇒ 新线程同样拿不到 ack。实测 dbb-3：{@code reset#=2} 之后新线程仍卡满 30s、用例依旧失败 ——
     * 换线程的收益没有兑现，却把共享信道弄脏并通过它传染给后续用例。</p>
     *
     * <p><b>判定与处置</b>：
     * <ol>
     *   <li>{@code shutdownNow()} 先让<b>可中断</b>的在途调用结束（interrupt）；</li>
     *   <li>在有界窗口 {@link #POISON_DRAIN_MS} 内等待其收尾：
     *     <ul>
     *       <li><b>已收尾</b> ⇒ 信道无残留泵，重建干净执行器（保留既有"自愈"语义，计数 {@code poisonResets}）；</li>
     *       <li><b>仍未收尾</b>（native 不可中断）⇒ <b>绝不新建线程</b>：标记信道
     *           {@link #isChannelUsable() 不可用}，复位交由会话语义（换 {@code Playwright} 实例 ——
     *           对外 API 里唯一"换连接"的手段，配 session 缓存恢复登录态）。</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>该处置<b>不波及 Context / Page</b>：路由是辅助设施，其信道不确定性不得绑架会话生命周期。</p>
     */
    private void handleTimeout(String opName, long boundMs) {
        channelFailures.incrementAndGet();
        ExecutorService current = driver.get();
        current.shutdownNow();
        boolean drained;
        try {
            drained = current.awaitTermination(POISON_DRAIN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            drained = false;
        }
        if (!drained) {
            unusable.set(true);
            LOGGER.error("[Route] driver call '{}' timed out {}ms and the in-flight call did NOT stop within {}ms — "
                            + "channel marked UNUSABLE and NO new driver thread is created (a second message pump on the "
                            + "same connection would steal the acks). Rebuild the session (new Playwright instance; the "
                            + "session cache restores the login) to reset the channel. Context/Page untouched.",
                    opName, boundMs, POISON_DRAIN_MS);
            return;
        }
        ExecutorService fresh = newDriverExecutor(this);
        if (driver.compareAndSet(current, fresh)) {
            long resets = poisonResets.incrementAndGet();
            LOGGER.error("[Route] route driver thread RESET after '{}' timed out {}ms — in-flight call confirmed "
                            + "stopped within {}ms, so no orphan pump is left on the connection (reset#={}, epoch -> {}, "
                            + "Context/Page untouched)",
                    opName, boundMs, POISON_DRAIN_MS, resets, driverEpoch.get());
        } else {
            fresh.shutdownNow();
            LOGGER.warn("[Route] driver call '{}' timed out {}ms but the channel was already reset by another caller",
                    opName, boundMs);
        }
    }

    /** 信道当前是否可确证可用（{@code false} ⇒ 存在未收尾的在途调用；成功调用后自动恢复）。 */
    @Override
    public boolean isChannelUsable() {
        return !unusable.get();
    }

    /** 信道故障（超时）累计次数（单调递增；跨调用比较差值即可判断"本调用是否污染过信道"）。 */
    public long channelFailureCountInstance() {
        return channelFailures.get();
    }

    /** 当前驱动线程代际（测试用：断言"没有新建线程"）。 */
    int driverEpochForTestInstance() {
        return driverEpoch.get();
    }

    /** 毒化重置累计次数（framework-internal 可观测 / 测试）。 */
    long poisonResetCountInstance() {
        return poisonResets.get();
    }

    /**
     * 显式复位信道：换一条干净驱动线程并清除"不可用"标记。
     *
     * <p><b>只在确认连接已换（或套件收尾）之后调用</b>：在途调用未收尾时单方面换线程正是本类要杜绝的
     * "第二个消息泵"。会话层换 {@code Playwright} 实例后调用它，即可让信道与新连接对齐。</p>
     */
    void resetChannelInstance() {
        resetChannelInstance(true);
    }

    /** 套件收尾专用的信道复位：与 {@link #resetChannelInstance()} 相同的换线程 + 清除不可用标记，但<b>不打 ERROR/WARN</b>。 */
    void resetChannelForTeardownInstance() {
        resetChannelInstance(false);
    }

    /** 释放本信道驱动线程（context 关闭 / 注册表清退时调用，防 daemon 线程泄漏）。 */
    public void shutdownNow() {
        ExecutorService ex = driver.get();
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    private void resetChannelInstance(boolean warn) {
        ExecutorService fresh = newDriverExecutor(this);
        ExecutorService previous = driver.getAndSet(fresh);
        if (previous != null) {
            previous.shutdownNow();
        }
        unusable.set(false);
        long now = System.currentTimeMillis();
        long last = lastResetErrorMs.get();
        if (warn) {
            if (now - last > RESET_ERROR_DEDUP_MS && lastResetErrorMs.compareAndSet(last, now)) {
                LOGGER.error("[Route] driver channel explicitly RESET (epoch -> {}); caller must have rebuilt "
                        + "the connection first", driverEpoch.get());
            } else {
                LOGGER.warn("[Route] driver channel RESET again (epoch -> {}); same failure window, already reported",
                        driverEpoch.get());
            }
        } else {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("[Route] driver channel reset for suite teardown (epoch -> {})", driverEpoch.get());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  下列 static 方法为历史调用入口（测试 / RouteEngine 全局查询）的<b>兼容桥</b>，
    //  全部委托到"当前线程所属连接的信道"（见 GuardedDriverCallRegistry.currentChannel()）。
    //  2026-10-24 起信道为实例语义，故这里只做转发，不再持有静态状态 —— 进程级单例已消除。
    // ─────────────────────────────────────────────────────────────────────────

    /** @see #channelFailureCountInstance() */
    public static long channelFailureCount() {
        GuardedDriverCallImpl c = GuardedDriverCallRegistry.currentChannel();
        return c == null ? 0L : c.channelFailureCountInstance();
    }

    /** @see #driverEpochForTestInstance() */
    public static int driverEpochForTest() {
        GuardedDriverCallImpl c = GuardedDriverCallRegistry.currentChannel();
        return c == null ? 0 : c.driverEpochForTestInstance();
    }

    /** @see #poisonResetCountInstance() */
    public static long poisonResetCount() {
        GuardedDriverCallImpl c = GuardedDriverCallRegistry.currentChannel();
        return c == null ? 0L : c.poisonResetCountInstance();
    }

    /** @see #resetChannelInstance() */
    public static void resetChannel() {
        GuardedDriverCallImpl c = GuardedDriverCallRegistry.currentChannel();
        if (c != null) {
            c.resetChannelInstance();
        }
    }

    /** @see #resetChannelForTeardownInstance() */
    public static void resetChannelForTeardown() {
        GuardedDriverCallImpl c = GuardedDriverCallRegistry.currentChannel();
        if (c != null) {
            c.resetChannelForTeardownInstance();
        }
    }
}

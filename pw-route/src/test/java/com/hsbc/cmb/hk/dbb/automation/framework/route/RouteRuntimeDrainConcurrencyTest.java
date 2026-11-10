package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * {@link RouteRuntimeImpl#drainMainThreadActions()} 的<b>真实并发</b>契约：
 * 同一 runtime 的协议动作（resume / fulfill / body 物化）任意时刻只能由一个线程执行，
 * 且并发到来时动作既不丢失、也不重复、更不并发写 Connection。
 *
 * <p>覆盖两条真实竞态：业务线程高频检查点 vs 业务线程；业务线程 vs 收尾 {@code close()}。</p>
 */
public class RouteRuntimeDrainConcurrencyTest {

    private BrowserContext ctx;
    private RouteRuntime runtime;

    @After
    public void tearDown() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx, true);
        }
    }

    @Test
    public void concurrentDrainNeverRunsProtocolActionsInParallel() throws Exception {
        RuntimeFixture fixture = newRuntime(24);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        Thread a = drainWorker(start, runtime, unexpected);
        Thread b = drainWorker(start, runtime, unexpected);
        a.start();
        b.start();
        start.countDown();
        a.join(TimeUnit.SECONDS.toMillis(10));
        b.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
        assertNull("drain 不得抛异常", unexpected.get());
        assertEquals("并发 drain 不得丢失协议动作", 24, fixture.executed.get());
        assertEquals("协议动作必须串行执行", 1, fixture.maxActive.get());
    }

    @Test
    public void closeAndBusinessDrainDoNotDoubleExecuteOrThrow() throws Exception {
        RuntimeFixture fixture = newRuntime(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        Thread business = drainWorker(start, runtime, unexpected);
        Thread closer = new Thread(() -> {
            await(start);
            try {
                runtime.close();
            } catch (Throwable t) {
                unexpected.compareAndSet(null, t);
            }
        }, "close-bounded-context-close");
        business.start();
        closer.start();
        start.countDown();
        business.join(TimeUnit.SECONDS.toMillis(10));
        closer.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(business.isAlive());
        assertFalse(closer.isAlive());
        assertNull("收尾与业务 drain 不得抛异常", unexpected.get());
        assertEquals("最后一批 DELAY 动作必须恰好执行一次", 12, fixture.executed.get());
        assertEquals("close 与业务 drain 也不得并发写 Connection", 1, fixture.maxActive.get());
    }

    private RuntimeFixture newRuntime(int actions) {
        ctx = mock(BrowserContext.class);
        runtime = RouteEngine.runtimeOf(ctx);
        RuntimeFixture fixture = new RuntimeFixture();
        for (int i = 0; i < actions; i++) {
            runtime.enqueueMainAction(() -> {
                int now = fixture.active.incrementAndGet();
                fixture.maxActive.accumulateAndGet(now, Math::max);
                // 自旋放大重叠窗口（避免 Thread.sleep 触发框架门禁）
                long deadline = System.nanoTime() + 1_000_000L;
                while (System.nanoTime() < deadline) {
                    Thread.yield();
                }
                fixture.executed.incrementAndGet();
                fixture.active.decrementAndGet();
            });
        }
        return fixture;
    }

    private static Thread drainWorker(CountDownLatch start, RouteRuntime rt,
                                      AtomicReference<Throwable> unexpected) {
        return new Thread(() -> {
            await(start);
            try {
                rt.drainMainThreadActions();
            } catch (Throwable t) {
                unexpected.compareAndSet(null, t);
            }
        });
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RuntimeFixture {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger executed = new AtomicInteger();
    }
}

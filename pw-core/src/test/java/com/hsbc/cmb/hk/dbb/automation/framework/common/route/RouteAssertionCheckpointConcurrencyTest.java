package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 检查点的<b>真实并发</b>契约（非单线程模拟）：失败消费必须按 context 归属，
 * 同一批失败只能有一个线程判红，两个并行 case 互不串味。
 *
 * <p>本类不使用任何 ThreadLocal 状态，故用例同时验证「生命周期事件是否跨线程派发」
 * 不再影响隔离性。</p>
 */
public class RouteAssertionCheckpointConcurrencyTest {

    private static final Object CONTEXT_A = new Object();
    private static final Object CONTEXT_B = new Object();

    @After
    public void tearDown() {
        RouteAssertionRegistry.clear();
    }

    @Test
    public void twoThreadsOnSameContextOnlyOneThrows() throws Exception {
        RouteAssertionRegistry.register(new OnceProbe());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger thrown = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        Thread t1 = worker(start, CONTEXT_A, thrown, unexpected);
        Thread t2 = worker(start, CONTEXT_A, thrown, unexpected);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(TimeUnit.SECONDS.toMillis(5));
        t2.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse("线程不得挂起", t1.isAlive());
        assertFalse("线程不得挂起", t2.isAlive());
        assertEquals("同一批失败只能有一个线程判红", 1, thrown.get());
        assertEquals("不得出现 AssertionError 之外的异常", 0, unexpected.get());
    }

    @Test
    public void twoContextsInParallelNeverCrossConsume() throws Exception {
        PerContextProbe probe = new PerContextProbe();
        probe.register(CONTEXT_A, "/api/a");
        probe.register(CONTEXT_B, "/api/b");
        RouteAssertionRegistry.register(probe);

        CountDownLatch start = new CountDownLatch(1);
        List<String> messages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicInteger unexpected = new AtomicInteger();
        Thread a = worker(start, CONTEXT_A, messages, unexpected);
        Thread b = worker(start, CONTEXT_B, messages, unexpected);
        a.start();
        b.start();
        start.countDown();
        a.join(TimeUnit.SECONDS.toMillis(5));
        b.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
        assertEquals(0, unexpected.get());
        assertEquals("两个 case 都应由自己的失败判红", 2, messages.size());
        assertTrue(messages.stream().anyMatch(m -> m.contains("/api/a")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("/api/b")));
    }

    private static Thread worker(CountDownLatch start, Object context,
                                 AtomicInteger thrown, AtomicInteger unexpected) {
        return new Thread(() -> {
            await(start);
            try {
                RouteAssertionCheckpoint.checkNow(context);
            } catch (AssertionError expected) {
                thrown.incrementAndGet();
            } catch (Throwable t) {
                unexpected.incrementAndGet();
            }
        });
    }

    private static Thread worker(CountDownLatch start, Object context,
                                 List<String> messages, AtomicInteger unexpected) {
        return new Thread(() -> {
            await(start);
            try {
                RouteAssertionCheckpoint.checkNow(context);
                messages.add("NO_FAILURE");
            } catch (AssertionError expected) {
                messages.add(String.valueOf(expected.getMessage()));
            } catch (Throwable t) {
                unexpected.incrementAndGet();
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

    /** 全局只有一条失败，谁先取走谁判红。 */
    private static final class OnceProbe implements RouteAssertionProbe {
        private final AtomicInteger remaining = new AtomicInteger(1);

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures() {
            return List.of();
        }

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures(Object context) {
            return remaining.getAndDecrement() == 1
                    ? List.of(failure("/api/once")) : List.of();
        }
    }

    /** 每个 context 各有一条自己的失败，按 context 分桶消费。 */
    private static final class PerContextProbe implements RouteAssertionProbe {
        private final Map<Object, String> patterns = new ConcurrentHashMap<>();
        private final Set<Object> consumed = ConcurrentHashMap.newKeySet();

        void register(Object context, String pattern) {
            patterns.put(context, pattern);
        }

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures() {
            return List.of();
        }

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures(Object context) {
            String pattern = patterns.get(context);
            return pattern != null && consumed.add(context)
                    ? List.of(failure(pattern)) : List.of();
        }
    }

    private static RouteAssertionFailure failure(String pattern) {
        return new RouteAssertionFailure(pattern, "POST", "https://host" + pattern,
                200, 500, List.of(), false, 60_000L, "", "", "", "");
    }
}

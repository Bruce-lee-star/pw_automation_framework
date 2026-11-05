package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * 通道会话所有权契约（v3.12，P0 第 4 项并发所有权）：验证默认 {@link SessionOwnership#locked()}
 * 实现的严格串行化与可重入性，不依赖真实浏览器（确定性）。
 */
public class SessionOwnershipTest {

    @Test
    public void concurrentRunExclusiveIsStrictlySerialized() throws Exception {
        SessionOwnership owner = SessionOwnership.locked();
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        int nThreads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < nThreads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                owner.runExclusive(() -> {
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.accumulateAndGet(c, Math::max);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    concurrent.decrementAndGet();
                    completed.incrementAndGet();
                    return null;
                });
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(nThreads, completed.get());
        // 严格串行：任何时刻临界区内至多一个线程
        assertEquals(1, maxConcurrent.get());
    }

    @Test
    public void nestedRunExclusiveOnSameOwnerReentersSafely() {
        SessionOwnership owner = SessionOwnership.locked();
        AtomicInteger depth = new AtomicInteger(0);
        int result = owner.runExclusive(() ->
                owner.runExclusive(() -> {
                    depth.incrementAndGet();
                    return 42;
                }));
        assertEquals(42, result);
        assertEquals(1, depth.get());
    }
}

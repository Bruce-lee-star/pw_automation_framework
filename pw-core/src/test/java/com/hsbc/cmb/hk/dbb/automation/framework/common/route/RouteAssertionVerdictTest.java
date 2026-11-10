package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 「晚到 MONITOR 失败」判决寄存器的消费式幂等与线程隔离契约。
 *
 * <p>本类只在「TestCaseFinished → fireTestFinished」这段同线程同步栈里传递判决摘要，
 * 不暂存失败数据（权威数据仍在 BrowserContext 队列），因此并行 case 之间必须零串味。</p>
 */
public class RouteAssertionVerdictTest {

    @Before
    public void setUp() {
        RouteAssertionVerdict.clear();
    }

    @After
    public void tearDown() {
        RouteAssertionVerdict.clear();
    }

    @Test
    public void noRecordReturnsNull() {
        assertNull(RouteAssertionVerdict.peek());
        assertNull(RouteAssertionVerdict.takeAndClear());
    }

    @Test
    public void recordMergesReportsAndPeekIsStable() {
        RouteAssertionVerdict.record("[status=500] POST /api/a");
        RouteAssertionVerdict.record("[timeout] GET /api/b");

        AssertionError first = RouteAssertionVerdict.peek();
        assertNotNull(first);
        assertTrue(first.getMessage().contains("/api/a"));
        assertTrue(first.getMessage().contains("/api/b"));
        assertSame("peek 只读，同一 case 必须返回同一判决实例", first, RouteAssertionVerdict.peek());
    }

    @Test
    public void takeAndClearConsumesExactlyOnce() {
        RouteAssertionVerdict.record("[status=500] POST /api/a");

        AssertionError taken = RouteAssertionVerdict.takeAndClear();
        assertNotNull(taken);
        assertTrue(taken.getMessage().contains("/api/a"));
        assertNull("判决必须消费式，绝不能重复投影成两条 failure", RouteAssertionVerdict.peek());
        assertNull(RouteAssertionVerdict.takeAndClear());
    }

    @Test
    public void blankReportIsIgnored() {
        RouteAssertionVerdict.record(null);
        RouteAssertionVerdict.record("   ");
        assertNull(RouteAssertionVerdict.peek());
    }

    @Test
    public void parallelCasesNeverShareAVerdict() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<AssertionError> verdictA = new AtomicReference<>();
        AtomicReference<AssertionError> verdictB = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread a = new Thread(() -> runParallelCase("case-A", bothStarted, release, verdictA, error));
        Thread b = new Thread(() -> runParallelCase("case-B", bothStarted, release, verdictB, error));
        a.start();
        b.start();
        assertTrue("并行线程未就绪", bothStarted.await(5, TimeUnit.SECONDS));
        release.countDown();
        a.join(5000);
        b.join(5000);

        assertNull("并行执行不得抛异常", error.get());
        assertNotNull(verdictA.get());
        assertNotNull(verdictB.get());
        assertTrue("A case 判决被串味", verdictA.get().getMessage().contains("case-A"));
        assertTrue("B case 判决被串味", verdictB.get().getMessage().contains("case-B"));
        assertEquals("A case 不得看到 B case 的失败", false,
                verdictA.get().getMessage().contains("case-B"));
        assertEquals("B case 不得看到 A case 的失败", false,
                verdictB.get().getMessage().contains("case-A"));
        assertNull("主线程不得看到子线程的判决", RouteAssertionVerdict.peek());
    }

    private static void runParallelCase(String marker, CountDownLatch bothStarted,
                                        CountDownLatch release, AtomicReference<AssertionError> out,
                                        AtomicReference<Throwable> error) {
        try {
            RouteAssertionVerdict.clear();
            bothStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            RouteAssertionVerdict.record("[status=500] POST /api/" + marker + " (pattern=" + marker + ")");
            out.set(RouteAssertionVerdict.takeAndClear());
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        } finally {
            RouteAssertionVerdict.clear();
        }
    }
}

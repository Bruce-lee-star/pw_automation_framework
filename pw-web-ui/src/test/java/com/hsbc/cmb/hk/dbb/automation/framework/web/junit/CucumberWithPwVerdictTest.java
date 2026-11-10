package com.hsbc.cmb.hk.dbb.automation.framework.web.junit;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionVerdict;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 「不加 hook」把晚到的 MONITOR 断言失败投影为 case 判红的 Runner 层契约。
 *
 * <p>模拟真实时序：{@code FeatureRunner.runChild} 先 {@code fireTestStarted}，
 * 再同步跑完整条 case（其间 Cucumber 派发 {@code TestCaseFinished}、框架在场景收尾
 * 登记判决），最后在 finally 里 {@code fireTestFinished}。测试不启动浏览器、不加载
 * feature，只验证 JUnit 通知层的投影行为。</p>
 */
public class CucumberWithPwVerdictTest {

    private static final Description CASE_A = Description.createTestDescription("feature", "scenario A");
    private static final Description CASE_B = Description.createTestDescription("feature", "scenario B");

    private RecordingNotifier target;
    private CucumberWithPw.MonitorVerdictNotifier notifier;

    @Before
    public void setUp() {
        RouteAssertionVerdict.clear();
        target = new RecordingNotifier();
        notifier = new CucumberWithPw.MonitorVerdictNotifier(target);
    }

    @After
    public void tearDown() {
        RouteAssertionVerdict.clear();
    }

    /**
     * 核心回归：最后一步内完全没有框架业务操作，MONITOR 响应直到场景收尾才结算——
     * Cucumber 已把 Result 定成 PASSED，Runner 层必须补发 failure 让 case 判红。
     */
    @Test
    public void lateMonitorFailureFailsCaseEvenWhenLastStepHasNoFrameworkOperation() {
        notifier.fireTestStarted(CASE_A);
        RouteAssertionVerdict.record("[status=500, expect=200] POST https://host/api/x (pattern=api/x)");
        notifier.fireTestFinished(CASE_A);

        assertEquals("晚到失败必须投影为一条 JUnit failure", 1, target.failures.size());
        Failure failure = target.failures.get(0);
        assertEquals("failure 必须归属当前 case", CASE_A, failure.getDescription());
        assertTrue("failure 必须保留 MONITOR 断言明细",
                failure.getMessage() != null && failure.getMessage().contains("api/x"));
        assertEquals(1, target.finished.size());
        assertTrue("fireTestFailure 必须早于 fireTestFinished",
                target.events.indexOf("failure:" + CASE_A.getDisplayName())
                        < target.events.indexOf("finished:" + CASE_A.getDisplayName()));
        assertNull("判决必须被消费，不得遗留到下一条 case", RouteAssertionVerdict.peek());
    }

    @Test
    public void caseAlreadyFailedByCucumberIsNotProjectedTwice() {
        notifier.fireTestStarted(CASE_A);
        notifier.fireTestFailure(new Failure(CASE_A, new IllegalStateException("business step failed")));
        RouteAssertionVerdict.record("[status=500] POST /api/x");
        notifier.fireTestFinished(CASE_A);

        assertEquals("已判红的 case 不得重复补发 failure", 1, target.failures.size());
        assertTrue("原业务失败必须保留",
                target.failures.get(0).getMessage().contains("business step failed"));
        assertNull("即便未补发，判决也必须被清掉", RouteAssertionVerdict.peek());
    }

    @Test
    public void verdictNeverLeaksIntoNextCase() {
        notifier.fireTestStarted(CASE_A);
        RouteAssertionVerdict.record("[status=500] POST /api/x");
        notifier.fireTestFinished(CASE_A);

        notifier.fireTestStarted(CASE_B);
        notifier.fireTestFinished(CASE_B);

        assertEquals("只有 A case 判红", 1, target.failures.size());
        assertEquals(CASE_A, target.failures.get(0).getDescription());
        assertEquals(2, target.finished.size());
    }

    @Test
    public void noVerdictLeavesCaseGreen() {
        notifier.fireTestStarted(CASE_A);
        notifier.fireTestFinished(CASE_A);

        assertTrue("无 MONITOR 失败时绝不误判红", target.failures.isEmpty());
        assertEquals(1, target.finished.size());
    }

    /** 包装 RunNotifier 必须整体委托，否则 suite/run 级通知会打到包装对象的空监听器列表。 */
    @Test
    public void allNotificationsAreDelegated() {
        notifier.fireTestRunStarted(Description.createSuiteDescription("run"));
        notifier.fireTestSuiteStarted(Description.createSuiteDescription("suite"));
        notifier.fireTestIgnored(CASE_B);
        notifier.fireTestAssumptionFailed(new Failure(CASE_B, new AssertionError("assumption")));
        notifier.fireTestSuiteFinished(Description.createSuiteDescription("suite"));
        notifier.fireTestRunFinished(new Result());

        assertTrue(target.events.contains("runStarted"));
        assertTrue(target.events.contains("suiteStarted"));
        assertTrue(target.events.contains("ignored"));
        assertTrue(target.events.contains("assumption"));
        assertTrue(target.events.contains("suiteFinished"));
        assertTrue(target.events.contains("runFinished"));
    }

    @Test
    public void parallelCasesOnlyFailTheirOwnDescription() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread a = new Thread(() -> runParallelCase(CASE_A, "case-A", bothStarted, release, error));
        Thread b = new Thread(() -> runParallelCase(CASE_B, "case-B", bothStarted, release, error));
        a.start();
        b.start();
        assertTrue("并行线程未就绪", bothStarted.await(5, TimeUnit.SECONDS));
        release.countDown();
        a.join(5000);
        b.join(5000);

        assertNull("并行执行不得抛异常", error.get());
        assertEquals("两条并行 case 各判红一次", 2, target.failures.size());
        for (Failure failure : target.failures) {
            String message = failure.getMessage();
            if (CASE_A.equals(failure.getDescription())) {
                assertTrue("A case 的 failure 必须只含自己的 MONITOR 明细",
                        message.contains("case-A") && !message.contains("case-B"));
            } else {
                assertEquals("failure 必须归属当前 case", CASE_B, failure.getDescription());
                assertTrue("B case 的 failure 必须只含自己的 MONITOR 明细",
                        message.contains("case-B") && !message.contains("case-A"));
            }
        }
        assertNull("主线程不得残留子线程判决", RouteAssertionVerdict.peek());
    }

    private void runParallelCase(Description description, String marker, CountDownLatch bothStarted,
                                 CountDownLatch release, AtomicReference<Throwable> error) {
        try {
            notifier.fireTestStarted(description);
            bothStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            RouteAssertionVerdict.record("[status=500] POST /api/" + marker + " (pattern=" + marker + ")");
            notifier.fireTestFinished(description);
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        }
    }

    /** 只记录事件、不依赖 JUnit 监听器列表，用于断言包装层的委托与投影顺序。 */
    private static final class RecordingNotifier extends RunNotifier {

        private final List<Failure> failures = Collections.synchronizedList(new ArrayList<>());
        private final List<Description> finished = Collections.synchronizedList(new ArrayList<>());
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void fireTestRunStarted(Description description) {
            events.add("runStarted");
        }

        @Override
        public void fireTestRunFinished(Result result) {
            events.add("runFinished");
        }

        @Override
        public void fireTestSuiteStarted(Description description) {
            events.add("suiteStarted");
        }

        @Override
        public void fireTestSuiteFinished(Description description) {
            events.add("suiteFinished");
        }

        @Override
        public void fireTestStarted(Description description) {
            events.add("started:" + description.getDisplayName());
        }

        @Override
        public void fireTestFailure(Failure failure) {
            failures.add(failure);
            events.add("failure:" + failure.getDescription().getDisplayName());
        }

        @Override
        public void fireTestAssumptionFailed(Failure failure) {
            events.add("assumption");
        }

        @Override
        public void fireTestIgnored(Description description) {
            events.add("ignored");
        }

        @Override
        public void fireTestFinished(Description description) {
            finished.add(description);
            events.add("finished:" + description.getDisplayName());
        }
    }
}

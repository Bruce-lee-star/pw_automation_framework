package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.step.PwStepFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportBus 并发回归（R1 真 bug 的门禁）：N 线程 × M 用例并发打事件，
 * 断言 session 数正确、无串台、无异常、证据归属正确。
 *
 * <p>同时顺带覆盖：实时 JSON 落盘（CaseFinished）、finishRun 全量出口、
 * 跨线程显式句柄（R2）、kill-switch（R4）。
 */
class BusConcurrencyTest {

    private static final int THREADS = 8;
    private static final int CASES_PER_THREAD = 5;

    @TempDir
    Path tempDir;

    private ReportBus bus;

    @BeforeEach
    void setUp() {
        bus = new ReportBus(PwReportsConfig.builder().outputDir(tempDir).build());
        bus.startRun();
    }

    @AfterEach
    void tearDown() {
        bus.finishRun();
    }

    @Test
    @DisplayName("并发 startCase/step/finishCase：session 数正确、无串台")
    void concurrentCasesDoNotCrossTalk() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger unexpected = new AtomicInteger();

        for (int t = 0; t < THREADS; t++) {
            final int threadNo = t;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                    for (int c = 0; c < CASES_PER_THREAD; c++) {
                        String baseId = "feat-" + threadNo + "-" + (c * 10) + "-case";
                        StepHandle caseHandle = bus.startCase(baseId, "feat", "case", List.of("tag" + threadNo));
                        // 每个用例 3 个根步骤，第二个下挂 2 个子步骤
                        for (int s = 1; s <= 3; s++) {
                            StepHandle step = bus.stepStarted("t" + threadNo + "-s" + s);
                            if (s == 2) {
                                bus.stepStarted("t" + threadNo + "-child-1");
                                bus.stepFinished(bus.captureHandle(), TestResult.SUCCESS, null);
                                bus.stepStarted("t" + threadNo + "-child-2");
                                bus.stepFinished(bus.captureHandle(), TestResult.SUCCESS, null);
                            }
                            bus.stepFinished(step, TestResult.SUCCESS, null);
                        }
                        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 42);
                    }
                } catch (Throwable e) {
                    unexpected.incrementAndGet();
                    throw new RuntimeException(e);
                }
            }, pool));
        }
        start.countDown();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(unexpected).hasValue(0);
        assertThat(bus.sessionCount()).isEqualTo(THREADS * CASES_PER_THREAD);

        List<TestOutcome> outcomes = bus.outcomeSnapshots();
        assertThat(outcomes).hasSize(THREADS * CASES_PER_THREAD);
        for (TestOutcome o : outcomes) {
            // 每个用例 3 个根步骤，第二个下挂 2 个子步骤
            assertThat(o.getTestSteps()).hasSize(3);
            String ownerThread = o.getOutcomeId().split("-")[1]; // feat-N-…
            boolean sawOwnS2 = false;
            for (var step : o.getTestSteps()) {
                // 串台检测 1：步骤描述必须属于本用例的线程
                assertThat(step.getDescription()).startsWith("t" + ownerThread + "-");
                // 串台检测 2：t{thread}-s2 必须且只能出现在本线程用例里，且带 2 个子步骤
                if (step.getDescription().equals("t" + ownerThread + "-s2")) {
                    assertThat(step.getChildren()).hasSize(2);
                    sawOwnS2 = true;
                }
            }
            assertThat(sawOwnS2).as("t%s-s2 missing in %s", ownerThread, o.getOutcomeId()).isTrue();
            assertThat(o.getResult()).isEqualTo(TestResult.SUCCESS);
            assertThat(o.getDuration()).isEqualTo(42);
        }
        assertThat(unexpected).hasValue(0);
    }

    @Test
    @DisplayName("跨线程显式句柄挂证据：归属正确步骤，零参 API 在异步线程不可用")
    void crossThreadEvidenceViaHandle() throws Exception {
        StepHandle caseHandle = bus.startCase("async-1-case", "feat", "async case", List.of());
        StepHandle step = bus.stepStarted("outer");
        StepHandle captured = bus.captureHandle();

        // 异步前 capture，回调内显式句柄挂载
        CompletableFuture.runAsync(() -> bus.attachEvidence(captured, "async evidence line")).get(5, TimeUnit.SECONDS);

        // 异步线程调零参版本：解析不到当前句柄 → 静默丢弃（不抛、不串到别的用例）
        StepHandle other = CompletableFuture.supplyAsync(bus::captureHandle).get(5, TimeUnit.SECONDS);
        assertThat(other.isNoop()).isTrue();

        bus.stepFinished(step, TestResult.SUCCESS, null);
        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 10);

        TestOutcome outcome = bus.outcomeSnapshots().get(0);
        assertThat(outcome.getTestSteps()).hasSize(1);
        assertThat(outcome.getTestSteps().get(0).getEvidence()).containsExactly("async evidence line");
        // 用例级无证据泄漏
        assertThat(outcome.getEvidence()).isEmpty();
    }

    @Test
    @DisplayName("finishRun 落全量出口：per-case JSON + summary + index + assets")
    void finishRunWritesAllArtifacts() throws Exception {
        StepHandle h1 = bus.startCase("art-1-a", "feat", "case a", List.of("smoke"));
        bus.stepFinished(bus.stepStarted("s1"), TestResult.SUCCESS, null);
        bus.finishCase(h1, TestResult.SUCCESS, null, 5);
        StepHandle h2 = bus.startCase("art-2-b", "feat", "case b", List.of());
        bus.stepFinished(bus.stepStarted("s1"), TestResult.FAILURE, new AssertionError("nope"));
        bus.finishCase(h2, TestResult.FAILURE, new AssertionError("nope"), 7);

        bus.finishRun();

        assertThat(tempDir.resolve("art-1-a.json")).exists();
        assertThat(tempDir.resolve("art-2-b.json")).exists();
        assertThat(tempDir.resolve("serenity-summary.json")).exists();
        assertThat(tempDir.resolve("index.html")).exists();
        assertThat(tempDir.resolve("assets/report.css")).exists();
        assertThat(tempDir.resolve("art-2-b.html")).exists();

        // 失败信息已脱敏落 JSON（堆栈/消息口径）
        String json = java.nio.file.Files.readString(tempDir.resolve("art-2-b.json"));
        assertThat(json).contains("\"result\" : \"FAILURE\"");
        assertThat(json).contains("testFailureCause");
        assertThat(bus.sinkErrorCount()).isZero();
    }

    @Test
    @DisplayName("kill-switch：enabled=false 时全部 API 静默、finishRun 无副作用")
    void killSwitchSilencesEverything() {
        ReportBus disabled = new ReportBus(PwReportsConfig.builder()
                .outputDir(tempDir.resolve("disabled"))
                .enabled(false)
                .build());
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.startCase("x-1-y", "f", "s", List.of()).isNoop()).isTrue();
        assertThat(disabled.stepStarted("s")).isEqualTo(StepHandle.NOOP);
        disabled.attachEvidence("should not be recorded");
        disabled.attachEvidence(StepHandle.NOOP, "noop handle");
        disabled.stepFinished(StepHandle.NOOP, TestResult.SUCCESS, null);
        disabled.recordApiOperation("GET /x?token=secret");
        disabled.finishCase(StepHandle.NOOP, TestResult.SUCCESS, null, 1);
        disabled.finishRun();
        assertThat(disabled.sessionCount()).isZero();
        assertThat(disabled.outcomeSnapshots()).isEmpty();
        assertThat(java.nio.file.Files.exists(tempDir.resolve("disabled"))).isFalse();
    }

    @Test
    @DisplayName("冲突 baseId 自动加后缀（outline 同名 / 重试重发）")
    void conflictingIdsGetSuffix() {
        StepHandle h1 = bus.startCase("dup-10-case", "f", "case", List.of());
        StepHandle h2 = bus.startCase("dup-10-case", "f", "case", List.of());
        StepHandle h3 = bus.startCase("dup-10-case", "f", "case", List.of());
        assertThat(h1.outcomeId()).isEqualTo("dup-10-case");
        assertThat(h2.outcomeId()).isEqualTo("dup-10-case-2");
        assertThat(h3.outcomeId()).isEqualTo("dup-10-case-3");
        assertThat(bus.sessionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("finishRun 幂等：二次调用不重复产出")
    void finishRunIdempotent() {
        StepHandle h = bus.startCase("idem-1-a", "f", "s", List.of());
        bus.finishCase(h, TestResult.SUCCESS, null, 1);
        bus.finishRun();
        String first = java.nio.file.Files.exists(tempDir.resolve("idem-1-a.json"))
                ? read(tempDir.resolve("idem-1-a.json")) : "";
        bus.finishRun(); // 双触发（ObjectFactory.stop + TestRunFinished）
        assertThat(read(tempDir.resolve("idem-1-a.json"))).isEqualTo(first);
    }

    private static String read(Path p) {
        try {
            return java.nio.file.Files.readString(p);
        } catch (Exception e) {
            return "<missing>";
        }
    }

    /**
     * per-thread 共享步骤库缓存隔离（用户「整个架构 per-thread 线程安全」要求的专项门禁）：
     * JVM-global 缓存会让 {@code @Steps(shared=true)} 实例跨线程共享 → 跨场景状态泄漏 + 竞态。
     * 改为 ThreadLocal 后，不同线程拿到不同共享实例，同线程内仍保持共享语义。
     */
    @Test
    @DisplayName("共享步骤库缓存 per-thread：同线程共享、跨线程隔离（杜绝 JVM-global 跨场景状态泄漏）")
    void sharedStepLibraryCacheIsPerThreadIsolated() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Object[] t0a = new Object[1], t0b = new Object[1], t1a = new Object[1], t1b = new Object[1];
        CompletableFuture<Void> f0 = CompletableFuture.runAsync(() -> {
            t0a[0] = PwStepFactory.sharedProxyFor(SharedFixtureSteps.class);
            t0b[0] = PwStepFactory.sharedProxyFor(SharedFixtureSteps.class); // 同线程二次 → 应相同
        }, pool);
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            t1a[0] = PwStepFactory.sharedProxyFor(SharedFixtureSteps.class);
            t1b[0] = PwStepFactory.sharedProxyFor(SharedFixtureSteps.class);
        }, pool);
        CompletableFuture.allOf(f0, f1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(t0a[0]).isNotNull().isSameAs(t0b[0]); // 同线程共享语义保持
        assertThat(t1a[0]).isNotNull().isSameAs(t1b[0]);
        assertThat(t0a[0]).isNotSameAs(t1a[0]);           // 跨线程隔离（JVM-global 会失败）
    }

    /** per-thread 共享缓存隔离测试的普通步骤库 fixture（可被 ByteBuddy 子类化）。 */
    public static class SharedFixtureSteps {
        public void doSomething() {
        }
    }
}

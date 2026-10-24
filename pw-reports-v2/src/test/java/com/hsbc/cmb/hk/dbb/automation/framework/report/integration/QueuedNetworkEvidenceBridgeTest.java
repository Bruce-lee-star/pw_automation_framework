package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨线程网络证据桥（Tier2 #6 真实接线形态）：route 内核在 <b>Playwright 事件线程</b>入队、
 * 测试主线程 flush 归位到当时的步骤。
 *
 * <p>本门禁固化三条关键不变量：<b>①跨线程归位</b>（事件线程的 {@code captureHandle()} 是 NOOP，
 * 证据若不靠句柄回传就会静默丢失）、<b>②队列溢出丢最旧</b>（防积压）、<b>③无场景静默降级</b>
 * （纯单测环境不抛错不积压）。</p>
 */
class QueuedNetworkEvidenceBridgeTest {

    @TempDir
    Path dir;

    private ReportBus bus;

    @BeforeEach
    void setUp() {
        bus = new ReportBus(PwReportsConfig.builder().outputDir(dir).build());
        bus.startRun();
    }

    @AfterEach
    void tearDown() {
        bus.finishRun();
    }

    @Test
    @DisplayName("事件线程入队 + 主线程 flush：证据归位到 capture 时的那个步骤（跨线程不丢）")
    void flushOnMainThreadLandsOnCapturedStep() throws Exception {
        StepHandle caseHandle = bus.startCase("net-q-1", "features/api", "route evidence", List.of());
        StepHandle step = bus.stepStarted("submit form");

        QueuedNetworkEvidenceBridge bridge = new QueuedNetworkEvidenceBridge(bus);
        // 主线程在进入异步前捕获句柄
        StepHandle captured = bridge.capture();

        // 模拟 Playwright 事件线程入队（另一线程，与主线程无 ThreadLocal 关联）
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread eventThread = new Thread(() -> {
            try {
                for (int i = 1; i <= 3; i++) {
                    bridge.record("MOCK", "https://api.example.com/order/" + i + "?token=SECRET_" + i,
                            "matched stub #" + i);
                }
            } catch (Throwable t) {
                error.set(t);
            }
        }, "pw-playwright-event");
        eventThread.start();
        eventThread.join(5_000);
        assertThat(error.get()).isNull();
        assertThat(bridge.pendingCount()).isEqualTo(3);

        // 事件线程自己 flush 是无效的（那是 NOOP 句柄）——必须主线程带 captured flush
        bus.stepFinished(step, TestResult.SUCCESS, null);
        bridge.flush(captured);
        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 5);
        bus.finishRun();

        TestOutcome o = JsonWriter.mapper()
                .readValue(dir.resolve("net-q-1").resolve("net-q-1.json").toFile(), TestOutcome.class);
        List<String> evidence = o.getTestSteps().stream()
                .flatMap(s -> s.getEvidence().stream()).toList();
        assertThat(evidence).hasSize(3);
        assertThat(evidence).allSatisfy(line -> assertThat(line).contains("MOCK").contains("api.example.com"));
        // 敏感 query 必须被总线 ScrubGate 遮蔽
        assertThat(evidence).noneSatisfy(line -> assertThat(line).contains("SECRET_"));
        // 同时进用例级 API 操作列表（双口径）
        //  captured 为 step 级句柄 → API 操作归位到对应步骤；用例级列表为空（仅无 stepId 的兜底才落用例级）
        assertThat(o.getApiOperations()).isEmpty();
        assertThat(o.getTestSteps().stream().flatMap(s -> s.getApiOperations().stream()).toList()).hasSize(3);
        assertThat(bridge.pendingCount()).isZero();
    }

    @Test
    @DisplayName("队列溢出丢最旧并保留最新（防极端场景积压），丢弃数在 flush 时汇总上报")
    void queueOverflowDropsOldest() throws Exception {
        StepHandle caseHandle = bus.startCase("net-q-2", "features/api", "overflow", List.of());
        StepHandle step = bus.stepStarted("step");
        QueuedNetworkEvidenceBridge bridge = new QueuedNetworkEvidenceBridge(bus, 3);
        StepHandle captured = bridge.capture();

        for (int i = 1; i <= 6; i++) {
            bridge.record("MONITOR", "https://api.example.com/" + i, "detail-" + i);
        }
        assertThat(bridge.pendingCount()).isEqualTo(3);

        bridge.flush(captured);
        bus.stepFinished(step, TestResult.SUCCESS, null);
        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 1);
        bus.finishRun();

        TestOutcome o = JsonWriter.mapper()
                .readValue(dir.resolve("net-q-2").resolve("net-q-2.json").toFile(), TestOutcome.class);
        List<String> evidence = o.getTestSteps().stream()
                .flatMap(s -> s.getEvidence().stream()).toList();
        assertThat(evidence).hasSize(3);
        // 保留的是最新的 4/5/6，最旧的 1/2/3 被丢
        assertThat(evidence).anyMatch(l -> l.contains("detail-4"))
                .anyMatch(l -> l.contains("detail-5"))
                .anyMatch(l -> l.contains("detail-6"));
        assertThat(evidence).noneSatisfy(l -> assertThat(l).contains("detail-1"));
    }

    @Test
    @DisplayName("无当前场景（纯单测）flush 静默降级：清空不抛错，且不写任何报告")
    void noopHandleDegradesSilently() {
        QueuedNetworkEvidenceBridge bridge = new QueuedNetworkEvidenceBridge(bus);
        // 未 startCase → captureHandle 为 NOOP
        assertThat(bridge.capture().isNoop()).isTrue();

        bridge.record("MONITOR", "https://api.example.com/x", "detail");
        assertThat(bridge.pendingCount()).isEqualTo(1);

        // 不得抛异常，且队列被摘空（不积压到下个场景）
        int written = bridge.flush();
        assertThat(written).isZero();
        assertThat(bridge.pendingCount()).isZero();
    }

    @Test
    @DisplayName("并发入队不丢不乱（多事件线程同时 record，flush 后总数一致）")
    void concurrentEnqueueIsLossless() throws Exception {
        StepHandle caseHandle = bus.startCase("net-q-4", "features/api", "concurrent", List.of());
        StepHandle step = bus.stepStarted("step");
        QueuedNetworkEvidenceBridge bridge = new QueuedNetworkEvidenceBridge(bus);
        StepHandle captured = bridge.capture();

        int threads = 4;
        int perThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        bridge.record("CAPTURE", "https://api.example.com/t" + id + "/" + i, "d" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "pw-evt-" + t).start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(bridge.pendingCount()).isEqualTo(threads * perThread);

        bridge.flush(captured);
        bus.stepFinished(step, TestResult.SUCCESS, null);
        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 1);
        bus.finishRun();

        TestOutcome o = JsonWriter.mapper()
                .readValue(dir.resolve("net-q-4").resolve("net-q-4.json").toFile(), TestOutcome.class);
        //  captured 为 step 级句柄 → API 操作归位到对应步骤；用例级列表为空
        assertThat(o.getApiOperations()).isEmpty();
        assertThat(o.getTestSteps().stream().flatMap(s -> s.getApiOperations().stream()).toList())
                .hasSize(threads * perThread);
    }

    @Test
    @DisplayName("discard 丢弃残留：场景被中断时兜底，防证据串到下个场景")
    void discardDropsResidue() {
        QueuedNetworkEvidenceBridge bridge = new QueuedNetworkEvidenceBridge(bus);
        bridge.record("MONITOR", "https://api.example.com/a", "x");
        bridge.record("MONITOR", "https://api.example.com/b", "y");
        assertThat(bridge.discard()).isEqualTo(2);
        assertThat(bridge.pendingCount()).isZero();
    }
}

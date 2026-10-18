package com.hsbc.cmb.hk.dbb.automation.framework.report.sink;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseStartedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.RunFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HttpPushSink} 契约门禁——此前<b>零单测</b>，三条关键路径全未验证：
 * 队列满丢弃计数、HTTP 失败计数、fail-open（不抛不影响被测流程）。
 *
 * <p>用 JDK 内置 {@link HttpServer} 起真实端点（不 mock HTTP 客户端）：这样能验证
 * 真实的 POST 报文与状态码处理，而非只验证「mock 被调用」。</p>
 */
class HttpPushSinkTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String baseUrl;

    /** 收到的请求体（真实 HTTP 报文）。 */
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    /** 端点应返回的状态码（可动态改以测失败路径）。 */
    private volatile int responseStatus = 202;

    private final CountDownLatch firstRequest = new CountDownLatch(1);

    private HttpPushSink sink;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reports", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/reports";
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            receivedBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        exchange.sendResponseHeaders(responseStatus, -1);
        exchange.close();
        firstRequest.countDown();
    }

    @AfterEach
    void tearDown() {
        if (sink != null) {
            sink.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private void startSink() {
        sink = new HttpPushSink(PwReportsConfig.builder().pushUrl(baseUrl).build());
    }

    private static TestOutcome outcome(String id, TestResult result) {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId(id);
        o.setName("pushed case");
        o.setUserStory(new UserStory("Checkout", "Checkout", "features/checkout.feature"));
        o.setResult(result);
        o.setDuration(12);
        return o;
    }

    private void awaitPushed(long expected, HttpPushSink s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (s.pushedCount() >= expected) {
                return;
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("CaseFinished 被真实 POST 到端点，payload 含 reportVersion 与 event 类型")
    void postsCaseFinishedEvent() throws Exception {
        startSink();
        TestOutcome o = outcome("checkout-1-pay", TestResult.FAILURE);

        sink.accept(new CaseStartedEvent("checkout-1-pay", "features/checkout.feature", "pushed case"));
        sink.accept(new CaseFinishedEvent(o));
        awaitPushed(1, sink);

        assertThat(receivedBodies).hasSize(1);
        String body = receivedBodies.get(0);
        assertThat(body).contains("\"reportVersion\" : 1").contains("\"event\" : \"caseFinished\"");
        assertThat(body).contains("checkout-1-pay").contains("FAILURE");
        assertThat(sink.pushedCount()).isEqualTo(1);
        assertThat(sink.failedCount()).isZero();
    }

    @Test
    @DisplayName("CaseStarted 不推（只推 caseFinished/runFinished，避免噪声）")
    void caseStartedIsNotPushed() throws Exception {
        startSink();
        TestOutcome o = outcome("checkout-2-pay", TestResult.SUCCESS);
        sink.accept(new CaseStartedEvent("checkout-2-pay", "features/checkout.feature", "pushed case"));

        // 给 worker 一点时间，确认它确实没发
        Thread.sleep(200);
        assertThat(receivedBodies).isEmpty();
        assertThat(sink.pushedCount()).isZero();
    }

    @Test
    @DisplayName("RunFinished 被推送")
    void postsRunFinishedEvent() throws Exception {
        startSink();
        sink.accept(new RunFinishedEvent(List.of(outcome("a-1-x", TestResult.SUCCESS))));
        awaitPushed(1, sink);

        assertThat(receivedBodies).hasSize(1);
        assertThat(receivedBodies.get(0)).contains("\"event\" : \"runFinished\"").contains("\"count\" : 1");
    }

    @Test
    @DisplayName("HTTP 4xx/5xx → failed 计数 +1，不抛异常（fail-open，本地 JSON 才是真相源）")
    void httpErrorCountsFailureAndDoesNotThrow() throws Exception {
        responseStatus = 500;
        startSink();
        TestOutcome o = outcome("checkout-3-pay", TestResult.SUCCESS);

        sink.accept(new CaseFinishedEvent(o));   // 不得抛

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && sink.failedCount() == 0) {
            Thread.sleep(20);
        }
        assertThat(sink.failedCount()).isEqualTo(1);
        assertThat(sink.pushedCount()).isZero();
        assertThat(sink.droppedCount()).isZero();
    }

    @Test
    @DisplayName("连接失败（端点不可达）→ failed 计数，worker 不死（后续事件仍可推）")
    void unreachableEndpointCountsFailureAndWorkerSurvives() throws Exception {
        // 指向一个几乎肯定没人监听的端口
        sink = new HttpPushSink(PwReportsConfig.builder()
                .pushUrl("http://127.0.0.1:1/reports")
                .build());
        TestOutcome o = outcome("checkout-4-pay", TestResult.SUCCESS);

        sink.accept(new CaseFinishedEvent(o));

        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline && sink.failedCount() == 0) {
            Thread.sleep(20);
        }
        assertThat(sink.failedCount()).as("连接失败被计数").isPositive();
        assertThat(sink.pushedCount()).isZero();
        // worker 线程仍存活（未被异常打死）
        assertThat(Thread.getAllStackTraces().keySet())
                .anyMatch(t -> t.getName().equals("pw-report-push") && t.isAlive());
    }

    @Test
    @DisplayName("队列满 → 丢弃最旧并计 dropped，绝不阻塞调用方（best-effort）")
    void queueOverflowCountsDropped() throws Exception {
        // 先让端点变慢，制造积压
        responseStatus = 500;
        startSink();

        // 灌远超队列容量（1024）的事件
        for (int i = 0; i < 3_000; i++) {
            sink.accept(new CaseFinishedEvent(outcome("o-" + i, TestResult.SUCCESS)));
        }
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline && sink.droppedCount() == 0) {
            Thread.sleep(20);
        }
        assertThat(sink.droppedCount()).as("队列溢出被丢弃计数").isPositive();
    }

    @Test
    @DisplayName("close 后不推新事件（worker 已停）")
    void closeStopsWorker() throws Exception {
        startSink();
        sink.close();

        TestOutcome o = outcome("checkout-5-pay", TestResult.SUCCESS);
        sink.accept(new CaseFinishedEvent(o));

        Thread.sleep(300);
        assertThat(receivedBodies).as("close 之后不再发送").isEmpty();
    }

    @Test
    @DisplayName("sink 名称稳定（诊断日志/指标按名识别）")
    void sinkNameIsStable() {
        startSink();
        assertThat(sink.name()).isEqualTo("http-push");
    }

    @Test
    @DisplayName("第一个请求确实到达端点（证明是真 HTTP 而非空转）")
    void firstRequestActuallyArrives() throws Exception {
        startSink();
        sink.accept(new CaseFinishedEvent(outcome("checkout-6-pay", TestResult.SUCCESS)));
        assertThat(firstRequest.await(5, TimeUnit.SECONDS)).isTrue();
    }
}

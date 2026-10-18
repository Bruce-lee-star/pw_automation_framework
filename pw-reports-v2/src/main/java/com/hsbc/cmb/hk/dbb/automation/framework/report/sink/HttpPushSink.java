package com.hsbc.cmb.hk.dbb.automation.framework.report.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseStartedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.RunFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP 推送 sink（默认关——{@code pw.report.push.url} 非空即启用）——R5 契约。
 *
 * <p>契约与约束：
 * <ul>
 *   <li>POST {@code {"reportVersion":1,"event":"caseFinished","outcome":{…}}}；</li>
 *   <li>有界队列（1024）+ 单线程池，满则丢弃 + {@code report.push.dropped} 计数；</li>
 *   <li><b>不重试</b>——本地 HTML/JSON 是真相源，推送尽力而为；</li>
 *   <li><b>截图仅推引用（文件名）</b>：截图文件随本地报告目录 / CI artifact 落盘，推送 payload
 *       只带 {@code TestStep.screenshot} 文件名，<b>不内联 bytes</b>——避免大二进制撑爆 payload
 *       与脱敏口径双出口；接收端从报告站点 / 产物取本体；</li>
 *   <li>消费的数据已过 ScrubGate（总线入口脱敏），此处原样序列化。</li>
 * </ul>
 */
public final class HttpPushSink implements ReportSink {

    private static final Logger log = LoggerFactory.getLogger(HttpPushSink.class);

    private static final int QUEUE_CAPACITY = 1024;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final PwReportsConfig config;
    private final ObjectMapper mapper = JsonWriter.mapper();
    private final BlockingQueue<ReportEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong pushed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final HttpClient httpClient;
    private final Thread worker;
    private volatile boolean running = true;

    public HttpPushSink(PwReportsConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        this.worker = new Thread(this::drainLoop, "pw-report-push");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public String name() {
        return "http-push";
    }

    @Override
    public void accept(ReportEvent event) {
        if (event instanceof CaseStartedEvent) {
            return; // 只推 caseFinished / runFinished
        }
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            ReportEvent event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                post(event);
                pushed.incrementAndGet();
            } catch (Throwable t) {
                failed.incrementAndGet();
                log.warn("push failed for {} (no retry; local JSON is the source of truth): {}",
                        event.type(), t.toString());
            }
        }
    }

    private void post(ReportEvent event) throws Exception {
        Map<String, Object> payload = switch (event) {
            case CaseFinishedEvent e -> Map.of(
                    "reportVersion", event.reportVersion(),
                    "event", event.type(),
                    "outcome", e.outcome());
            case RunFinishedEvent e -> Map.of(
                    "reportVersion", event.reportVersion(),
                    "event", event.type(),
                    "count", e.outcomes().size());
            // CaseStartedEvent 已在 accept() 过滤，不入队；此处防御性兜底
            case CaseStartedEvent e -> Map.of(
                    "reportVersion", event.reportVersion(),
                    "event", event.type(),
                    "outcomeId", e.outcomeId());
        };
        byte[] body = mapper.writeValueAsBytes(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getPushUrl()))
                .header("Content-Type", "application/json")
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("push endpoint returned HTTP " + response.statusCode());
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }

    public long pushedCount() {
        return pushed.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failedCount() {
        return failed.get();
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReportingBridgeImpl} 的"按 outcomeId 精确挂载 / 取活动用例 id"契约（per-context 绑定彻底正确化）。
 */
class ReportingBridgeImplOutcomeBindTest {

    @Test
    @DisplayName("API evidence uses the report bus configured scrub rules end-to-end")
    void apiEvidenceUsesConfiguredScrubRules() throws Exception {
        Path dir = Files.createTempDirectory("pw-bind-scrub");
        CountDownLatch pushed = new CountDownLatch(1);
        AtomicReference<String> pushedJson = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            try (var body = exchange.getRequestBody()) {
                pushedJson.set(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, -1);
            pushed.countDown();
            exchange.close();
        });
        server.start();
        ReportBus bus = new ReportBus(PwReportsConfig.builder()
                .enabled(true)
                .outputDir(dir)
                .pushUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/push")
                .extraScrubPatterns(List.of(Pattern.compile("CUSTOM_SECRET-\\d+")))
                .build());
        ReportBus previous = ReportBus.getDefault();
        ReportBus.installDefault(bus);
        try {
            bus.startCase("scrub-case", "feature", "scenario", List.of(), "feature.feature");
            new ReportingBridgeImpl().recordApiOperation("response contained CUSTOM_SECRET-123");
            bus.finishCase(bus.captureHandle(), TestResult.SUCCESS, null, 1);

            assertThat(bus.outcomeSnapshots().get(0).getApiOperations())
                    .containsExactly("response contained [SCRUBBED]")
                    .doesNotContain("CUSTOM_SECRET-123");
            String json = Files.readString(
                    dir.resolve("scrub-case").resolve("scrub-case.json"), StandardCharsets.UTF_8);
            assertThat(json).contains("[SCRUBBED]").doesNotContain("CUSTOM_SECRET-123");
            assertThat(pushed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pushedJson.get()).contains("[SCRUBBED]").doesNotContain("CUSTOM_SECRET-123");
        } finally {
            ReportBus.installDefault(previous);
            server.stop(0);
        }
    }

    @Test
    @DisplayName("currentOutcomeId 返回当前活动用例 id（收尾线程据此回填 context 绑定）")
    void currentOutcomeIdReturnsActiveCase() throws Exception {
        Path dir = Files.createTempDirectory("pw-bind-cur");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        ReportBus previous = ReportBus.getDefault();
        ReportBus.installDefault(bus);
        try {
            ReportingBridgeImpl bridge = new ReportingBridgeImpl();
            assertThat(bridge.currentOutcomeId()).isNull();
            bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");
            assertThat(bridge.currentOutcomeId()).isEqualTo("c1");
            AtomicReference<String> foreignThreadOutcome = new AtomicReference<>("unexpected");
            Thread closeGuard = new Thread(() -> foreignThreadOutcome.set(bridge.currentOutcomeId()));
            closeGuard.start();
            closeGuard.join();
            assertThat(foreignThreadOutcome.get()).isNull();
        } finally {
            ReportBus.installDefault(previous);
        }
    }

    @Test
    @DisplayName("attachTrace(outcomeId, file) 按精确用例挂载 tracePath（不依赖线程上下文）")
    void attachTraceByOutcomeIdSetsTracePath() throws Exception {
        Path dir = Files.createTempDirectory("pw-bind-trace");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        ReportBus previous = ReportBus.getDefault();
        ReportBus.installDefault(bus);
        try {
            ReportingBridgeImpl bridge = new ReportingBridgeImpl();
            bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");
            Path file = dir.resolve("c1").resolve("trace.zip");
            Files.createDirectories(file.getParent());
            Files.write(file, "zip".getBytes());
            //  即便当前线程无句柄上下文（模拟 CloseGuard 收尾线程），凭 outcomeId 仍能精确挂载
            bridge.attachTrace("c1", file);
            assertThat(bus.outcomeSnapshots().get(0).getTracePath()).isEqualTo("c1/trace.zip");
        } finally {
            ReportBus.installDefault(previous);
        }
    }

    @Test
    @DisplayName("attachVideo(outcomeId, file) 按精确用例挂载 videoPath（不依赖线程上下文）")
    void attachVideoByOutcomeIdSetsVideoPath() throws Exception {
        Path dir = Files.createTempDirectory("pw-bind-video");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        ReportBus previous = ReportBus.getDefault();
        ReportBus.installDefault(bus);
        try {
            ReportingBridgeImpl bridge = new ReportingBridgeImpl();
            bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");
            Path file = dir.resolve("c1").resolve("records").resolve("vid.webm");
            Files.createDirectories(file.getParent());
            Files.write(file, "webm".getBytes());
            bridge.attachVideo("c1", file);
            assertThat(bus.outcomeSnapshots().get(0).getVideoPath()).isEqualTo("c1/records/vid.webm");
        } finally {
            ReportBus.installDefault(previous);
        }
    }
}

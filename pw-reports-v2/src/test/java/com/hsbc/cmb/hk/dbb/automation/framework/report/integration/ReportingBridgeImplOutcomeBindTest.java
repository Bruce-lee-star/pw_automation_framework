package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReportingBridgeImpl} 的"按 outcomeId 精确挂载 / 取活动用例 id"契约（per-context 绑定彻底正确化）。
 */
class ReportingBridgeImplOutcomeBindTest {

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

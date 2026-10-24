package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * recordApiMonitorFailure 通道门禁——旧 RouteMonitorFailureReportSink 跨进程通道的
 * 用例内形态：用例级（不挂步骤树）、入 bus 必经 ScrubGate、NOOP/未激活静默、≤200 条。
 */
class ApiMonitorFailureRecordingTest {

    @TempDir
    Path tempDir;

    private ReportBus bus;

    @BeforeEach
    void setUp() {
        bus = new ReportBus(PwReportsConfig.builder()
                .outputDir(tempDir)
                .extraScrubPatterns(List.of(Pattern.compile("card-\\d{16}")))
                .build());
        bus.startRun();
    }

    @AfterEach
    void tearDown() {
        bus.finishRun();
    }

    @Test
    @DisplayName("显式句柄记录：落 per-case JSON apiMonitorFailures，必经 ScrubGate")
    void explicitHandleRecordsToCaseWithScrub() throws Exception {
        StepHandle handle = bus.startCase("m-10-card", "feat", "case", List.of());
        bus.recordApiMonitorFailure(handle, "MONITOR FAIL POST /api/pay card-1234567890123456 status=500");
        bus.finishCase(handle, TestResult.FAILURE, null, 10);

        Path json = tempDir.resolve("m-10-card").resolve("m-10-card.json");
        assertThat(json).exists();
        TestOutcome o = JsonWriter.mapper().readValue(json.toFile(), TestOutcome.class);
        assertThat(o.getApiMonitorFailures()).hasSize(1);
        assertThat(o.getApiMonitorFailures().get(0)).contains("[SCRUBBED]");
        assertThat(o.getApiMonitorFailures().get(0)).doesNotContain("1234567890123456");
        // 不串入 apiOperations（独立通道）
        assertThat(o.getApiOperations()).isEmpty();
    }

    @Test
    @DisplayName("零参版走场景线程 ThreadLocal；NOOP 句柄静默")
    void zeroArgVersionUsesThreadLocalAndNoopIgnored() throws Exception {
        StepHandle handle = bus.startCase("m-20-zero", "feat", "case", List.of());
        bus.recordApiMonitorFailure("MONITOR FAIL via thread local");
        // NOOP：总线没激活的等价形态
        bus.recordApiMonitorFailure(StepHandle.NOOP, "should be dropped");
        bus.finishCase(handle, TestResult.SUCCESS, null, 5);

        Path json = tempDir.resolve("m-20-zero").resolve("m-20-zero.json");
        TestOutcome o = JsonWriter.mapper().readValue(json.toFile(), TestOutcome.class);
        assertThat(o.getApiMonitorFailures()).containsExactly("MONITOR FAIL via thread local");
    }

    @Test
    @DisplayName("用例级：不产生步骤，监控失败不挂进步骤树")
    void caseLevelOnlyNoStepTreePollution() throws Exception {
        StepHandle handle = bus.startCase("m-30-tree", "feat", "case", List.of());
        StepHandle step = bus.stepStarted("do payment");
        bus.recordApiMonitorFailure(step, "MONITOR FAIL attached to step handle but stored case-level");
        bus.stepFinished(step, TestResult.SUCCESS, null);
        bus.finishCase(handle, TestResult.SUCCESS, null, 5);

        Path json = tempDir.resolve("m-30-tree").resolve("m-30-tree.json");
        TestOutcome o = JsonWriter.mapper().readValue(json.toFile(), TestOutcome.class);
        assertThat(o.getApiMonitorFailures()).hasSize(1);
        assertThat(o.getTestSteps()).hasSize(1);
        assertThat(o.getTestSteps().get(0).getEvidence()).isEmpty();
    }

    @Test
    @DisplayName("上限 200：超限静默丢弃（fail-open，不影响测试）")
    void boundedAt200() throws Exception {
        StepHandle handle = bus.startCase("m-40-cap", "feat", "case", List.of());
        for (int i = 0; i < 250; i++) {
            bus.recordApiMonitorFailure(handle, "MONITOR FAIL item-" + i);
        }
        bus.finishCase(handle, TestResult.SUCCESS, null, 5);

        Path json = tempDir.resolve("m-40-cap").resolve("m-40-cap.json");
        TestOutcome o = JsonWriter.mapper().readValue(json.toFile(), TestOutcome.class);
        assertThat(o.getApiMonitorFailures()).hasSize(200);
        assertThat(o.getApiMonitorFailures().get(199)).isEqualTo("MONITOR FAIL item-199");
    }

    @Test
    @DisplayName("kill-switch：enabled=false 时全静默")
    void disabledBusIsSilent() {
        ReportBus off = new ReportBus(PwReportsConfig.builder().outputDir(tempDir).enabled(false).build());
        off.startRun();
        StepHandle handle = off.startCase("m-50-off", "feat", "case", List.of());
        off.recordApiMonitorFailure(handle, "dropped");
        off.recordApiMonitorFailure("also dropped");
        off.finishCase(handle, TestResult.SUCCESS, null, 5);
        // 无异常、无落盘即通过
        assertThat(tempDir.resolve("m-50-off").resolve("m-50-off.json")).doesNotExist();
    }
}

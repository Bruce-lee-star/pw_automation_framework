package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.console.ConsoleCapture;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReportBus} 控制台捕获集成测试——验证 stdout/stderr 在步骤内归步骤、步骤外归用例，
 * 且经 ScrubGate 脱敏。用例结束还原全局流，避免泄漏。
 */
class ReportBusConsoleTest {

    @AfterEach
    void tearDown() {
        ConsoleCapture.clear();
        ConsoleCapture.uninstall();
    }

    @Test
    @DisplayName("步骤内输出归步骤、步骤外/用例级输出归用例，且分离 stdout/stderr")
    void consoleAttributedToStepAndCase() throws Exception {
        Path dir = Files.createTempDirectory("pw-report-console");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());

        StepHandle c = bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");
        System.out.println("CASE_BEFORE_STEP");
        StepHandle s = bus.stepStarted("a step");
        System.out.println("STEP_LINE");
        bus.stepFinished(s, TestResult.SUCCESS, null);
        System.err.println("CASE_AFTER_STEP_ERR");
        bus.finishCase(c, TestResult.SUCCESS, null, -1);

        List<TestOutcome> snaps = bus.outcomeSnapshots();
        assertThat(snaps).hasSize(1);
        TestOutcome o = snaps.get(0);

        assertThat(o.getConsoleOutput()).contains("CASE_BEFORE_STEP");
        assertThat(o.getConsoleError()).contains("CASE_AFTER_STEP_ERR");

        assertThat(o.getTestSteps()).hasSize(1);
        assertThat(o.getTestSteps().get(0).getConsoleOutput()).contains("STEP_LINE");
        // 步骤输出不应泄漏到用例级
        assertThat(o.getConsoleOutput()).doesNotContain("STEP_LINE");
    }

    @Test
    @DisplayName("脚本中写敏感串经 ScrubGate 脱敏后入报告")
    void sensitiveConsoleIsScrubbed() throws Exception {
        Path dir = Files.createTempDirectory("pw-report-console-scrub");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());

        StepHandle c = bus.startCase("c2", "feature", "scenario", List.of("t"), "feature.feature");
        System.out.println("GET /api/session?token=secret-value&code=abc");
        bus.finishCase(c, TestResult.SUCCESS, null, -1);

        TestOutcome o = bus.outcomeSnapshots().get(0);
        assertThat(o.getConsoleOutput()).noneMatch(line -> line.contains("secret-value"));
        assertThat(o.getConsoleOutput()).anyMatch(line -> line.contains("token=***"));
    }
}

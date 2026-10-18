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
 * {@link ReportBus#recordRouteRegistration} 集成测试——验证 RouteDsl 注册行落到
 * 用例级 {@code routeRegistrations}（报告"Route registrations"区），与 stdout / 网络调用分开归类。
 */
class RouteRegistrationReportTest {

    @AfterEach
    void tearDown() {
        ConsoleCapture.clear();
        ConsoleCapture.uninstall();
    }

    @Test
    @DisplayName("recordRouteRegistration 落到 outcome.routeRegistrations（用例级）")
    void routeRegistrationAttributedToCase() throws Exception {
        Path dir = Files.createTempDirectory("pw-report-route");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());

        StepHandle c = bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");
        bus.recordRouteRegistration("[Route] register GET route for '/api/health' (monitor: timeout=30s)");
        bus.recordRouteRegistration("[Route] register POST route for '/api/session' (mock: status=200)");
        bus.finishCase(c, TestResult.SUCCESS, null, -1);

        TestOutcome o = bus.outcomeSnapshots().get(0);
        assertThat(o.getRouteRegistrations()).containsExactly(
                "[Route] register GET route for '/api/health' (monitor: timeout=30s)",
                "[Route] register POST route for '/api/session' (mock: status=200)");
    }

    @Test
    @DisplayName("bus 未激活时不记录路由注册")
    void noopWhenDisabled() throws Exception {
        Path dir = Files.createTempDirectory("pw-report-route-off");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(false).outputDir(dir).build());

        StepHandle c = bus.startCase("c2", "feature", "scenario", List.of("t"), "feature.feature");
        bus.recordRouteRegistration("[Route] register GET route for '/x'");
        bus.finishCase(c, TestResult.SUCCESS, null, -1);

        assertThat(bus.outcomeSnapshots()).isEmpty();
    }
}

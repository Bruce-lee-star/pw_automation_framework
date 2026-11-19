package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 失败页源码链路（{@link ReportBus#pageSourceTarget} / {@link ReportBus#attachPageSource}）。 */
class ReportBusPageSourceTest {

    @Test
    @DisplayName("pageSourceTarget 指向 per-case page-source.html；就位后 attach 仅登记路径")
    void targetAndRegisterInPlace() throws Exception {
        Path dir = Files.createTempDirectory("pw-page-source");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        StepHandle h = bus.startCase("c1", "feature", "scenario", List.of("t"), "feature.feature");

        Path target = bus.pageSourceTarget(h);
        assertThat(target).isEqualTo(dir.resolve("c1").resolve("page-source.html"));

        String html = "<!doctype html><html><head><base href=\"https://site/\"></head>"
                + "<body><h1>failure page</h1></body></html>";
        Files.createDirectories(target.getParent());
        Files.writeString(target, html, StandardCharsets.UTF_8);
        bus.attachPageSource(h, target);

        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.readString(target)).isEqualTo(html);
        assertThat(bus.outcomeSnapshots().get(0).getPageSourcePath()).isEqualTo("c1/page-source.html");
    }

    @Test
    @DisplayName("无活动句柄 / NOOP 时 target 为 null，attach 静默")
    void noopIsSilent() throws Exception {
        Path dir = Files.createTempDirectory("pw-page-source-noop");
        ReportBus bus = new ReportBus(PwReportsConfig.builder().enabled(true).outputDir(dir).build());
        assertThat(bus.pageSourceTarget(StepHandle.NOOP)).isNull();
        bus.attachPageSource(StepHandle.NOOP, dir.resolve("none.html"));
        assertThat(bus.outcomeSnapshots()).isEmpty();
    }
}

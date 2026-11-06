package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code locator-health.html} 独立页门禁。
 *
 * <p>核心约束（2026-11-06 用户决定）：定位健康度<b>独立成页</b>，
 * {@code index.html} 与 {@code email-report.html} <b>不受任何影响</b>；
 * 无采集数据时<b>不落文件</b>（文件存在即有内容，与 monitor-failures 同口径）。
 */
class LocatorHealthHtmlRendererTest {

    @TempDir
    Path dir;

    @AfterEach
    void resetRegistry() {
        LocatorHealthReportRegistry.install(null);
    }

    private static LocatorHealthReportData sampleData() {
        return new LocatorHealthReportData(
                List.of(new LocatorHealthEntry("#row", "css", 12L, 2L, 3),
                        new LocatorHealthEntry("role=button[name=\"Ok\"]", "role", 30L, 0L, 0),
                        new LocatorHealthEntry("#login", "css", 5L, 0L, 0)),
                List.of(new LocatorHealthViolation("Login as user A", "#row", "css", "click", 3,
                        "main", "2026-11-06T10:00:00Z")),
                false);
    }

    @Test
    @DisplayName("有采集数据时产出独立页，含场景归因与用量表")
    void writesStandalonePageWithEvidence() throws Exception {
        LocatorHealthReportRegistry.install(LocatorHealthHtmlRendererTest::sampleData);

        Path out = LocatorHealthHtmlRenderer.write(dir, List.of(OutcomeFixtures.full()));

        assertThat(out).isNotNull();
        assertThat(out.getFileName().toString()).isEqualTo("locator-health.html");
        String html = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(html)
                .contains("Locator health")
                .contains("Strict violations")
                .contains("Login as user A")
                .contains("#row")
                .contains("click")
                .contains("Most used selectors")
                .contains("#login")
                .contains("Selectors sampled <b>3</b>")
                .contains("strict violations <b>1</b>");
    }

    @Test
    @DisplayName("用量表按 uses 降序、同量按选择器字典序（模板零逻辑）")
    void topUsageIsSortedByUsesDescending() {
        List<LocatorHealthEntry> top = LocatorHealthHtmlRenderer.topUsage(sampleData());

        assertThat(top).extracting(LocatorHealthEntry::getSelector)
                .containsExactly("role=button[name=\"Ok\"]", "#row", "#login");
    }

    @Test
    @DisplayName("零违例也落文件，并给出「定位稳定」结论")
    void writesPageWhenNoViolations() throws Exception {
        LocatorHealthReportRegistry.install(() -> new LocatorHealthReportData(
                List.of(new LocatorHealthEntry("#login", "css", 5L, 0L, 0)), List.of(), false));

        Path out = LocatorHealthHtmlRenderer.write(dir, List.of(OutcomeFixtures.full()));

        assertThat(out).isNotNull();
        assertThat(Files.readString(out, StandardCharsets.UTF_8))
                .contains("No strict-mode violations");
    }

    @Test
    @DisplayName("无采集数据时不落文件")
    void writesNothingWhenNoData() {
        LocatorHealthReportRegistry.install(LocatorHealthReportData::empty);

        Path out = LocatorHealthHtmlRenderer.write(dir, List.of(OutcomeFixtures.full()));

        assertThat(out).isNull();
        assertThat(dir.resolve(LocatorHealthHtmlRenderer.LOCATOR_HEALTH_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("classpath 未装配采集模块时不落文件")
    void writesNothingWhenProviderAbsent() {
        LocatorHealthReportRegistry.install(null); // 本模块 classpath 无 SPI 实现 → 空数据

        Path out = LocatorHealthHtmlRenderer.write(dir, List.of(OutcomeFixtures.full()));

        assertThat(out).isNull();
        assertThat(dir.resolve(LocatorHealthHtmlRenderer.LOCATOR_HEALTH_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("index.html 完全不受影响：无内容、无入口（有数据时也不引入）")
    void indexReportIsNotPolluted() throws Exception {
        LocatorHealthReportRegistry.install(LocatorHealthHtmlRendererTest::sampleData);

        String index = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        assertThat(index)
                .doesNotContain("locator-health.html")
                .doesNotContain("Locator health")
                .doesNotContain("Strict violations")
                .doesNotContain("Most used selectors")
                .doesNotContain("Login as user A");
    }

    @Test
    @DisplayName("email-report.html 完全不受影响（既无内容也无入口）")
    void emailReportIsNotPolluted() throws Exception {
        LocatorHealthReportRegistry.install(LocatorHealthHtmlRendererTest::sampleData);

        Path email = EmailReportRenderer.writeEmailReport(dir, List.of(OutcomeFixtures.full()));

        assertThat(Files.readString(email, StandardCharsets.UTF_8))
                .doesNotContain("locator-health.html")
                .doesNotContain("Locator health");
    }

    @Test
    @DisplayName("选择器内容按 HTML 转义（防 XSS）")
    void escapesSelectorMarkup() throws Exception {
        LocatorHealthReportRegistry.install(() -> new LocatorHealthReportData(
                List.of(new LocatorHealthEntry("<img src=x onerror=alert(1)>", "css", 1L, 0L, 0)),
                List.of(), false));

        Path out = LocatorHealthHtmlRenderer.write(dir, List.of(OutcomeFixtures.full()));

        assertThat(out).isNotNull();
        assertThat(Files.readString(out, StandardCharsets.UTF_8))
                .doesNotContain("<img src=x onerror=alert(1)>")
                .contains("&lt;img src=x onerror=alert(1)&gt;");
    }
}

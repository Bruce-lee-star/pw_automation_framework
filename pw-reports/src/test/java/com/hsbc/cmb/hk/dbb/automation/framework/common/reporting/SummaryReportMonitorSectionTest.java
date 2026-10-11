package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 端到端验证：API 监控失败区块<b>从 summary-report 拆出</b>，独立生成 monitor-failures.html。
 *
 * <p>经测试专用 SPI 实现 {@link TestMonitorFailureReportSink}（注册于 reporting 测试 classpath）注入样例数据，
 * 驱动真实 {@link SummaryReportGenerator} 走通全链路。断言：
 * <ul>
 *   <li>{@code monitor-failures.html} 存在且含失败记录 / 数据丢失红框；</li>
 *   <li>{@code serenity-summary.html} <b>不再</b>内嵌该区块（拆出后 summary 保持干净）。</li>
 * </ul>
 */
public class SummaryReportMonitorSectionTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();
    private File folder;

    @Before
    public void initTempFolder() {
        folder = tempFolder.getRoot();
    }

    private File newReportDir(String name) {
        File dir = new File(folder, name);
        if (!dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Cannot create test report dir: " + dir);
        }
        return dir;
    }

    private static final String PROJECT_NAME = "Monitor Section Project";
    private static final String REPORT_URL = "https://reports.example.com/job/44/Serenity_20Summary_20Report/";

    @Before
    public void pinEnvironment() {
        System.setProperty("serenity.project.name", PROJECT_NAME);
        System.setProperty("serenity.report.url", REPORT_URL);
    }

    @After
    public void restoreEnvironment() {
        System.clearProperty("serenity.project.name");
        System.clearProperty("serenity.report.url");
        TestMonitorFailureReportSink.reset();
    }

    @Test
    public void reportDisplaysMonitorFailureSectionWhenDataPresent() throws Exception {
        List<MonitorFailureItem> items = new ArrayList<>();
        items.add(new MonitorFailureItem(
                "team-a@hsbc.com", "Login", "/api/v1/transfer", "500", "POST",
                "https://api.example.com/api/v1/transfer", "status=500 expected=200",
                List.of("Scenario A", "Scenario B"), "{\"amt\":100}", "{\"error\":\"boom\"}", "", ""));
        List<MonitorOwnerBlock> owners = new ArrayList<>();
        owners.add(new MonitorOwnerBlock("team-a@hsbc.com", items));

        Map<String, Long> loss = new LinkedHashMap<>();
        loss.put("route_monitor_record", 3L);
        MonitorFailureReportData data = new MonitorFailureReportData(owners, loss, 3L, 1, 1);
        TestMonitorFailureReportSink.setSampleData(data);

        File dir = newReportDir("monitor-report");
        writeOutcome(dir, "ok.json", "{\n"
                + "  \"name\": \"Happy path\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 500,\n"
                + "  \"userStory\": { \"storyName\": \"Monitor Feature\" },\n"
                + "  \"scenarioId\": \"monitor-feature;happy-path\",\n"
                + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
                + "}");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        // standalone monitor-failures.html: contains failure record + data-loss warning
        String monitorHtml = Files.readString(dir.toPath().resolve("monitor-failures.html"), StandardCharsets.UTF_8);
        assertTrue("standalone report must contain title", monitorHtml.contains("API Monitor Failure Report"));
        assertTrue("standalone report must contain owner/pattern", monitorHtml.contains("/api/v1/transfer"));
        assertTrue("standalone report must contain data-loss warning title", monitorHtml.contains("Data integrity warning"));
        assertTrue("standalone report must contain loss record count", monitorHtml.contains("3 records"));
        assertTrue("standalone report must contain deduped failure count", monitorHtml.contains("1x"));
        // directives/placeholders must be resolved
        assertFalse("Freemarker directives must not leak into output", monitorHtml.contains("<#"));
        assertFalse("template placeholders must be resolved", monitorHtml.contains("${"));

        // summary-report no longer embeds this section
        String summaryHtml = Files.readString(dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);
        assertFalse("summary must not embed API monitor failure section", summaryHtml.contains("API Monitor Failure"));
        assertFalse("summary must not contain data-loss warning", summaryHtml.contains("Data integrity warning"));
    }

    @Test
    public void reportOmitsMonitorSectionWhenNoData() throws Exception {
        TestMonitorFailureReportSink.setSampleData(MonitorFailureReportData.empty());

        File dir = newReportDir("no-monitor-report");
        writeOutcome(dir, "ok.json", "{\n"
                + "  \"name\": \"Happy path\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 500,\n"
                + "  \"userStory\": { \"storyName\": \"Monitor Feature\" },\n"
                + "  \"scenarioId\": \"monitor-feature;happy-path\",\n"
                + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
                + "}");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        String html = Files.readString(dir.toPath().resolve("serenity-summary.html"), StandardCharsets.UTF_8);
        assertFalse("summary must not render monitor section when no data", html.contains("API Monitor Failure"));
        assertFalse("summary must not render data-loss warning when no data", html.contains("Data integrity warning"));
        assertFalse("无数据时不生成独立 monitor-failures.html",
                Files.exists(dir.toPath().resolve("monitor-failures.html")));
    }

    /**
     * N-05（doc 21 CRITICAL｜假绿方向）：{@code write()} 失败的 sink <b>不得</b>被 {@code clear()}。
     *
     * <p>原实现对全部 sink 无条件 clear —— 写失败者（磁盘满/权限/序列化异常）的内存记录既未落盘、
     * 又被丢弃，报告侧于是显示「无监控失败」：把「有失败」变成「没有失败」。</p>
     *
     * <p><b>本用例在旧实现下必红</b>（旧实现会把失败的 sink 也 clear，{@code clearCalls() == 1}）。</p>
     */
    @Test
    public void failedSinkIsNotClearedSoRecordsSurviveForRetry() throws Exception {
        TestMonitorFailureReportSink.setSampleData(sampleDataWithOneOwner());
        TestMonitorFailureReportSink.setFailWrite(true);

        File dir = newReportDir("sink-write-failure");
        writeOutcome(dir, "ok.json", "{\n"
                + "  \"name\": \"Happy path\",\n"
                + "  \"result\": \"SUCCESS\",\n"
                + "  \"duration\": 500,\n"
                + "  \"userStory\": { \"storyName\": \"Monitor Feature\" },\n"
                + "  \"scenarioId\": \"monitor-feature;happy-path\",\n"
                + "  \"startTime\": \"2026-09-01T10:00:00.000000+08:00\"\n"
                + "}");

        new SummaryReportGenerator(dir.getAbsolutePath()).generateSummaryReport();

        assertTrue("应确实尝试过写入该 sink", TestMonitorFailureReportSink.writeAttempts() >= 1);
        assertEquals("write 失败的 sink 绝不能被 clear —— 否则其记录既未落盘、也从内存消失（静默数据丢失 / 假绿）", 0, TestMonitorFailureReportSink.clearCalls());
    }

    private static MonitorFailureReportData sampleDataWithOneOwner() {
        List<MonitorFailureItem> items = new ArrayList<>();
        items.add(new MonitorFailureItem(
                "team-a@hsbc.com", "Login", "/api/v1/transfer", "500", "POST",
                "https://api.example.com/api/v1/transfer", "status=500 expected=200",
                List.of("Scenario A"), "{\"amt\":100}", "{\"error\":\"boom\"}", "", ""));
        List<MonitorOwnerBlock> owners = new ArrayList<>();
        owners.add(new MonitorOwnerBlock("team-a@hsbc.com", items));
        Map<String, Long> loss = new LinkedHashMap<>();
        loss.put("route_monitor_record", 3L);
        return new MonitorFailureReportData(owners, loss, 3L, 1, 1);
    }

    private static void writeOutcome(File dir, String fileName, String json) throws Exception {
        Files.writeString(new File(dir, fileName).toPath(), json, StandardCharsets.UTF_8);
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 聚合 HTML 出口——{@code monitor-failures.html} + 邮件报告共用的汇总模型
 * {@link #summaryModel(List)}。
 *
 * <p><b>{@code serenity-summary.html} 已于 2026-10-22 整页删除</b>（用户决定）：它与
 * {@code index.html} + {@code feature-*.html} 信息完全重复，且是三页里唯一没对齐顶栏的遗留页。
 * 删除范围：模板 {@code serenity-summary.ftl}、{@code writeSummary}、{@code SUMMARY_FILE}
 * 常量、{@code minimalSummary} 兜底页，以及 {@code LocalReportSink} 里的写盘调用。
 * {@code pw-summary.json} / {@code pw-summary.csv} 是<b>数据出口</b>（供 CI 与
 * 邮件插件消费），与 HTML 页无关，<b>保留不动</b>。
 *
 * <p>与旧实现的差异（有意）：
 * <ul>
 *   <li>数据源不同：旧版读 Serenity HTML/JSON + 跨进程 monitor-failures-by-owner.json；
 *       本类直接读 per-case TestOutcome（同进程内存对象，无第二解析层）；</li>
 *   <li><b>无 owner 维度</b>（用户决策 2026-10-16）：monitor 报告按 feature 分组，
 *       owner 通道待框架侧定义后再加；</li>
 *   <li>monitor 报告<b>无数据不落文件</b>（对齐旧行为，CI 侧「文件存在即有失败」语义不变）。</li>
 * </ul>
 *
 * <p>降级链与 {@link HtmlRenderer} 一致：模板异常 → 纯 Java 兜底页；单报告失败不拖累其余产物。
 */
public final class SummaryHtmlRenderer {

    private static final Logger log = LoggerFactory.getLogger(SummaryHtmlRenderer.class);

    /** 旧模块同名文件（MONITOR_FAILURE_FILE 常量）。 */
    public static final String MONITOR_FAILURE_FILE = "monitor-failures.html";

    private static final Configuration FREEMARKER = new Configuration(Configuration.VERSION_2_3_34);

    static {
        FREEMARKER.setClassLoaderForTemplateLoading(SummaryHtmlRenderer.class.getClassLoader(), "templates/");
        FREEMARKER.setDefaultEncoding(StandardCharsets.UTF_8.name());
        FREEMARKER.setLogTemplateExceptions(false);
        FREEMARKER.setLocale(Locale.ROOT);
    }

    private SummaryHtmlRenderer() {
    }

    // ---------------------------------------------------------------- monitor

    /**
     * 写 {@code monitor-failures.html}：监控失败独立报告（旧版 2026-10-11 拆出语义）。
     *
     * @return 文件路径；<b>无任何监控失败时返回 {@code null} 且不落文件</b>
     *        （对齐旧版：文件存在即有失败）
     */
    public static Path writeMonitorFailures(Path dir, List<TestOutcome> outcomes) {
        List<MonitorEntry> entries = monitorEntries(outcomes);
        if (entries.isEmpty()) {
            return null;
        }
        Path target = dir.resolve(MONITOR_FAILURE_FILE);
        try {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("baseHref", PwReportsConfig.baseUrl());
            model.put("totalFailures", entries.size());
            model.put("affectedScenarios", entries.stream().map(e -> e.scenario()).distinct().count());
            model.put("groups", monitorGroups(entries));
            model.put("failures", entriesAsMaps(entries));
            String html = process("monitor-failures.ftl", model);
            Files.createDirectories(dir);
            Files.writeString(target, html, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            log.warn("monitor-failures HTML render failed (summary report unaffected)", t);
            return null;
        }
        return target;
    }

    // ---------------------------------------------------------------- model

    /**
     * 汇总模型：totals + feature 分组 + 失败概览 + 占比/环形图分段。
     *
     * <p>{@code serenity-summary.html} 删除后，唯一消费方是
     * {@link EmailReportRenderer}（邮件报告），故模型口径仍保留原样，邮件页不受影响。
     */
    static Map<String, Object> summaryModel(List<TestOutcome> outcomes) {
        Map<String, Object> model = new LinkedHashMap<>();
        Map<String, Object> totals = JsonWriter.totals(outcomes);
        List<Map<String, Object>> groups = featureGroups(outcomes);
        int count = outcomes.size();
        int failures = (int) outcomes.stream()
                .filter(o -> o.getResult() != null && o.getResult().isFailure()).count();
        model.put("totals", totals);
        model.put("count", count);
        model.put("failedCount", failures);
        model.put("allPassed", count > 0 && failures == 0);
        model.put("hasFailures", failures > 0);
        model.put("totalDuration", totals.get("duration"));
        model.put("totalDurationText", formatDuration(((Number) totals.get("duration")).longValue()));
        model.put("successRate", successRate(count, failures));
        model.put("featureCount", groups.size());
        model.put("tags", tagCounts(outcomes));
        model.put("groups", groups);
        model.put("failureList", failureRows(outcomes));
        // 新设计（docs/template-design）所需：分结果计数 / 占比 / 环形图分段 / 自动与手工
        int errorCount = ReportView.count(outcomes, "ERROR");
        int passCount = ReportView.count(outcomes, "PASS");
        int pendingCount = ReportView.count(outcomes, "PENDING");
        int skippedCount = ReportView.count(outcomes, "SKIPPED")
                + ReportView.count(outcomes, "IGNORED")
                + ReportView.count(outcomes, "UNDEFINED");
        int autoCount = (int) outcomes.stream().filter(o -> !o.isManual()).count();
        model.put("passCount", passCount);
        model.put("failCount", failures - errorCount);
        model.put("errorCount", errorCount);
        model.put("pendingCount", pendingCount);
        model.put("skippedCount", skippedCount);
        model.put("autoCount", autoCount);
        model.put("manualCount", count - autoCount);
        model.put("passPct", ReportView.pct(passCount, count));
        model.put("failPct", ReportView.pct(failures - errorCount, count));
        model.put("errorPct", ReportView.pct(errorCount, count));
        model.put("pendingPct", ReportView.pct(pendingCount, count));
        model.put("skippedPct", ReportView.pct(skippedCount, count));
        model.put("autoPct", ReportView.pct(autoCount, count));
        model.put("manualPct", ReportView.pct(count - autoCount, count));
        model.put("baseHref", PwReportsConfig.baseUrl());
        model.put("traceViewerBaseUrl", PwReportsConfig.traceViewerBaseUrl());
        model.put("mixSegments", ReportView.segments(
                "auto", "#3b82f6", autoCount,
                "manual", "#0d9488", count - autoCount));
        model.put("resultSegments", ReportView.segments(
                "PASS", "#16a34a", passCount,
                "FAILURE", "#dc2626", failures - errorCount,
                "ERROR", "#ea580c", errorCount,
                "PENDING", "#d97706", pendingCount,
                "SKIPPED", "#94a3b8", skippedCount));
        return model;
    }

    /** feature 分组（保持首现顺序）；组内是场景行。 */
    static List<Map<String, Object>> featureGroups(List<TestOutcome> outcomes) {
        Map<String, List<Map<String, Object>>> byFeature = new LinkedHashMap<>();
        for (TestOutcome o : outcomes) {
            String feature = o.getUserStory() == null || o.getUserStory().getStoryName() == null
                    ? "(unknown feature)" : o.getUserStory().getStoryName();
            byFeature.computeIfAbsent(feature, f -> new ArrayList<>()).add(scenarioRow(o));
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byFeature.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("feature", e.getKey());
            g.put("scenarios", e.getValue());
            g.put("failed", e.getValue().stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.get("isFailure"))));
            groups.add(g);
        }
        return groups;
    }

    private static Map<String, Object> scenarioRow(TestOutcome o) {
        Map<String, Object> row = new LinkedHashMap<>();
        String result = o.getResult() == null ? "UNKNOWN" : o.getResult().name();
        String resultKey = ReportView.key(o.getResult());
        row.put("name", o.getName());
        row.put("result", result);
        // 新设计（docs/template-design）以 PASS 为准、按 key 出徽章与筛选，故一并给出
        row.put("resultKey", resultKey);
        row.put("resultLabel", ReportView.label(resultKey));
        row.put("auto", !o.isManual());
        row.put("durationSec", ReportView.durationSec(o.getDuration()));
        row.put("isFailure", o.getResult() != null && o.getResult().isFailure());
        row.put("isSuccess", o.getResult() == com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult.SUCCESS);
        row.put("durationMs", o.getDuration());
        row.put("durationText", formatDuration(o.getDuration()));
        row.put("link", o.getOutcomeId() + "/" + o.getOutcomeId() + ".html");
        row.put("tags", o.getTags() == null ? List.of() : o.getTags());
        row.put("isOutline", Boolean.TRUE.equals(o.getScenarioOutline()));
        row.put("exampleCount", o.getExamples() == null ? 0 : o.getExamples().size());
        return row;
    }

    /** 失败概览行（FAILURE/ERROR 用例）。 */
    static List<Map<String, Object>> failureRows(List<TestOutcome> outcomes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TestOutcome o : outcomes) {
            if (o.getResult() == null || !o.getResult().isFailure()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", o.getName());
            row.put("feature", o.getUserStory() == null ? "" : o.getUserStory().getStoryName());
            row.put("result", o.getResult().name());
            row.put("link", o.getOutcomeId() + "/" + o.getOutcomeId() + ".html");
            String message = o.getTestFailureCause() == null || o.getTestFailureCause().getMessage() == null
                    ? (o.getTestFailureMessage() == null ? "(no message)" : o.getTestFailureMessage())
                    : o.getTestFailureCause().getMessage();
            row.put("message", firstLine(message));
            rows.add(row);
        }
        return rows;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    /** 成功占比（0–100，保留 1 位小数）；空跑记 0。 */
    static double successRate(int count, int failures) {
        if (count <= 0) {
            return 0.0;
        }
        return Math.round((count - failures) * 1000.0 / count) / 10.0;
    }

    /** 人读时长：&lt;1s 用 ms，&lt;1min 用 s，否则 m s（对齐 Serenity 的可读口径）。 */
    static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        long totalSec = Math.round(ms / 1000.0);
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min > 0) {
            return min + "m " + sec + "s";
        }
        return (ms % 1000 == 0 ? totalSec + "s" : String.format(Locale.ROOT, "%.1fs", ms / 1000.0));
    }

    /**
     * 执行耗时汇总：Total / Clock / Average / Max / Min（已格式化文本）。
     *
     * <p>口径与旧邮件摘要一致：Total = 全部用例 duration 之和，Clock 同 Total（串行墙钟口径下两者一致），
     * Average = 均值，Max / Min = 最大 / 最小单用例耗时。index / feature / 邮件三处共用，避免各算各的漂移。
     */
    static Map<String, Object> timings(List<TestOutcome> outcomes) {
        List<TestOutcome> list = outcomes == null ? List.of() : outcomes;
        long total = list.stream().mapToLong(TestOutcome::getDuration).sum();
        long max = list.stream().mapToLong(TestOutcome::getDuration).max().orElse(0);
        long min = list.stream().mapToLong(TestOutcome::getDuration).min().orElse(0);
        long avg = list.isEmpty() ? 0 : Math.round(total / (double) list.size());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("total", formatDuration(total));
        t.put("clock", formatDuration(total));
        t.put("avg", formatDuration(avg));
        t.put("max", formatDuration(max));
        t.put("min", formatDuration(min));
        return t;
    }

    /** 人读时间戳格式（{@code yyyy-MM-dd HH:mm:ss}，系统默认时区渲染）。 */
    private static final DateTimeFormatter HUMAN_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /**
     * 运行窗口（index / feature / per-case / 邮件四份报告顶栏共用，2026-10-22 用户要求：
     * 「顶部除报告名外，显示生成时间、从什么时间开始测试、什么时间结束」）。
     *
     * <p>口径：{@code startedAt} = 最早用例开始时间、{@code endedAt} = 最晚用例结束时间、
     * {@code generatedAt} = 报告生成时刻；均已格式化为 {@code yyyy-MM-dd HH:mm:ss}。
     * 空跑或无时间戳时 started/ended 为 {@code "—"}，{@code generatedAt} 恒有值。</p>
     */
    static Map<String, Object> runWindow(List<TestOutcome> outcomes) {
        List<TestOutcome> list = outcomes == null ? List.of() : outcomes;
        OffsetDateTime min = null;
        OffsetDateTime max = null;
        for (TestOutcome o : list) {
            OffsetDateTime s = parseQuietly(o.getStartTime());
            OffsetDateTime e = parseQuietly(o.getEndTime());
            if (s != null && (min == null || s.isBefore(min))) {
                min = s;
            }
            if (e != null && (max == null || e.isAfter(max))) {
                max = e;
            }
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("startedAt", min == null ? "—" : HUMAN_TS.format(min));
        w.put("endedAt", max == null ? "—" : HUMAN_TS.format(max));
        w.put("generatedAt", HUMAN_TS.format(OffsetDateTime.now()));
        return w;
    }

    /** ISO-8601 → {@link OffsetDateTime}；缺失/不可解析时返回 null（不抛，容忍脏数据）。 */
    private static OffsetDateTime parseQuietly(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            log.debug("timestamp without offset, trying local date-time: {}", iso);
        }
        try {
            return LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            log.debug("unparsable timestamp ignored: {}", iso);
            return null;
        }
    }

    /** 标签云：按出现次数降序、同量按字典序；供 index / summary 共用。 */
    static List<Map<String, Object>> tagCounts(List<TestOutcome> outcomes) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (TestOutcome o : outcomes) {
            if (o.getTags() == null) {
                continue;
            }
            for (String tag : o.getTags()) {
                if (tag != null && !tag.isEmpty()) {
                    counts.merge(tag, 1, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tag", e.getKey());
            row.put("count", e.getValue());
            rows.add(row);
        }
        rows.sort((a, b) -> {
            int c = Integer.compare((Integer) b.get("count"), (Integer) a.get("count"));
            return c != 0 ? c : ((String) a.get("tag")).compareTo((String) b.get("tag"));
        });
        return rows;
    }

    // ---------------------------------------------------------------- monitor model

    /** 监控失败扁平条目：一个 entry = 一条失败记录。 */
    record MonitorEntry(String scenario, String feature, String line, String link) {
    }

    static List<MonitorEntry> monitorEntries(List<TestOutcome> outcomes) {
        List<MonitorEntry> entries = new ArrayList<>();
        for (TestOutcome o : outcomes) {
            if (o.getApiMonitorFailures() == null) {
                continue;
            }
            String feature = o.getUserStory() == null || o.getUserStory().getStoryName() == null
                    ? "(unknown feature)" : o.getUserStory().getStoryName();
            for (String line : o.getApiMonitorFailures()) {
                entries.add(new MonitorEntry(o.getName(), feature, line,
                        o.getOutcomeId() + "/" + o.getOutcomeId() + ".html"));
            }
        }
        return entries;
    }

    /** feature 分组视图（无 owner 维度，2026-10-16 决策）；items 已转 Map（record 模板兼容）。 */
    static List<Map<String, Object>> monitorGroups(List<MonitorEntry> entries) {
        Map<String, List<MonitorEntry>> byFeature = new LinkedHashMap<>();
        for (MonitorEntry e : entries) {
            byFeature.computeIfAbsent(e.feature(), f -> new ArrayList<>()).add(e);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<MonitorEntry>> e : byFeature.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("feature", e.getKey());
            g.put("items", entriesAsMaps(e.getValue()));
            groups.add(g);
        }
        return groups;
    }

    private static List<Map<String, Object>> entriesAsMaps(List<MonitorEntry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MonitorEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", e.line());
            m.put("scenario", e.scenario());
            m.put("feature", e.feature());
            m.put("link", e.link());
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- internals

    private static String process(String templateName, Map<String, Object> model)
            throws IOException, TemplateException {
        Template template = FREEMARKER.getTemplate(templateName);
        try (StringWriter sw = new StringWriter()) {
            template.process(model, sw);
            return sw.toString();
        }
    }
}

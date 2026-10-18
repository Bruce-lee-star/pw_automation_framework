package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 邮件用单页报告 {@code email-report.html}——<b>自包含单文件</b>，专为邮件发送设计。
 *
 * <p>与 {@link AggregateBuilder}（index）/ {@link HtmlRenderer}（per-case）的区别，即邮件客户端约束：
 * <ul>
 *   <li><b>无外部资源</b>：样式全部写在 {@code <style>} 内，不 {@code <link>} 任何 assets/*.css，
 *       不引用 favicon / 图片 / 字体——把单个 HTML 作为附件或贴入邮件正文即可原样渲染；</li>
 *   <li><b>无 JavaScript</b>：Outlook / Gmail 会剥离 {@code <script>}，故不做筛选/折叠等交互，
 *       所有内容服务端渲染完成（与 index.ftl 的 JS 筛选形成对比）；</li>
 *   <li><b>表格布局 + 行内样式</b>：Outlook 桌面端用 Word 引擎渲染，不支持 flex / grid / SVG，
 *       故环形图退化为分段条、布局一律 table，并对关键元素补 inline style。</li>
 * </ul>
 *
 * <p>口径与 {@code serenity-summary.html} 同源（复用 {@link SummaryHtmlRenderer#summaryModel}），
 * 只补充邮件所需的项目名 / 版本 / 生成时间 / 环境 / 失败明细 / feature 汇总。
 *
 * <p>fail-open（R4）：渲染失败只告警并降级为最小可读页，不影响其余产物与测试本身。
 */
public final class EmailReportRenderer {

    /** 邮件报告文件名（CI 邮件插件按此名收集；单文件自包含，可直接作附件）。 */
    public static final String EMAIL_FILE = "email-report.html";

    private static final Logger log = LoggerFactory.getLogger(EmailReportRenderer.class);

    /** 生成时间戳（钉 ROOT，避免不同机器 locale 漂移）。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);

    private static final Configuration FREEMARKER = new Configuration(Configuration.VERSION_2_3_34);

    static {
        FREEMARKER.setClassLoaderForTemplateLoading(EmailReportRenderer.class.getClassLoader(), "templates/");
        FREEMARKER.setDefaultEncoding(StandardCharsets.UTF_8.name());
        FREEMARKER.setLogTemplateExceptions(false);
        FREEMARKER.setLocale(Locale.ROOT);
    }

    private EmailReportRenderer() {
    }

    /** 写 {@code <dir>/email-report.html}；永不抛（失败降级最小页）。 */
    public static Path writeEmailReport(Path dir, List<TestOutcome> outcomes) {
        Path target = dir.resolve(EMAIL_FILE);
        try {
            String html = process("email-report.ftl", emailModel(outcomes));
            Files.createDirectories(dir);
            Files.writeString(target, html, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            log.warn("email report render failed, degrading to minimal page", t);
            try {
                Files.createDirectories(dir);
                Files.writeString(target, minimalEmail(outcomes), StandardCharsets.UTF_8);
            } catch (IOException io) {
                log.error("even minimal email report write failed", io);
            }
        }
        return target;
    }

    /** 测试用：渲染为字符串（不落盘）。 */
    public static String renderToString(List<TestOutcome> outcomes) throws IOException, TemplateException {
        return process("email-report.ftl", emailModel(outcomes));
    }

    /**
     * 邮件页模型：复用 {@link SummaryHtmlRenderer#summaryModel}（汇总口径与 serenity-summary 同源），
     * 补充邮件所需的项目名、版本、生成时间、环境、失败明细与 feature 汇总。
     */
    static Map<String, Object> emailModel(List<TestOutcome> outcomes) {
        Map<String, Object> model = new LinkedHashMap<>(SummaryHtmlRenderer.summaryModel(outcomes));
        List<Map<String, Object>> features = featureRows(outcomes);
        model.put("projectName", AggregateBuilder.projectName());
        model.put("reportVersion", AggregateBuilder.reportVersion(outcomes));
        model.put("generatedAt", STAMP.format(OffsetDateTime.now(ZoneOffset.UTC)));
        model.put("env", ReportView.environment());
        model.put("browsers", ReportView.browsers());
        model.put("failures", failureRows(outcomes));
        model.put("features", features);
        model.put("featureCount", features.size());
        return model;
    }

    /** 失败明细（邮件最关心的可执行信息）：用例名 / feature / 结果徽章 / 首行失败原因。 */
    private static List<Map<String, Object>> failureRows(List<TestOutcome> outcomes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SummaryHtmlRenderer.failureRows(outcomes)) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            String key = String.valueOf(r.get("result"));
            row.put("resultKey", key);
            row.put("resultLabel", ReportView.label(key));
            rows.add(row);
        }
        return rows;
    }

    /** feature 汇总行：邮件只给结论（场景数 / 通过 / 问题数 / 通过率）。 */
    private static List<Map<String, Object>> featureRows(List<TestOutcome> outcomes) {
        Map<String, List<TestOutcome>> byFeature = new LinkedHashMap<>();
        for (TestOutcome o : outcomes) {
            byFeature.computeIfAbsent(featureOf(o), f -> new ArrayList<>()).add(o);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<TestOutcome>> e : byFeature.entrySet()) {
            List<TestOutcome> list = e.getValue();
            int total = list.size();
            int pass = 0;
            int fail = 0;
            int err = 0;
            int pend = 0;
            for (TestOutcome o : list) {
                switch (ReportView.key(o.getResult())) {
                    case "PASS" -> pass++;
                    case "FAILURE" -> fail++;
                    case "ERROR" -> err++;
                    case "PENDING" -> pend++;
                    default -> {
                    }
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("feature", e.getKey());
            row.put("scenarioCount", total);
            row.put("passCount", pass);
            row.put("failCount", fail);
            row.put("errorCount", err);
            row.put("pendingCount", pend);
            row.put("issueCount", fail + err + pend);
            row.put("passPct", ReportView.pct(pass, total));
            rows.add(row);
        }
        return rows;
    }

    private static String featureOf(TestOutcome o) {
        UserStory story = o.getUserStory();
        return story == null || story.getStoryName() == null ? "(unknown feature)" : story.getStoryName();
    }

    private static String process(String templateName, Map<String, Object> model)
            throws IOException, TemplateException {
        Template template = FREEMARKER.getTemplate(templateName);
        try (StringWriter sw = new StringWriter()) {
            template.process(model, sw);
            return sw.toString();
        }
    }

    /** 兜底：无模板依赖的最小可读页（同样是自包含单文件）。 */
    static String minimalEmail(List<TestOutcome> outcomes) {
        List<TestOutcome> list = outcomes == null ? List.of() : outcomes;
        int failures = 0;
        for (TestOutcome o : list) {
            if (o.getResult() != null && o.getResult().isFailure()) {
                failures++;
            }
        }
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                + "<title>Test Report</title></head>"
                + "<body style=\"font-family:Arial,Helvetica,sans-serif;color:#1f2d3d\">"
                + "<h2>Test Report</h2>"
                + "<p>Scenarios: " + list.size() + " · Failed: " + failures + "</p>"
                + "<p>Full details: see index.html in the report directory.</p>"
                + "</body></html>";
    }
}

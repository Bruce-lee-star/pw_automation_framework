package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
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
 * <p>口径复用 {@link SummaryHtmlRenderer#summaryModel}（汇总口径与 {@code index.html} 同源；
 * 原 {@code serenity-summary.html} 已于 2026-10-22 删除），
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
     * 邮件页模型：复用 {@link SummaryHtmlRenderer#summaryModel}（汇总口径与 index 页同源），
     * 补充邮件所需的项目名、版本、生成时间、环境、失败明细与 feature 汇总。
     */
    static Map<String, Object> emailModel(List<TestOutcome> outcomes) {
        Map<String, Object> model = new LinkedHashMap<>(SummaryHtmlRenderer.summaryModel(outcomes));
        List<Map<String, Object>> features = featureRows(outcomes);
        model.put("projectName", AggregateBuilder.projectName());
        model.put("reportVersion", AggregateBuilder.reportVersion(outcomes));
        model.put("generatedAt", STAMP.format(OffsetDateTime.now(ZoneOffset.UTC)));
        //  运行窗口（generatedAt / startedAt / endedAt，人读格式）：邮件同样要在顶部给出
        //  "什么时候生成、测试何时开始/结束"（2026-10-22 用户要求，与三份站点报告同源）。
        model.put("run", SummaryHtmlRenderer.runWindow(outcomes));
        //  env / browsers / coverageFeatures / execTime 已随「Environment 节删除 +
        //  Functional Coverage 节删除」（2026-10-22 用户决定）从模型移除——feature 统计
        //  只保留 featureCount（顶部 Features KPI 卡），不再为已删除的节准备数据。
        List<Map<String, Object>> failureList = failureRows(outcomes);
        model.put("failures", failureList);
        model.put("featureCount", features.size());
        //  邮件页派生数据：耗时口径 / 高频失败 / 不稳定 feature / 失败分组 / 报告内链
        //  （口径沿用自旧 pw-reports 邮件摘要报告，其模板已于 2026-10-22 随模块合并一并删除）
        model.put("timings", timings(outcomes));
        model.put("frequentFailures", frequentFailures(outcomes));
        model.put("unstableFeatures", unstableFeatures(outcomes));
        model.put("failureGroups", failureGroups(failureList));
        model.put("links", reportLinks());
        //  CI / 构建信息（邮件页脚构建链接）；无 Jenkins/GitLab 变量时 present=false，不渲染
        model.put("ci", ReportView.ciBuild());
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

    /** 耗时口径（沿用旧邮件摘要 timings 表）：总时长 / 时钟 / 平均 / 最大 / 最小。 */
    private static Map<String, Object> timings(List<TestOutcome> outcomes) {
        //  与 index / feature 共用同一实现，避免三处口径漂移。
        return SummaryHtmlRenderer.timings(outcomes);
    }

    /**
     * 最常失败——按<b>失败类型</b>聚合（2026-10-22 用户要求：要能看出「到底哪种失败情况多」）。
     *
     * <p>类型判定优先级：{@code report.error.types} 配置的规则（匹配失败消息 / 异常类名关键字）
     * → 用例语义类别 {@code testFailureCause.errorType}（FAILURE / ERROR / COMPROMISED）
     * → 异常类简名。原实现按整段消息聚合，同类失败只要消息里有一处变量不同就会被拆成多条，
     * "哪种最多"根本看不出来。
     */
    private static List<Map<String, Object>> frequentFailures(List<TestOutcome> outcomes) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (TestOutcome o : outcomes) {
            if (o.getResult() == null || !o.getResult().isFailure()) {
                continue;
            }
            byType.merge(failureTypeOf(o), 1, Integer::sum);
        }
        return topCounts(byType);
    }

    /** 一条失败类型规则：类型名 + 关键字（已转小写）。 */
    private record ErrorTypeRule(String type, List<String> keywords) {
    }

    /** 解析 {@code report.error.types}（每次渲染读取，便于测试用系统属性覆盖）。 */
    private static List<ErrorTypeRule> errorTypeRules() {
        String raw = ConfigSource.resolve(ConfigKeys.REPORT_ERROR_TYPES.key(),
                ConfigKeys.REPORT_ERROR_TYPES.defaultValue());
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<ErrorTypeRule> rules = new ArrayList<>();
        for (String part : raw.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String type = part.substring(0, eq).trim();
            List<String> keywords = new ArrayList<>();
            for (String kw : part.substring(eq + 1).split("\\|")) {
                String k = kw.trim().toLowerCase(Locale.ROOT);
                if (!k.isEmpty()) {
                    keywords.add(k);
                }
            }
            if (!type.isEmpty() && !keywords.isEmpty()) {
                rules.add(new ErrorTypeRule(type, List.copyOf(keywords)));
            }
        }
        return List.copyOf(rules);
    }

    /**
     * 把一条失败用例归到可聚合的「类型」标签。
     *
     * <p>判定链：{@code report.error.types} 配置规则（用户自定义类型，最高优先级）
     * → {@link ErrorClassifier} 内置分类（异常类名 / 消息精确短语 / 语义类别，见分类器 javadoc）。
     * 聚合榜 {@link #frequentFailures} 与失败明细行 {@link #failureRows} 共用本方法——同一用例
     * 在「Most Frequent Failure Types」与「Full Failure List」看到的分组一致。
     */
    private static String failureTypeOf(TestOutcome o) {
        String message = failureMessage(o);
        String classname = o.getTestFailureClassname() == null ? "" : o.getTestFailureClassname();
        String haystack = (message + "\n" + classname).toLowerCase(Locale.ROOT);
        for (ErrorTypeRule rule : errorTypeRules()) {
            for (String kw : rule.keywords()) {
                if (haystack.contains(kw)) {
                    return rule.type();
                }
            }
        }
        return ErrorClassifier.classify(o).name();
    }

    /** 最不稳定 Feature（对齐 Serenity 2.0.84 home.ftl 的 Most Unstable Features）：按 feature 聚合失败数、降序、前 5，并附失败百分比。 */
    private static List<Map<String, Object>> unstableFeatures(List<TestOutcome> outcomes) {
        Map<String, Integer> failByFeat = new LinkedHashMap<>();
        Map<String, Integer> totalByFeat = new LinkedHashMap<>();
        for (TestOutcome o : outcomes) {
            String f = featureOf(o);
            totalByFeat.merge(f, 1, Integer::sum);
            if (o.getResult() != null && o.getResult().isFailure()) {
                failByFeat.merge(f, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        failByFeat.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5).forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    int total = totalByFeat.getOrDefault(e.getKey(), 0);
                    int pct = total == 0 ? 0 : Math.round(e.getValue() * 100f / total);
                    m.put("failurePercentage", pct);
                    rows.add(m);
                });
        return rows;
    }

    private static String failureMessage(TestOutcome o) {
        if (o.getTestFailureCause() != null && o.getTestFailureCause().getMessage() != null) {
            return o.getTestFailureCause().getMessage();
        }
        return o.getTestFailureMessage() == null ? "(no message)" : o.getTestFailureMessage();
    }

    private static List<Map<String, Object>> topCounts(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) {
            if (rows.size() >= 5) {
                break;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", e.getKey());
            r.put("count", e.getValue());
            rows.add(r);
        }
        return rows;
    }

    /** 报告内链（查看完整报告 / 下载 CSV）：随 baseHref 绝对化，缺省相对（index / csv 与本报同目录）。 */
    private static Map<String, Object> reportLinks() {
        String base = PwReportsConfig.baseUrl();
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("fullReport", base + "index.html");
        l.put("csv", base + "serenity-summary.csv");
        return l;
    }

    /** 按 feature 分组的失败列表（沿用旧邮件摘要 Full Failure List），保留首现顺序。 */
    private static List<Map<String, Object>> failureGroups(List<Map<String, Object>> failures) {
        Map<String, List<Map<String, Object>>> byFeat = new LinkedHashMap<>();
        for (Map<String, Object> f : failures) {
            String feat = String.valueOf(f.getOrDefault("feature", "(unknown feature)"));
            byFeat.computeIfAbsent(feat, k -> new ArrayList<>()).add(f);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byFeat.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("feature", e.getKey());
            g.put("scenarios", e.getValue());
            groups.add(g);
        }
        return groups;
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

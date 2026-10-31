package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * index.html 聚合页（dashboard）——按 result 分组汇总 + 全用例链接。
 */
public final class AggregateBuilder {

    private static final Logger log = LoggerFactory.getLogger(AggregateBuilder.class);

    private static final Configuration FREEMARKER = new Configuration(Configuration.VERSION_2_3_34);

    static {
        FREEMARKER.setClassLoaderForTemplateLoading(AggregateBuilder.class.getClassLoader(), "templates/");
        FREEMARKER.setDefaultEncoding(StandardCharsets.UTF_8.name());
        FREEMARKER.setLogTemplateExceptions(false);
        FREEMARKER.setLocale(Locale.ROOT);
    }

    private AggregateBuilder() {
    }

    /** 渲染并写 {@code <dir>/index.html}；失败降级为纯链接列表。 */
    public static Path writeIndex(Path dir, List<TestOutcome> outcomes) {
        Path target = dir.resolve("index.html");
        try {
            Template template = FREEMARKER.getTemplate("index.ftl");
            String html;
            try (StringWriter sw = new StringWriter()) {
                template.process(indexModel(outcomes), sw);
                html = sw.toString();
            }
            Files.createDirectories(dir);
            Files.writeString(target, html, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            log.warn("index render failed, degrading to plain list", t);
            try {
                Files.createDirectories(dir);
                Files.writeString(target, plainIndex(outcomes), StandardCharsets.UTF_8);
            } catch (IOException io) {
                log.error("even plain index write failed", io);
            }
        }
        return target;
    }

    /** reportVersion：从首个用例取（全用例同值），空列表回退 1。index / 邮件报告共用。 */
    static int reportVersion(List<TestOutcome> outcomes) {
        return outcomes.isEmpty() ? 1 : outcomes.get(0).getReportVersion();
    }

    private static String plainIndex(List<TestOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>Test report</title>")
                .append("<link rel=\"stylesheet\" href=\"assets/report.css\"></head><body><h1>Test report</h1><ul>");
        for (TestOutcome o : outcomes) {
            String result = o.getResult() == null ? "UNKNOWN" : o.getResult().name();
            sb.append("<li class=\"result-").append(result.toLowerCase(Locale.ROOT)).append("\">")
                    .append("<a href=\"").append(HtmlRenderer.esc(o.getOutcomeId()))
                    .append("/").append(HtmlRenderer.esc(o.getOutcomeId())).append(".html\">")
                    .append(HtmlRenderer.esc(o.getName())).append("</a> — ").append(result)
                    .append("</li>");
        }
        sb.append("</ul></body></html>");
        return sb.toString();
    }

    /** index 模型：totals + feature 分组（含内联步骤）+ 标签云 + 成功率 + 项目名。 */
    static Map<String, Object> indexModel(List<TestOutcome> outcomes) {
        Map<String, Object> totals = JsonWriter.totals(outcomes);
        List<Map<String, Object>> groups = SummaryHtmlRenderer.featureGroups(outcomes);
        Map<String, TestOutcome> byId = new LinkedHashMap<>();
        for (TestOutcome o : outcomes) {
            byId.put(o.getOutcomeId(), o);
        }
        for (Map<String, Object> g : groups) {
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) g.get("scenarios");
            for (Map<String, Object> s : scenarios) {
                String link = (String) s.get("link");
                //  link 形如 <outcomeId>/<outcomeId>.html（2026-10-21 迁移），取末段文件名作 id
                String id = link.substring(link.lastIndexOf('/') + 1).replace(".html", "");
                s.put("steps", buildSteps(byId.get(id)));
            }
            featureStats(g, outcomes);
        }
        int count = outcomes.size();
        int failures = (int) outcomes.stream()
                .filter(o -> o.getResult() != null && o.getResult().isFailure()).count();
        int errorCount = ReportView.count(outcomes, "ERROR");
        int passCount = ReportView.count(outcomes, "PASS");
        int pendingCount = ReportView.count(outcomes, "PENDING");
        int skippedCount = ReportView.count(outcomes, "SKIPPED")
                + ReportView.count(outcomes, "IGNORED")
                + ReportView.count(outcomes, "UNDEFINED");
        int autoCount = (int) outcomes.stream().filter(o -> !o.isManual()).count();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("passCount", passCount);
        model.put("failCount", failures - errorCount);
        model.put("errorCount", errorCount);
        model.put("pendingCount", pendingCount);
        model.put("skippedCount", skippedCount);
        model.put("autoCount", autoCount);
        model.put("manualCount", count - autoCount);
        model.put("autoPct", ReportView.pct(autoCount, count));
        model.put("manualPct", ReportView.pct(count - autoCount, count));
        model.put("passPct", ReportView.pct(passCount, count));
        model.put("failPct", ReportView.pct(failures - errorCount, count));
        model.put("errorPct", ReportView.pct(errorCount, count));
        model.put("pendingPct", ReportView.pct(pendingCount, count));
        model.put("skippedPct", ReportView.pct(skippedCount, count));
        model.put("issuePct", ReportView.pct(failures + pendingCount, count));
        model.put("mixSegments", ReportView.segments(
                "auto", "#3b82f6", autoCount,
                "manual", "#0d9488", count - autoCount));
        model.put("resultSegments", ReportView.segments(
                "PASS", "#16a34a", passCount,
                "FAILURE", "#dc2626", failures - errorCount,
                "ERROR", "#ea580c", errorCount,
                "PENDING", "#d97706", pendingCount,
                "SKIPPED", "#94a3b8", skippedCount));
        model.put("durationBuckets", ReportView.durationBuckets(outcomes));
        model.put("env", ReportView.environment());
        model.put("browsers", ReportView.browsers());
        //  CI / 构建信息（顶栏构建链接）；本地运行无 Jenkins/GitLab 变量时 present=false，不渲染
        model.put("ci", ReportView.ciBuild());
        model.put("outcomes", outcomes);
        model.put("totals", totals);
        model.put("reportVersion", reportVersion(outcomes));
        model.put("count", count);
        model.put("failedCount", failures);
        model.put("allPassed", count > 0 && failures == 0);
        model.put("hasFailures", failures > 0);
        model.put("successRate", SummaryHtmlRenderer.successRate(count, failures));
        model.put("totalDurationText",
                SummaryHtmlRenderer.formatDuration(((Number) totals.get("duration")).longValue()));
        //  执行耗时汇总（Total / Clock / Average / Max / Min）：与邮件报告同口径，
        //  index / feature 都要展示（2026-10-22 用户反馈两块都缺"整个 case 执行总时长"）。
        model.put("timings", SummaryHtmlRenderer.timings(outcomes));
        //  运行窗口（generatedAt / startedAt / endedAt）：顶栏除报告名外的时间信息
        //  （2026-10-22 用户要求，index / feature / per-case / 邮件四处共用）。
        model.put("run", SummaryHtmlRenderer.runWindow(outcomes));
        model.put("groups", groups);
        model.put("tags", SummaryHtmlRenderer.tagCounts(outcomes));
        model.put("projectName", projectName());
        return model;
    }

    /** Feature 行统计（新设计：总览表需自动/手工、通过/失败/出错/待定、通过率与详情页链接）。 */
    private static void featureStats(Map<String, Object> g, List<TestOutcome> outcomes) {
        String feature = String.valueOf(g.get("feature"));
        int total = ((List<?>) g.get("scenarios")).size();
        int auto = 0;
        int manual = 0;
        int pass = 0;
        int fail = 0;
        int err = 0;
        int pend = 0;
        for (TestOutcome o : outcomes) {
            String f = o.getUserStory() == null || o.getUserStory().getStoryName() == null
                    ? "(unknown feature)" : o.getUserStory().getStoryName();
            if (!feature.equals(f)) {
                continue;
            }
            if (o.isManual()) {
                manual++;
            } else {
                auto++;
            }
            switch (ReportView.key(o.getResult())) {
                case "PASS" -> pass++;
                case "FAILURE" -> fail++;
                case "ERROR" -> err++;
                case "PENDING" -> pend++;
                default -> {
                }
            }
        }
        g.put("scenarioCount", total);
        g.put("autoCount", auto);
        g.put("manualCount", manual);
        g.put("passCount", pass);
        g.put("failCount", fail);
        g.put("errorCount", err);
        g.put("pendingCount", pend);
        g.put("issueCount", fail + err + pend);
        g.put("passRate", ReportView.pct(pass, total));
        g.put("link", ReportView.featureSlug(feature) + ".html");
    }

    /** 把用例的步骤树拍平为带层级深度的列表（仅取渲染所需字段）。 */
    static List<Map<String, Object>> buildSteps(TestOutcome o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o != null) {
            flattenSteps(o.getTestSteps(), out, 0);
        }
        return out;
    }

    private static void flattenSteps(List<TestStep> steps, List<Map<String, Object>> out, int depth) {
        if (steps == null) {
            return;
        }
        for (TestStep st : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", st.getDescription());
            m.put("result", st.getResult() == null ? null : st.getResult().name());
            m.put("depth", depth);
            String shot = st.getScreenshot();
            m.put("hasScreenshot", shot != null && !shot.isEmpty());
            out.add(m);
            flattenSteps(st.getChildren(), out, depth + 1);
        }
    }



    /**
     * 报告标题：读框架配置 {@code project.report.name}（历史键 {@code framework.project.name} 仍作兜底），
     * 缺省回退 {@code Test Report}。index / 邮件报告共用。
     *
     * <p>统一走 {@link ConfigReader#resolve}：系统属性 → 环境变量 → SPI 配置源 → 默认值，
     * 与框架其它配置读取链路一致，保证「所有报告使用同一个 name」。</p>
     */
    static String projectName() {
        String name = ConfigReader.resolve(ConfigRegistry.WEB_FRAMEWORK_PROJECT_NAME.key(),
                ConfigRegistry.WEB_FRAMEWORK_PROJECT_NAME.defaultValue());
        if (name == null || name.isBlank()) {
            // 兼容旧桥接：早期实现直接把项目名塞进该系统属性
            name = System.getProperty("framework.project.name");
        }
        //  <b>未配置 → Test Report</b>：不再拿框架自带的默认值冒充"用户配的名字"
        //  （2026-10-22 用户要求：只显示配置的，没配置就显示 Test Report）。
        return name == null || name.isBlank() ? "Test Report" : name;
    }

    /** 测试用：渲染为字符串。 */
    public static String renderIndexToString(List<TestOutcome> outcomes) throws IOException, TemplateException {
        Template template = FREEMARKER.getTemplate("index.ftl");
        try (StringWriter sw = new StringWriter()) {
            template.process(indexModel(outcomes), sw);
            return sw.toString();
        }
    }
}

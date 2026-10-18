package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.console.ConsoleClassifier;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * per-case HTML 渲染（FreeMarker，自有模板）——JSON 之后的降级出口（R4）。
 *
 * <p>降级路径：模板异常 / IO 失败 → {@link #minimalHtml} 兜底，保证用例永远有可看页面；
 * JSON 已在 CaseFinished 时先行落盘，HTML 丢失不损失真相源。
 */
public final class HtmlRenderer {

    private static final Logger log = LoggerFactory.getLogger(HtmlRenderer.class);

    private static final Configuration FREEMARKER = new Configuration(Configuration.VERSION_2_3_34);

    static {
        FREEMARKER.setClassLoaderForTemplateLoading(HtmlRenderer.class.getClassLoader(), "templates/");
        FREEMARKER.setDefaultEncoding(StandardCharsets.UTF_8.name());
        //  test-outcome.ftl 的 "Play trace" 链接用 ${outcome.tracePath?url}：?url 内置需要 output_encoding
        //    （或 url_escaping_charset）才能工作，否则渲染抛 _TemplateModelException → 整页降级 minimalHtml。
        FREEMARKER.setOutputEncoding(StandardCharsets.UTF_8.name());
        FREEMARKER.setLogTemplateExceptions(false);
        FREEMARKER.setLocale(Locale.ROOT);
    }

    private HtmlRenderer() {
    }

    /** 渲染并写 {@code <dir>/<outcomeId>.html}；失败不抛（降级最小 HTML）。 */
    public static Path renderOutcome(Path dir, TestOutcome outcome) {
        Path target = dir.resolve(outcome.getOutcomeId() + ".html");
        try {
            Template template = FREEMARKER.getTemplate("test-outcome.ftl");
            String html;
            try (StringWriter sw = new StringWriter()) {
                template.process(model(outcome), sw);
                html = sw.toString();
            }
            Files.createDirectories(dir);
            Files.writeString(target, html, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            log.warn("HTML render failed for {}, degrading to minimal page", outcome.getOutcomeId(), t);
            try {
                Files.createDirectories(dir);
                Files.writeString(target, minimalHtml(outcome), StandardCharsets.UTF_8);
            } catch (IOException io) {
                log.error("even minimal HTML write failed for {}", outcome.getOutcomeId(), io);
            }
        }
        return target;
    }

    /** 测试/快照门禁用：渲染为字符串（不落盘）。 */
    public static String renderToString(TestOutcome outcome) throws IOException, TemplateException {
        Template template = FREEMARKER.getTemplate("test-outcome.ftl");
        try (StringWriter sw = new StringWriter()) {
            template.process(model(outcome), sw);
            return sw.toString();
        }
    }

    /**
     * 渲染模型：{@code outcome} + 回 Feature 页的 {@code featureName/featureLink}
     * + 控制台分组（{@code consoleGroups} 用例级、{@code stepConsole} 步骤级）。
     */
    static Map<String, Object> model(TestOutcome outcome) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("outcome", outcome);

        String featureName = outcome.getUserStory() == null ? null : outcome.getUserStory().getStoryName();
        if (featureName != null && !featureName.isBlank()) {
            model.put("featureName", featureName);
            model.put("featureLink", ReportView.featureSlug(featureName) + ".html");
        }

        model.put("consoleGroups",
                ConsoleClassifier.classify(outcome.getConsoleOutput(), outcome.getConsoleError()));
        model.put("stepConsole", stepConsole(outcome.getTestSteps()));
        model.put("baseHref", PwReportsConfig.baseUrl());
        model.put("traceViewerBaseUrl", PwReportsConfig.traceViewerBaseUrl());
        return model;
    }

    /** 步骤级控制台分组：stepId → 分组；无输出的步骤不进 map。 */
    private static Map<String, List<Map<String, Object>>> stepConsole(List<TestStep> steps) {
        Map<String, List<Map<String, Object>>> map = new LinkedHashMap<>();
        collect(steps, map);
        return map;
    }

    private static void collect(List<TestStep> steps, Map<String, List<Map<String, Object>>> map) {
        if (steps == null) {
            return;
        }
        for (TestStep step : steps) {
            if (step == null) {
                continue;
            }
            if (step.getStepId() != null) {
                List<Map<String, Object>> groups =
                        ConsoleClassifier.classify(step.getConsoleOutput(), step.getConsoleError());
                if (!groups.isEmpty()) {
                    map.put(step.getStepId(), groups);
                }
            }
            collect(step.getChildren(), map);
        }
    }

    /** 渲染降级兜底页——无模板依赖，信息量取 outcome 关键字段。 */
    public static String minimalHtml(TestOutcome outcome) {
        String result = outcome.getResult() == null ? "UNKNOWN" : outcome.getResult().name();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
                .append("<title>").append(esc(outcome.getName())).append("</title>")
                .append("<link rel=\"stylesheet\" href=\"assets/report.css\"></head><body>")
                .append("<h1 class=\"result-").append(result.toLowerCase(Locale.ROOT)).append("\">")
                .append(esc(outcome.getName())).append(" — ").append(result).append("</h1>");
        if (outcome.getTestFailureCause() != null) {
            sb.append("<pre class=\"failure\">").append(esc(outcome.getTestFailureCause().getMessage()))
                    .append("</pre>");
        }
        sb.append("<p>Full data: ").append(esc(outcome.getOutcomeId())).append(".json</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

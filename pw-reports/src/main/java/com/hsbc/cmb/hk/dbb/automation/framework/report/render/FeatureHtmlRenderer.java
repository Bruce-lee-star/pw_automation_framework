package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Feature 详情页（新设计：Overview → Feature → Scenario 两级下钻）。
 *
 * <p>每个 feature 输出一个 {@code feature-<slug>.html}，文件名由 {@link ReportView#featureSlug(String)}
 * 生成，并已写入分组的 {@code link} 字段，故总览页链接与此处产出天然一致。</p>
 *
 * <p>统计区与总览页一致（构成 / 结果 / 耗时）：耗时直方图使用<b>本 feature 自己的</b>
 * {@code durationBuckets}（由本次 feature 的用例重新分桶），而非沿用全量桶。</p>
 *
 * <p>fail-open：单页渲染失败只告警，不影响其余页面与汇总产物。</p>
 */
public final class FeatureHtmlRenderer {

    private static final Logger log = LoggerFactory.getLogger(FeatureHtmlRenderer.class);

    private static final Configuration FREEMARKER = new Configuration(Configuration.VERSION_2_3_34);

    static {
        FREEMARKER.setClassLoaderForTemplateLoading(FeatureHtmlRenderer.class.getClassLoader(), "templates/");
        FREEMARKER.setDefaultEncoding(StandardCharsets.UTF_8.name());
        FREEMARKER.setLogTemplateExceptions(false);
        FREEMARKER.setLocale(Locale.ROOT);
    }

    private FeatureHtmlRenderer() {
    }

    /** 渲染全部 feature 页，返回写出的文件路径（失败时返回已成功的部分）。 */
    public static List<Path> writeFeatures(Path dir, List<TestOutcome> outcomes) {
        List<Path> written = new ArrayList<>();
        if (outcomes == null || outcomes.isEmpty()) {
            return written;
        }
        try {
            Map<String, Object> index = AggregateBuilder.indexModel(outcomes);
            List<Map<String, Object>> groups = castGroups(index.get("groups"));
            Template template = FREEMARKER.getTemplate("feature.ftl");
            Files.createDirectories(dir);
            for (Map<String, Object> g : groups) {
                Map<String, Object> model = new LinkedHashMap<>(index);
                model.putAll(featureModel(g));
                // 本 feature 自己的耗时分布与耗时汇总（覆盖继承来的全量口径）。
                List<TestOutcome> featureList = featureOutcomes(outcomes, String.valueOf(g.get("feature")));
                model.put("durationBuckets", ReportView.durationBuckets(featureList));
                model.put("timings", SummaryHtmlRenderer.timings(featureList));
                //  顶栏时间信息用【本 feature 自己】的执行窗口（该 feature 全部用例的最早开始 /
                //  最晚结束），不再是整个运行的窗口——2026-10-22 用户核对时发现 feature 页与
                //  index 页的 start/end 完全一样（同源全量口径），feature 页应反映自己的范围。
                //  口径分工：index = 整个运行；feature = 该 feature；per-case = 该用例；邮件 = 整个运行。
                model.put("run", SummaryHtmlRenderer.runWindow(featureList));
                Files.writeString(dir.resolve(String.valueOf(g.get("link"))),
                        render(template, model), StandardCharsets.UTF_8);
                written.add(dir.resolve(String.valueOf(g.get("link"))));
            }
        } catch (Throwable t) {
            log.warn("feature page render failed (non-fatal)", t);
        }
        return written;
    }

    private static Map<String, Object> featureModel(Map<String, Object> g) {
        int total = num(g.get("scenarioCount"));
        int pass = num(g.get("passCount"));
        int fail = num(g.get("failCount"));
        int err = num(g.get("errorCount"));
        int pend = num(g.get("pendingCount"));
        int auto = num(g.get("autoCount"));
        int manual = num(g.get("manualCount"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("featureName", g.get("feature"));
        m.put("scenarios", g.get("scenarios"));
        m.put("scenarioCount", total);
        m.put("passCount", pass);
        m.put("failCount", fail);
        m.put("errorCount", err);
        m.put("pendingCount", pend);
        m.put("autoCount", auto);
        m.put("manualCount", manual);
        m.put("issueCount", num(g.get("issueCount")));
        m.put("passRate", g.get("passRate"));
        m.put("passPct", ReportView.pct(pass, total));
        m.put("failPct", ReportView.pct(fail, total));
        m.put("errorPct", ReportView.pct(err, total));
        m.put("pendingPct", ReportView.pct(pend, total));
        m.put("autoPct", ReportView.pct(auto, total));
        m.put("manualPct", ReportView.pct(manual, total));
        m.put("baseHref", PwReportsConfig.baseUrl());
        m.put("traceViewerBaseUrl", PwReportsConfig.traceViewerBaseUrl());
        //  CI / 构建信息（顶栏构建链接）；per-case / feature / index / 邮件四处同源
        m.put("ci", ReportView.ciBuild());
        m.put("mixSegments", ReportView.segments(
                "auto", "#3b82f6", auto,
                "manual", "#0d9488", manual));
        m.put("resultSegments", ReportView.segments(
                "PASS", "#16a34a", pass,
                "FAILURE", "#dc2626", fail,
                "ERROR", "#ea580c", err,
                "PENDING", "#d97706", pend));
        return m;
    }

    /** 取属于指定 feature 的用例（与 {@code featureGroups} 的分组口径一致）。 */
    private static List<TestOutcome> featureOutcomes(List<TestOutcome> outcomes, String feature) {
        List<TestOutcome> list = new ArrayList<>();
        for (TestOutcome o : outcomes) {
            if (feature.equals(featureOf(o))) {
                list.add(o);
            }
        }
        return list;
    }

    private static String featureOf(TestOutcome o) {
        var story = o.getUserStory();
        return story == null || story.getStoryName() == null ? "(unknown feature)" : story.getStoryName();
    }

    private static String render(Template template, Map<String, Object> model) throws Exception {
        try (StringWriter sw = new StringWriter()) {
            template.process(model, sw);
            return sw.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castGroups(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
    }

    private static int num(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}

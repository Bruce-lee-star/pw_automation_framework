package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportRegistry;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 定位健康度<b>独立页</b>渲染器：产出 {@code locator-health.html}。
 *
 * <p><b>为什么不进主报告</b>（2026-11-06 用户决定）：{@code index.html} 是「本轮结果」的结论页，
 * {@code email-report.html} 是邮件摘要；定位健康度属「质量信号」而非结果，混进去会让两份核心
 * 产物同时变重。故本页单独产出，与 {@code monitor-failures.html} 同口径——<b>无采集数据时不落文件</b>
 * （文件存在即有内容），不影响既有页面与 golden 基线。
 *
 * <p>数据经 pw-core 的 {@code LocatorHealthReportSink} SPI 取回（pw-web-ui 采集），
 * 报告模块不反向依赖 web 模块；渲染失败只记 WARN，绝不影响主报告与测试本身。
 */
public final class LocatorHealthHtmlRenderer {

    /** 产物文件名。 */
    public static final String LOCATOR_HEALTH_FILE = "locator-health.html";

    /** 用量表展示条数。 */
    private static final int TOP_USAGE = 10;

    private static final Logger log = LoggerFactory.getLogger(LocatorHealthHtmlRenderer.class);

    private static final Configuration FREEMARKER = new Configuration(Configuration.VERSION_2_3_34);

    static {
        FREEMARKER.setClassLoaderForTemplateLoading(
                LocatorHealthHtmlRenderer.class.getClassLoader(), "templates/");
        FREEMARKER.setDefaultEncoding(StandardCharsets.UTF_8.name());
        FREEMARKER.setLogTemplateExceptions(false);
        FREEMARKER.setLocale(Locale.ROOT);
    }

    private LocatorHealthHtmlRenderer() {
    }

    /**
     * 写 {@code <dir>/locator-health.html}。
     *
     * @param dir      报告目录
     * @param outcomes 本轮用例（仅用于顶栏的项目名 / 运行窗口 / CI 徽标，与健康度数据本身无关）
     * @return 实际写入路径；<b>无采集数据时返回 {@code null} 且不落文件</b>
     */
    public static Path write(Path dir, List<TestOutcome> outcomes) {
        LocatorHealthReportData health = LocatorHealthReportRegistry.collect();
        if (!health.hasContent()) {
            return null;
        }
        Path target = dir.resolve(LOCATOR_HEALTH_FILE);
        try {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("baseHref", PwReportsConfig.baseUrl());
            model.put("projectName", AggregateBuilder.projectName());
            model.put("run", SummaryHtmlRenderer.runWindow(outcomes));
            model.put("ci", ReportView.ciBuild());
            model.put("health", health);
            model.put("topUsage", topUsage(health));
            Template template = FREEMARKER.getTemplate("locator-health.ftl");
            String html;
            try (StringWriter sw = new StringWriter()) {
                template.process(model, sw);
                html = sw.toString();
            }
            Files.createDirectories(dir);
            Files.writeString(target, html, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 旁路产物：失败不影响主报告与测试结果
            log.warn("locator-health HTML render failed (main report unaffected)", t);
            return null;
        }
        return target;
    }

    /**
     * 用量 TOP N（按 uses 降序，同量按选择器字典序稳定排序）。
     *
     * <p>采集快照本身按「违例降序 → 用量降序」排（面向"最不稳定"），本页用量表需要另一个视角，
     * 故在此重排，模板保持零逻辑。
     */
    static List<LocatorHealthEntry> topUsage(LocatorHealthReportData health) {
        return health.getSelectors().stream()
                .sorted(Comparator.comparingLong(LocatorHealthEntry::getUses).reversed()
                        .thenComparing(LocatorHealthEntry::getSelector))
                .limit(TOP_USAGE)
                .toList();
    }
}

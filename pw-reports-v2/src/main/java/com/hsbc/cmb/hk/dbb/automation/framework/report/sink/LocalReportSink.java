package com.hsbc.cmb.hk.dbb.automation.framework.report.sink;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseStartedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.RunFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;

import com.hsbc.cmb.hk.dbb.automation.framework.report.render.AggregateBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.CsvWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.EmailReportRenderer;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.FeatureHtmlRenderer;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.HtmlRenderer;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.OutlineAggregator;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JunitXmlWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.StaticAssets;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.SummaryHtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地文件出口——JSON 真相源 + HTML 展示 + index + 静态资产。
 *
 * <p>时序（R4 降级链）：
 * <ol>
 *   <li>{@code CaseFinished} → <b>立即写 per-case JSON</b>（真相源实时保，CI 超时被杀也不丢已完成用例）；</li>
 *   <li>{@code RunFinished} → sweep 补写漏网 JSON → 逐用例渲染 HTML（失败降级最小页）
 *       → serenity-summary.json → index.html → serenity-summary.html →
 *       email-report.html（邮件用单页自包含报告）→
 *       monitor-failures.html（无监控失败不落文件）→ assets；</li>
 *   <li>每项独立 try/catch，单项失败不影响其余项与测试本身。</li>
 * </ol>
 */
public final class LocalReportSink implements ReportSink {

    private static final Logger log = LoggerFactory.getLogger(LocalReportSink.class);

    private final PwReportsConfig config;
    private final Set<String> writtenIds = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger errors =
            new java.util.concurrent.atomic.AtomicInteger();

    public LocalReportSink(PwReportsConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public void accept(ReportEvent event) {
        switch (event) {
            case CaseStartedEvent ignored -> {
                // JSON 在 CaseFinished 落（事件携带全量数据）；此处无操作
            }
            case CaseFinishedEvent e -> writeOutcomeJson(e.outcome());
            case RunFinishedEvent e -> finishAll(e.outcomes());
        }
    }

    // ---------------------------------------------------------------- internals

    private void writeOutcomeJson(TestOutcome outcome) {
        if (!writtenIds.add(outcome.getOutcomeId())) {
            return; // 幂等：real-time 已写 / sweep 不重写
        }
        try {
            JsonWriter.writeOutcome(dir(), outcome);
        } catch (Throwable t) {
            errors.incrementAndGet();
            log.error("per-case JSON write failed for {} (json is the source of truth)",
                    outcome.getOutcomeId(), t);
        }
    }

    private void finishAll(List<TestOutcome> outcomes) {
        // Scenario Outline 折叠（B）：聚合父 outcome 取代行集合进入汇总/index/CSV/JUnit/history；
        // 行 outcomes 的 per-case JSON/HTML/monitor-failures 仍照常产出（保 real-time 产物与父页面跳转目标）。
        List<TestOutcome> aggregated = OutlineAggregator.aggregate(outcomes);

        // sweep 补写漏网 JSON：先写 rows + standalone，再写聚合父（父含 examples，rows 已写则不重写）
        for (TestOutcome o : outcomes) {
            writeOutcomeJson(o); // sweep：未写过的一补（防御 CaseFinished 丢失）
        }
        for (TestOutcome o : aggregated) {
            writeOutcomeJson(o);
        }
        // per-case HTML：rows + standalone（父页面跳转目标）+ 聚合父页面
        for (TestOutcome o : outcomes) {
            try {
                HtmlRenderer.renderOutcome(dir(), o);
            } catch (Throwable t) {
                errors.incrementAndGet();
                log.warn("per-case HTML render failed for {}", o.getOutcomeId(), t);
            }
        }
        for (TestOutcome o : aggregated) {
            try {
                HtmlRenderer.renderOutcome(dir(), o);
            } catch (Throwable t) {
                errors.incrementAndGet();
                log.warn("per-case HTML render failed for {}", o.getOutcomeId(), t);
            }
        }
        try {
            JsonWriter.writeSummary(dir(), aggregated);
        } catch (Throwable t) {
            errors.incrementAndGet();
            log.error("serenity-summary.json write failed", t);
        }
        try {
            AggregateBuilder.writeIndex(dir(), aggregated);
        } catch (Throwable t) {
            errors.incrementAndGet();
            log.warn("index.html write failed", t);
        }
        try {
            // 新设计第二级：每个 feature 一页（总览页的 Feature 行链到这里）
            FeatureHtmlRenderer.writeFeatures(dir(), aggregated);
        } catch (Throwable t) {
            log.warn("feature pages write failed (non-fatal)", t);
        }
        try {
            SummaryHtmlRenderer.writeSummary(dir(), aggregated);
        } catch (Throwable t) {
            errors.incrementAndGet();
            log.warn("serenity-summary.html write failed", t);
        }
        try {
            // 邮件用单页报告：自包含（无外部 CSS/JS），可直接作附件或贴入正文
            EmailReportRenderer.writeEmailReport(dir(), aggregated);
        } catch (Throwable t) {
            log.warn("email-report.html write failed (non-fatal)", t);
        }
        try {
            // 无监控失败时不落文件（对齐旧版：文件存在即有失败）；
            // monitor 失败挂在 rows 上，故用原始 outcomes（聚合父不携带 apiMonitorFailures）
            SummaryHtmlRenderer.writeMonitorFailures(dir(), outcomes);
        } catch (Throwable t) {
            log.warn("monitor-failures.html write failed (non-fatal)", t);
        }
        try {
            CsvWriter.writeCsv(dir(), aggregated);
        } catch (Throwable t) {
            log.warn("serenity-summary.csv write failed (non-fatal)", t);
        }
        try {
            JunitXmlWriter.writeXml(dir(), aggregated);
        } catch (Throwable t) {
            log.warn("serenity-junit.xml write failed (non-fatal)", t);
        }
        try {
            StaticAssets.copyAssets(dir());
        } catch (Throwable t) {
            log.warn("static asset copy failed", t);
        }
        if (errors.get() > 0) {
            log.warn("LocalReportSink completed with {} error(s); JSON preserved where written", errors.get());
        }
    }

    private Path dir() {
        return config.getOutputDir();
    }

    /** 测试用：已写 JSON 的 outcomeId 集合。 */
    public Set<String> writtenIds() {
        return Set.copyOf(writtenIds);
    }
}

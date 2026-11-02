package com.hsbc.cmb.hk.dbb.automation.framework.report.sink;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 本地文件出口——JSON 真相源 + HTML 展示 + index + 静态资产。
 *
 * <p>时序（R4 降级链）：
 * <ol>
 *   <li>{@code CaseFinished} → <b>立即写 per-case JSON</b>（真相源实时保，CI 超时被杀也不丢已完成用例）；</li>
 *   <li>{@code RunFinished} → sweep 补写漏网 JSON → 逐用例渲染 HTML（失败降级最小页）
 *       → pw-summary.json → index.html → feature-*.html →
 *       email-report.html（邮件用单页自包含报告）→
 *       monitor-failures.html（无监控失败不落文件）→ assets；</li>
 *       <p>（{@code serenity-summary.html} 已于 2026-10-22 删除：与 index + feature 页信息重复。）</p>
 *   <li>每项独立 try/catch，单项失败不影响其余项与测试本身。</li>
 * </ol>
 */
public final class LocalReportSink implements ReportSink {

    private static final Logger log = LoggerFactory.getLogger(LocalReportSink.class);

    private final PwReportsConfig config;
    private final Set<String> writtenIds = ConcurrentHashMap.newKeySet();
    private final Set<String> printedLinks = ConcurrentHashMap.newKeySet();
    /** 本次运行是否仅单 case（用于决定打印逐条 case 链接还是聚合报告链接）。 */
    private volatile boolean singleCaseRun;
    private final java.util.concurrent.atomic.AtomicInteger errors =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 渲染线程名序号（仅用于可观测性，线程安全）。 */
    private static final java.util.concurrent.atomic.AtomicInteger renderSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    public LocalReportSink(PwReportsConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public void onRunStarted() {
        //  P1-9：同 JVM 多轮 run（rerun / 多 feature 分轮）时，上一轮的 writtenIds / errors 残留会让
        //  第二轮相同 outcomeId 被判"已写"而跳过 per-case JSON 真相源，且 errors 变成累计值。
        //  整场开始即清，保证每轮产物从零开始。
        writtenIds.clear();
        printedLinks.clear();
        errors.set(0);
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

        // sweep 补写漏网 JSON（串行、实时真相源兜底；必须在其它渲染前完成，且属轻量 IO）
        for (TestOutcome o : outcomes) {
            writeOutcomeJson(o); // sweep：未写过的一补（防御 CaseFinished 丢失）
        }
        for (TestOutcome o : aggregated) {
            writeOutcomeJson(o);
        }

        //  P1-9（问题②）：原 finishAll 在 RunFinished 派发线程上<b>串行</b>渲染全部 HTML/JSON/CSV/JUnit/资产，
        //  无并发、无超时 —— 大轮次墙钟耗时高，且极端情况下可能阻塞/挂死派发线程。
        //  现改为<b>分片 + 并行 + 有界超时</b>：各独立产物并行渲染（单步隔离保留），整体 join 受超时保护；
        //  正常规模在超时内全部完成（契约不变：finishAll 返回时报告已落盘），病理性超时则取消剩余任务并告警
        //  （JSON 真相源已实时落盘，不丢；超时任务多为 HTML/资产展示层，非真相源）。
        List<Callable<Object>> tasks = new ArrayList<>();
        // per-case HTML：rows + standalone（父页面跳转目标）+ 聚合父页面（各自独立文件，可并行）
        for (TestOutcome o : outcomes) {
            tasks.add(Executors.callable(() -> renderOutcomeSafe(o)));
        }
        for (TestOutcome o : aggregated) {
            tasks.add(Executors.callable(() -> renderOutcomeSafe(o)));
        }
        tasks.add(Executors.callable(() -> runRender("pw-summary.json", true,
                () -> { JsonWriter.writeSummary(dir(), aggregated); return null; })));
        tasks.add(Executors.callable(() -> runRender("index.html", false,
                () -> { AggregateBuilder.writeIndex(dir(), aggregated); return null; })));
        tasks.add(Executors.callable(() -> runRender("feature pages", false,
                () -> { FeatureHtmlRenderer.writeFeatures(dir(), aggregated); return null; })));
        tasks.add(Executors.callable(() -> runRender("email-report.html", false,
                () -> { EmailReportRenderer.writeEmailReport(dir(), aggregated); return null; })));
        tasks.add(Executors.callable(() -> runRender("monitor-failures.html", false,
                () -> { SummaryHtmlRenderer.writeMonitorFailures(dir(), outcomes); return null; })));
        tasks.add(Executors.callable(() -> runRender("pw-summary.csv", false,
                () -> { CsvWriter.writeCsv(dir(), aggregated); return null; })));
        tasks.add(Executors.callable(() -> runRender("pw-junit.xml", false,
                () -> { JunitXmlWriter.writeXml(dir(), aggregated); return null; })));
        tasks.add(Executors.callable(() -> runRender("static assets", false,
                () -> { StaticAssets.copyAssets(dir()); return null; })));

        boolean single = outcomes.size() <= 1;
        singleCaseRun = single;
        renderInParallel(tasks);

        //  2026-11-02 用户需求：单 case 调试已在 renderOutcomeSafe 逐条打印该 case 链接；
        //  整跑（多 case）则在此统一打印聚合报告入口 index.html 与邮件报告 email-report.html，
        //  CI/托管环境经 pw.report.base.url 绝对化为 http(s) 链接，本地回退 file://。
        if (!single) {
            log.info("Index report: {}", reportLink("index.html"));
            log.info("Email report: {}", reportLink("email-report.html"));
        }

        //  2026-11-02 用户需求：命令行整跑（多 case）时，控制台只给聚合入口、不逐条打印以免刷屏；
        //  另落盘 report-links.txt，逐条列出每条 case 的可点击链接（带 case 名，便于 grep 定位，如查 77），
        //  CI/托管环境经 pw.report.base.url 绝对化为 http(s)，本地回退 file://。
        writeReportLinksFile(outcomes, aggregated);

        if (errors.get() > 0) {
            log.warn("LocalReportSink completed with {} error(s); JSON preserved where written", errors.get());
        }
    }

    private void renderOutcomeSafe(TestOutcome o) {
        runRender("per-case HTML " + o.getOutcomeId(), false,
                () -> { HtmlRenderer.renderOutcome(dir(), o); return null; });
        //  2026-11-02 用户需求：单 case 调试时，该 case 的 HTML 渲染完成后立即打印其可点击报告链接；
        //  仅本地单 case 运行打印（整跑改打聚合报告链接，见 finishAll），不扫磁盘历史，同一 outcomeId 只打一次。
        if (singleCaseRun) {
            String id = o.getOutcomeId();
            if (id != null && printedLinks.add(id)) {
                log.info("Case report: {}", reportLink(id + "/" + id + ".html"));
            }
        }
    }

    /**
     * 落盘 {@code <outputDir>/report-links.txt}：聚合入口 + 每条 case 的链接（带 case 名，便于 grep 定位）。
     * 失败不抛（非真相源，仅辅助定位）；链接经 {@link #reportLink} 解析（CI 用 http(s)，本地 file://）。
     */
    private void writeReportLinksFile(List<TestOutcome> outcomes, List<TestOutcome> aggregated) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Report links\n");
            sb.append("Index : ").append(reportLink("index.html")).append('\n');
            sb.append("Email : ").append(reportLink("email-report.html")).append('\n');
            sb.append("Per-case:\n");
            Set<String> seen = new HashSet<>();
            for (TestOutcome o : outcomes) {
                appendLinkLine(sb, seen, o);
            }
            for (TestOutcome o : aggregated) {
                appendLinkLine(sb, seen, o);
            }
            Files.writeString(dir().resolve("report-links.txt"), sb.toString());
        } catch (Throwable t) {
            log.warn("report-links.txt write failed (non-fatal)", t);
        }
    }

    /** 追加一行 {@code <case名> => <link>}，按 outcomeId 去重（outline 父页/行页不重复）。 */
    private void appendLinkLine(StringBuilder sb, Set<String> seen, TestOutcome o) {
        String id = o.getOutcomeId();
        if (id == null || !seen.add(id)) {
            return;
        }
        String name = o.getName() != null ? o.getName() : id;
        sb.append("  ").append(name).append(" => ").append(reportLink(id + "/" + id + ".html")).append('\n');
    }

    /** 单步隔离渲染：异常不向外传播，失败计入 {@link #errors}（severe=true 时升级为 error 级日志）。 */
    private void runRender(String label, boolean severe, Callable<Object> r) {
        try {
            r.call();
        } catch (Throwable t) {
            errors.incrementAndGet();
            if (severe) {
                log.error("{} render failed (non-fatal)", label, t);
            } else {
                log.warn("{} render failed (non-fatal)", label, t);
            }
        }
    }

    /**
     * 分片并行渲染：提交所有任务到有界线程池，{@link ExecutorService#invokeAll(java.util.Collection, long, java.util.concurrent.TimeUnit)}
     * 在 {@link #renderTimeoutSeconds()} 内等待全部完成；超时则取消未启动任务并告警（已启动任务由 finally 的
     * shutdownNow 中断）。使用 daemon 线程，避免渲染线程阻止 JVM 退出（P1-9 韧性）。
     */
    private void renderInParallel(List<Callable<Object>> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "pw-report-render-" + renderSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try {
            long timeout = renderTimeoutSeconds();
            List<Future<Object>> futures = pool.invokeAll(tasks, timeout, TimeUnit.SECONDS);
            long cancelled = 0;
            for (Future<Object> f : futures) {
                if (f.isCancelled()) {
                    cancelled++;
                }
            }
            if (cancelled > 0) {
                log.warn("LocalReportSink render: {} task(s) cancelled due to {}s timeout", cancelled, timeout);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("LocalReportSink render interrupted before completion", ie);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 渲染整体有界超时（秒）：默认 300s，可经系统属性 {@code pw.report.render.timeout.seconds} 覆盖。 */
    private static long renderTimeoutSeconds() {
        String v = ConfigReader.getProperty("pw.report.render.timeout.seconds");
        if (v != null) {
            try {
                long parsed = Long.parseLong(v.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignore) {
                // 非法值回落默认
            }
        }
        return 300L;
    }

    /**
     * 报告文件的可点击链接：CI/托管环境（配置了 {@code pw.report.base.url} / {@code PW_REPORT_BASE_URL}）
     * 经基准 URL 绝对化为 http(s) 链接；本地未配置则回退 {@code file://}。
     * 与 {@code HtmlRenderer.cssHref()} 同款约定：baseUrl 已规范为以 {@code /} 结尾。
     */
    private String reportLink(String relativePath) {
        String base = PwReportsConfig.baseUrl();
        if (base != null && !base.isEmpty()) {
            return base + relativePath;
        }
        return dir().resolve(relativePath).toUri().toString();
    }

    private Path dir() {
        return config.getOutputDir();
    }

    /** 测试用：已写 JSON 的 outcomeId 集合。 */
    public Set<String> writtenIds() {
        return Set.copyOf(writtenIds);
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.DownloadRecord;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.FailureCause;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestAttachment;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单用例录制会话——每 scenario 一个，由 {@link ReportBus} 按 outcomeId 索引。
 *
 * <p>线程模型：步骤树只由场景线程顺序写（Cucumber 不变式：同 scenario 的 hook/step/glue
 * 同线程顺序执行）；evidence / apiOperations 允许异步线程通过显式句柄追加，故用
 * 写时复制列表 + 有界丢弃。句柄目标 {@code stepIndex} 用 ConcurrentHashMap。
 */
final class RecordingSession {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final String outcomeId;
    private final String feature;
    private final String scenario;
    private final List<String> tags;
    private final String featurePath;
    private final int maxEvidenceLines;
    private final int maxApiOperations;

    private final List<TestStep> rootSteps = new CopyOnWriteArrayList<>();
    private final Map<String, TestStep> stepIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> stepStartMs = new ConcurrentHashMap<>();
    private final List<String> evidence = new CopyOnWriteArrayList<>();
    private final List<String> apiOperations = new CopyOnWriteArrayList<>();
    /** API 监控失败条目（独立于 apiOperations：这里只收失败，monitor-failures.html 数据源）。 */
    private final List<String> apiMonitorFailures = new CopyOnWriteArrayList<>();
    /**
     * RouteDsl 路由注册（API 注册）行；<b>用例级兜底</b>——注册发生在步骤上下文时归位到
     * {@code TestStep#routeRegistrations}，非步骤上下文（钩子等）才落本列表。
     */
    private final List<String> routeRegistrations = new CopyOnWriteArrayList<>();
    private final AtomicInteger routeDropped = new AtomicInteger();
    private final List<TestAttachment> attachments = new CopyOnWriteArrayList<>();
    /** 挂载到报告的下载文件（{@link DownloadRecord}）；对应 per-case 报告「Downloads」Tab。 */
    private final List<DownloadRecord> downloads = new CopyOnWriteArrayList<>();
    private volatile String tracePath;
    /** 场景录屏相对路径（{@code <outcomeId>/records/<name>.webm}），未采集到时保持 null。 */
    private volatile String videoPath;
    /** trace 录制窗口起点（{@code HH:mm:ss.SSS}），未提供时保持 null。 */
    private volatile String traceStartTime;
    /** trace 录制窗口终点（{@code HH:mm:ss.SSS}），未提供时保持 null。 */
    private volatile String traceEndTime;
    /** trace 录制时长（毫秒；0 表示未知）。 */
    private volatile long traceDurationMs;
    private final List<String> consoleOutput = new CopyOnWriteArrayList<>();
    private final List<String> consoleError = new CopyOnWriteArrayList<>();
    private final AtomicInteger stepCounter = new AtomicInteger();
    private final AtomicInteger screenshotCounter = new AtomicInteger();
    private final AtomicInteger evidenceDropped = new AtomicInteger();
    private final AtomicInteger apiDropped = new AtomicInteger();
    private final AtomicInteger monitorDropped = new AtomicInteger();
    private final AtomicInteger consoleDropped = new AtomicInteger();

    /** 手工用例标记（标签 @manual）：不执行、报告记录为手工。 */
    private volatile boolean manual;
    /** 浏览器控制台输出（page console，脱敏，限行）。additive-only。 */
    private final List<String> browserConsole = new CopyOnWriteArrayList<>();
    /** 浏览器错误（page-error，脱敏，限行）。additive-only。 */
    private final List<String> browserErrors = new CopyOnWriteArrayList<>();

    private final long startTimeMs = System.currentTimeMillis();
    private volatile long endTimeMs;
    private volatile long durationOverride;
    private volatile TestResult result;
    private volatile Throwable error;
    /** @Before hook 失败（首个失败优先）——用例级根因，优先于步骤失败。 */
    private volatile Throwable rootFailure;
    // ---- Scenario Outline 一等模型（additive；默认不设 → 投影省略） ----
    private volatile boolean scenarioOutline;
    private volatile String outlineKey;
    private volatile List<String> exampleHeaders;
    private volatile List<String> exampleValues;
    private volatile int exampleRowNumber = -1;

    RecordingSession(String outcomeId, String feature, String scenario, List<String> tags,
                     String featurePath, int maxEvidenceLines, int maxApiOperations) {
        this.outcomeId = outcomeId;
        this.feature = feature;
        this.scenario = scenario;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
        this.featurePath = featurePath;
        this.maxEvidenceLines = maxEvidenceLines;
        this.maxApiOperations = maxApiOperations;
    }

    String outcomeId() {
        return outcomeId;
    }

    // ---------------------------------------------------------------- steps

    /** 新建步骤并挂到 parent 之下（parent 为 null 或 case 级 → 根层）。返回带 stepId 的 POJO。 */
    TestStep newStep(String description, Integer lineNumber, StepHandle parent) {
        TestStep step = new TestStep();
        step.setStepId("s" + stepCounter.incrementAndGet());
        step.setDescription(description);
        step.setLineNumber(lineNumber);
        step.setStartTime(ISO.format(Instant.now()));
        if (parent != null && parent.stepId() != null) {
            TestStep p = stepIndex.get(parent.stepId());
            if (p != null) {
                p.getChildren().add(step);
            } else {
                rootSteps.add(step);
            }
        } else {
            rootSteps.add(step);
        }
        stepIndex.put(step.getStepId(), step);
        stepStartMs.put(step.getStepId(), System.currentTimeMillis());
        return step;
    }

    void finishStep(String stepId, TestResult result, Throwable error) {
        TestStep step = stepForWrite(stepId);
        if (step == null) {
            return;
        }
        step.setResult(result);
        Long start = stepStartMs.get(stepId);
        step.setDuration(start == null ? 0L : Math.max(0L, System.currentTimeMillis() - start));
        if (error != null) {
            FailureCause cause = FailureCause.from(error);
            step.setException(cause);
        }
    }

    /** 按 stepId 取步骤 POJO（截图挂载 / 收尾共用）。 */
    TestStep stepForWrite(String stepId) {
        return stepIndex.get(stepId);
    }

    // ---------------------------------------------------------------- evidence / api

    void appendEvidence(String scrubbedLine) {
        appendBounded(evidence, scrubbedLine, maxEvidenceLines, evidenceDropped, "evidence");
    }

    void appendStepEvidence(String stepId, String scrubbedLine) {
        TestStep step = stepIndex.get(stepId);
        if (step == null) {
            appendEvidence(scrubbedLine);
            return;
        }
        appendBounded(step.getEvidence(), scrubbedLine, maxEvidenceLines, evidenceDropped, "evidence");
    }

    /**
     * API 操作记录：若已知所属步骤（{@code stepId} 非 null 且该步骤存在）则归位到步骤级，
     * 否则回落用例级列表（脱敏、限行）。
     */
    void appendApiOperation(String stepId, String scrubbedLine) {
        TestStep step = (stepId != null) ? stepIndex.get(stepId) : null;
        if (step != null) {
            appendBounded(step.getApiOperations(), scrubbedLine, maxApiOperations, apiDropped, "apiOperation");
        } else {
            appendBounded(apiOperations, scrubbedLine, maxApiOperations, apiDropped, "apiOperation");
        }
    }

    /** API 监控失败条目：用例级（监控失败是端点级事实，不挂步骤树），上限 200。 */
    void appendApiMonitorFailure(String scrubbedLine) {
        appendBounded(apiMonitorFailures, scrubbedLine, 200, monitorDropped, "apiMonitorFailure");
    }

    /**
     * RouteDsl 路由注册（API 注册）行：注册发生在哪个步骤就归到哪个步骤
     * （{@code stepId} 为 null 或步骤不存在 → 回落用例级列表，如 {@code @Before} 钩子内注册）。
     * 上限同 apiOperations。
     */
    void appendRouteRegistration(String stepId, String scrubbedLine) {
        TestStep step = (stepId != null) ? stepIndex.get(stepId) : null;
        if (step != null) {
            appendBounded(step.getRouteRegistrations(), scrubbedLine, maxApiOperations, routeDropped, "routeRegistration");
        } else {
            appendBounded(routeRegistrations, scrubbedLine, maxApiOperations, routeDropped, "routeRegistration");
        }
    }

    // ---------------------------------------------------------------- console

    /** 用例级控制台输出（stdout/stderr 经 ScrubGate 脱敏后入此）。 */
    void appendConsole(boolean isError, String scrubbedLine) {
        appendBounded(isError ? consoleError : consoleOutput, scrubbedLine,
                maxEvidenceLines, consoleDropped, "console");
    }

    /** 步骤级控制台输出：归到指定步骤；步骤不存在则降级到用例级。 */
    void appendStepConsole(String stepId, boolean isError, String scrubbedLine) {
        TestStep step = stepIndex.get(stepId);
        if (step == null) {
            appendConsole(isError, scrubbedLine);
            return;
        }
        appendBounded(isError ? step.getConsoleError() : step.getConsoleOutput(),
                scrubbedLine, maxEvidenceLines, consoleDropped, "console");
    }

    /** 标注本 case 为手工用例（标签 @manual）：不执行、报告记录为手工。 */
    void markManual() {
        this.manual = true;
    }

    /** 浏览器控制台输出（page console / page-error，经脱敏入此，限行）。 */
    void appendBrowserConsole(boolean isError, String scrubbedLine) {
        appendBounded(isError ? browserErrors : browserConsole, scrubbedLine,
                maxEvidenceLines, consoleDropped, "browserLog");
    }

    // ---------------------------------------------------------------- attachments

    void appendAttachment(TestAttachment attachment) {
        attachments.add(attachment);
    }

    void appendDownload(DownloadRecord download) {
        downloads.add(download);
    }

    void appendStepAttachment(String stepId, TestAttachment attachment) {
        TestStep step = stepIndex.get(stepId);
        if (step != null) {
            step.getAttachments().add(attachment);
        }
    }

    void setTracePath(String path) {
        this.tracePath = path;
    }

    void setVideoPath(String path) {
        this.videoPath = path;
    }

    /**
     * 登记 trace 录制窗口（起止时刻 + 时长）。任一时点为 {@code null} 表示采集方未提供窗口，
     * 报告侧相应省略该行，不影响 path / 播放按钮。
     */
    void setTraceMeta(String startTime, String endTime, long durationMs) {
        if (startTime != null) {
            this.traceStartTime = startTime;
        }
        if (endTime != null) {
            this.traceEndTime = endTime;
        }
        if (durationMs > 0) {
            this.traceDurationMs = durationMs;
        }
    }

    private void appendBounded(List<String> target, String line, int max, AtomicInteger dropped, String kind) {
        if (target.size() >= max) {
            dropped.incrementAndGet();
            return;
        }
        target.add(line);
    }

    // ---------------------------------------------------------------- screenshots

    /**
     * 可读截图名：无步骤描述时 {@code step-NNN.png}；有描述时 {@code step-NNN-<slug>.png}
     * （序号按采集顺序、三位零填充、超 999 自然进位；slug 由步骤描述折叠而来，更易认）。
     */
    String nextScreenshotName(String stepDescription) {
        int n = screenshotCounter.incrementAndGet();
        String slug = screenshotSlug(stepDescription);
        if (slug.isEmpty()) {
            return String.format(Locale.ROOT, "step-%03d.png", n);
        }
        return String.format(Locale.ROOT, "step-%03d-%s.png", n, slug);
    }

    /** 步骤描述 → 文件名字符串：小写、非 {@code [a-z0-9]} 段折叠为单个 {@code -}、去首尾 {@code -}、截断 60；空则 ""。 */
    private static String screenshotSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(Math.min(lower.length(), 60));
        boolean pendingDash = false;
        for (int i = 0; i < lower.length() && sb.length() < 60; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingDash && sb.length() > 0) {
                    sb.append('-');
                }
                pendingDash = false;
                sb.append(c);
            } else {
                pendingDash = true;
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- lifecycle

    void recordRootFailure(Throwable t) {
        if (t != null && rootFailure == null) {
            rootFailure = t;
        }
    }

    /** 标注本 case 为 Scenario Outline 行（供 OutlineAggregator 分组聚合）。 */
    void markOutline(String outlineKey, List<String> exampleHeaders, List<String> exampleValues,
                     int exampleRowNumber) {
        this.scenarioOutline = true;
        this.outlineKey = outlineKey;
        this.exampleHeaders = exampleHeaders;
        this.exampleValues = exampleValues;
        this.exampleRowNumber = exampleRowNumber;
    }

    void finish(TestResult result, Throwable error, long durationMs) {
        this.endTimeMs = System.currentTimeMillis();
        this.result = result;
        if (error != null) {
            this.error = error;
        }
        if (durationMs <= 0) {
            durationMs = endTimeMs - startTimeMs;
        }
        this.durationOverride = durationMs;
    }

    boolean isFinished() {
        return result != null;
    }

    // ---------------------------------------------------------------- projection

    /** 投影为 JSON POJO；{@code scrub} 用于失败信息脱敏（失败消息同样是证据，R3）。 */
    TestOutcome toOutcome(ScrubGate scrub) {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId(outcomeId);
        o.setName(scenario);
        o.setTitle(scenario);
        o.setUserStory(new UserStory(feature, featureDisplayName(), featurePath));
        o.setTags(tags);
        o.setResult(result);
        o.setStartTime(ISO.format(Instant.ofEpochMilli(startTimeMs)));
        o.setEndTime(ISO.format(Instant.ofEpochMilli(endTimeMs == 0 ? System.currentTimeMillis() : endTimeMs)));
        o.setDuration(durationOverride > 0 ? durationOverride : Math.max(0L, endTimeMs - startTimeMs));
        o.setTestSteps(rootSteps);
        o.setEvidence(evidence);
        o.setApiOperations(apiOperations);
        o.setApiMonitorFailures(apiMonitorFailures);
        o.setRouteRegistrations(routeRegistrations);
        o.setAttachments(attachments);
        o.setDownloads(downloads);
        o.setConsoleOutput(consoleOutput);
        o.setConsoleError(consoleError);
        o.setManual(manual);
        o.setBrowserConsole(browserConsole);
        o.setBrowserErrors(browserErrors);
        o.setTracePath(tracePath);
        o.setVideoPath(videoPath);
        o.setTraceStartTime(traceStartTime);
        o.setTraceEndTime(traceEndTime);
        o.setTraceDurationMs(traceDurationMs);
        if (scenarioOutline) {
            o.setScenarioOutline(Boolean.TRUE);
            o.setOutlineKey(outlineKey);
            o.setExampleHeaders(exampleHeaders);
            o.setExampleValues(exampleValues);
            o.setExampleRowNumber(exampleRowNumber >= 0 ? exampleRowNumber : null);
        }

        Throwable effective = rootFailure != null ? rootFailure : error;
        if (effective != null) {
            FailureCause cause = FailureCause.from(effective);
            String message = scrub.scrubLine(cause.getMessage());
            cause.setMessage(message);
            o.setTestFailureCause(cause);
            o.setTestFailureMessage(message);
            o.setTestFailureClassname(effective.getClass().getName());
            o.setTestFailureSummary(scrub.scrubStackTrace(toStackTrace(effective)));
        }
        return o;
    }

    private String featureDisplayName() {
        if (featurePath == null || featurePath.isEmpty()) {
            return feature;
        }
        String name = featurePath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String toStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    int evidenceDropped() {
        return evidenceDropped.get();
    }

    int apiDropped() {
        return apiDropped.get();
    }
}

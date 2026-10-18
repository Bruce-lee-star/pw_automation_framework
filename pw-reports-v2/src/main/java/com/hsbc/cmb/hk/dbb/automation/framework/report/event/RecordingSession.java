package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

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
    /** RouteDsl 路由注册（API 注册）行；用例级（注册是 scenario 级事实，不挂步骤树）。 */
    private final List<String> routeRegistrations = new CopyOnWriteArrayList<>();
    private final AtomicInteger routeDropped = new AtomicInteger();
    private final List<TestAttachment> attachments = new CopyOnWriteArrayList<>();
    private volatile String tracePath;
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

    void appendApiOperation(String scrubbedLine) {
        appendBounded(apiOperations, scrubbedLine, maxApiOperations, apiDropped, "apiOperation");
    }

    /** API 监控失败条目：用例级（监控失败是端点级事实，不挂步骤树），上限 200。 */
    void appendApiMonitorFailure(String scrubbedLine) {
        appendBounded(apiMonitorFailures, scrubbedLine, 200, monitorDropped, "apiMonitorFailure");
    }

    /** RouteDsl 路由注册（API 注册）行：用例级，上限同 apiOperations。 */
    void appendRouteRegistration(String scrubbedLine) {
        appendBounded(routeRegistrations, scrubbedLine, maxApiOperations, routeDropped, "routeRegistration");
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

    void appendStepAttachment(String stepId, TestAttachment attachment) {
        TestStep step = stepIndex.get(stepId);
        if (step != null) {
            step.getAttachments().add(attachment);
        }
    }

    void setTracePath(String path) {
        this.tracePath = path;
    }

    private void appendBounded(List<String> target, String line, int max, AtomicInteger dropped, String kind) {
        if (target.size() >= max) {
            dropped.incrementAndGet();
            return;
        }
        target.add(line);
    }

    // ---------------------------------------------------------------- screenshots

    /** 确定性截图名：step-NNN.png（序号按采集顺序，三位零填充，超 999 自然进位）。 */
    String nextScreenshotName() {
        return String.format(Locale.ROOT, "step-%03d.png", screenshotCounter.incrementAndGet());
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
        o.setConsoleOutput(consoleOutput);
        o.setConsoleError(consoleError);
        o.setManual(manual);
        o.setBrowserConsole(browserConsole);
        o.setBrowserErrors(browserErrors);
        o.setTracePath(tracePath);
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

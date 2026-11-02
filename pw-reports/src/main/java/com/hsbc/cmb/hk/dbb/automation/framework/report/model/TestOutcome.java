package com.hsbc.cmb.hk.dbb.automation.framework.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用例级结果——per-case JSON 的根对象，字段名镜像 Serenity {@code TestOutcome}（DR-1）。
 *
 * <p>口径说明：
 * <ul>
 *   <li>镜像字段：{@code name/title/description/userStory/tags/result/startTime/endTime/duration/
 *       testSteps/testFailureCause/testFailureClassname/testFailureMessage}；</li>
 *   <li>自有字段（additive-only）：{@code reportVersion}（契约版本，R5）、
 *       {@code outcomeId}（唯一 id，文件名与交叉链接都用，R7）、{@code evidence}、
 *       {@code apiOperations}；</li>
 *   <li>{@code duration}：毫秒（Serenity 实证口径）。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TestOutcome {

    /** 契约版本，当前 1（DR-3：additive-only，分歧走主版本双写）。 */
    private int reportVersion = 1;

    /** 唯一 id：秒级时间戳 + 用例身份的 SHA-256 hex 摘要，冲突加 -2/-3 后缀。 */
    private String outcomeId;

    private String name;

    private String title;

    private String description;

    private UserStory userStory;

    private List<String> tags = new CopyOnWriteArrayList<>();

    private TestResult result;

    /** ISO-8601。 */
    private String startTime;

    /** ISO-8601。 */
    private String endTime;

    /** 毫秒。 */
    private long duration;

    private List<TestStep> testSteps = new CopyOnWriteArrayList<>();

    /** 镜像 Serenity FailureCause（{@code message}/{@code errorType}）。 */
    private FailureCause testFailureCause;

    private String testFailureClassname;

    /** 与 testFailureCause.message 同值——兼容只读 errorMessage 的老消费方。 */
    private String testFailureMessage;

    /** 脱敏后的完整堆栈（镜像 Serenity testFailureSummary 语义）。 */
    private String testFailureSummary;

    /** 用例级证据（脱敏后，≤50 行）：hook 失败告警、@After 失败 warning 等。 */
    private List<String> evidence = new CopyOnWriteArrayList<>();

    /** API 操作记录（脱敏后，≤500 条）——承接 SerenityReporter.recordApiOperation。 */
    private List<String> apiOperations = new CopyOnWriteArrayList<>();

    /** API 监控失败条目（脱敏后，≤200 条）。additive-only。 */
    private List<String> apiMonitorFailures = new CopyOnWriteArrayList<>();

    /** RouteDsl 路由注册（API 注册）描述行（脱敏后）。additive-only。 */
    private List<String> routeRegistrations = new CopyOnWriteArrayList<>();

    /** 业务层自由附件（HTML/文本块，已脱敏）。additive-only。 */
    private List<TestAttachment> attachments = new CopyOnWriteArrayList<>();

    /** 挂载到报告的下载文件（additive-only）：对应 per-case 报告「Downloads」Tab，可点击下载。 */
    private List<DownloadRecord> downloads = new CopyOnWriteArrayList<>();

    /** 用例级捕获的 stdout（hook 输出等，脱敏，限行）。additive-only。 */
    private List<String> consoleOutput = new CopyOnWriteArrayList<>();

    /** 用例级捕获的 stderr（脱敏，限行）。additive-only。 */
    private List<String> consoleError = new CopyOnWriteArrayList<>();

    /** Playwright trace.zip 相对路径（&lt;outcomeId&gt;/trace.zip），有值则渲染 Trace Viewer 按钮。 */
    private String tracePath;

    /**
     * Playwright 场景录屏相对路径（{@code <outcomeId>/records/<name>.webm}），有值则渲染录像播放器。
     *
     * <p>仅当框架开启 {@code playwright.record.video.enabled} 时由采集方上报；未开启/未采集到
     * 时为 null，报告 Tab 显示空态提示。additive-only。
     */
    private String videoPath;

    /** trace 录制起始时刻（{@code HH:mm:ss.SSS}）；采集方未提供窗口时为 null。additive-only。 */
    private String traceStartTime;

    /** trace 录制结束时刻（{@code HH:mm:ss.SSS}）；采集方未提供窗口时为 null。additive-only。 */
    private String traceEndTime;

    /** trace 录制时长（毫秒；{@code <=0} 表示未知）。additive-only。 */
    private long traceDurationMs;

    /** 手动 case 标记（非自动执行）。 */
    private boolean manual;

    /** 手动备注。 */
    private String manualNote;

    /**
     * 是否已应用「标记成功」覆盖（非破坏性）。additive-only。
     *
     * <p>用 {@code Boolean}（而非 boolean）以便未覆盖时序列化为 null、不污染每个用例 JSON。
     * 配合 {@code original*} 字段，标记后可经 ReportServer {@code /api/mark-revert} 还原原始失败态，
     * 亦可重复调 {@code /api/mark-success} 修改 {@link #overrideNote}。
     */
    private Boolean overridden;

    /** 当前「标记成功」覆盖备注（可修改）。additive-only。 */
    private String overrideNote;

    /** 覆盖前的原始结果（非破坏性保留）。additive-only。 */
    private TestResult originalResult;

    /** 覆盖前的原始失败原因（非破坏性保留）。additive-only。 */
    private FailureCause originalTestFailureCause;

    /** 覆盖前的原始失败摘要（非破坏性保留）。additive-only。 */
    private String originalTestFailureSummary;

    /** 覆盖前的原始失败类名（非破坏性保留）。additive-only。 */
    private String originalTestFailureClassname;

    /** 覆盖前的原始失败消息（非破坏性保留）。additive-only。 */
    private String originalTestFailureMessage;

    /** 浏览器控制台输出（page console，脱敏，限行）。additive-only。 */
    private List<String> browserConsole = new CopyOnWriteArrayList<>();

    /** 浏览器错误（page-error，脱敏，限行）。additive-only。 */
    private List<String> browserErrors = new CopyOnWriteArrayList<>();

    /** Scenario Outline 一等模型：是否数据驱动场景大纲（additive，默认 null → 序列化省略）。 */
    private Boolean scenarioOutline;

    /** Scenario Outline 示例表头（列名），如 [user, password, error]（additive，缺省 null）。 */
    private List<String> exampleHeaders;

    /** Scenario Outline 本行示例取值，与 exampleHeaders 对齐（additive，缺省 null）。 */
    private List<String> exampleValues;

    /** Scenario Outline 示例行号（1 起；非 outline 为 null，显式置 -1 表示未编号）。 */
    private Integer exampleRowNumber;

    /** 聚合分组键：feature + "/" + scenarioName；仅用于 outline 行聚合（additive，缺省 null）。 */
    private String outlineKey;

    /** 聚合父 outcome 持有：每行一个子 outcome（仅 outline 父持有，additive，缺省 null）。 */
    private List<TestOutcome> examples;

    public int getReportVersion() {
        return reportVersion;
    }

    public void setReportVersion(int reportVersion) {
        this.reportVersion = reportVersion;
    }

    public String getOutcomeId() {
        return outcomeId;
    }

    public void setOutcomeId(String outcomeId) {
        this.outcomeId = outcomeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.title = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UserStory getUserStory() {
        return userStory;
    }

    public void setUserStory(UserStory userStory) {
        this.userStory = userStory;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public TestResult getResult() {
        return result;
    }

    public void setResult(TestResult result) {
        this.result = result;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public List<TestStep> getTestSteps() {
        return testSteps;
    }

    public void setTestSteps(List<TestStep> testSteps) {
        this.testSteps = testSteps;
    }

    public FailureCause getTestFailureCause() {
        return testFailureCause;
    }

    public void setTestFailureCause(FailureCause testFailureCause) {
        this.testFailureCause = testFailureCause;
    }

    public String getTestFailureClassname() {
        return testFailureClassname;
    }

    public void setTestFailureClassname(String testFailureClassname) {
        this.testFailureClassname = testFailureClassname;
    }

    public String getTestFailureMessage() {
        return testFailureMessage;
    }

    public void setTestFailureMessage(String testFailureMessage) {
        this.testFailureMessage = testFailureMessage;
    }

    public String getTestFailureSummary() {
        return testFailureSummary;
    }

    public void setTestFailureSummary(String testFailureSummary) {
        this.testFailureSummary = testFailureSummary;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence;
    }

    public List<String> getApiOperations() {
        return apiOperations;
    }

    public void setApiOperations(List<String> apiOperations) {
        this.apiOperations = apiOperations;
    }

    public List<String> getApiMonitorFailures() {
        return apiMonitorFailures;
    }

    public void setApiMonitorFailures(List<String> apiMonitorFailures) {
        this.apiMonitorFailures = apiMonitorFailures;
    }

    public List<String> getRouteRegistrations() {
        return routeRegistrations;
    }

    public void setRouteRegistrations(List<String> routeRegistrations) {
        this.routeRegistrations = routeRegistrations;
    }

    public List<TestAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<TestAttachment> attachments) {
        this.attachments = attachments;
    }

    public List<DownloadRecord> getDownloads() {
        return downloads;
    }

    public void setDownloads(List<DownloadRecord> downloads) {
        this.downloads = downloads == null ? new CopyOnWriteArrayList<>() : downloads;
    }

    public List<String> getConsoleOutput() {
        return consoleOutput;
    }

    public void setConsoleOutput(List<String> consoleOutput) {
        this.consoleOutput = consoleOutput;
    }

    public List<String> getConsoleError() {
        return consoleError;
    }

    public void setConsoleError(List<String> consoleError) {
        this.consoleError = consoleError;
    }

    public String getTracePath() {
        return tracePath;
    }

    public void setTracePath(String tracePath) {
        this.tracePath = tracePath;
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getTraceStartTime() {
        return traceStartTime;
    }

    public void setTraceStartTime(String traceStartTime) {
        this.traceStartTime = traceStartTime;
    }

    public String getTraceEndTime() {
        return traceEndTime;
    }

    public void setTraceEndTime(String traceEndTime) {
        this.traceEndTime = traceEndTime;
    }

    public long getTraceDurationMs() {
        return traceDurationMs;
    }

    public void setTraceDurationMs(long traceDurationMs) {
        this.traceDurationMs = traceDurationMs;
    }

    public boolean isManual() {
        return manual;
    }

    public void setManual(boolean manual) {
        this.manual = manual;
    }

    public String getManualNote() {
        return manualNote;
    }

    public void setManualNote(String manualNote) {
        this.manualNote = manualNote;
    }

    public Boolean getOverridden() {
        return overridden;
    }

    public void setOverridden(Boolean overridden) {
        this.overridden = overridden;
    }

    public String getOverrideNote() {
        return overrideNote;
    }

    public void setOverrideNote(String overrideNote) {
        this.overrideNote = overrideNote;
    }

    public TestResult getOriginalResult() {
        return originalResult;
    }

    public void setOriginalResult(TestResult originalResult) {
        this.originalResult = originalResult;
    }

    public FailureCause getOriginalTestFailureCause() {
        return originalTestFailureCause;
    }

    public void setOriginalTestFailureCause(FailureCause originalTestFailureCause) {
        this.originalTestFailureCause = originalTestFailureCause;
    }

    public String getOriginalTestFailureSummary() {
        return originalTestFailureSummary;
    }

    public void setOriginalTestFailureSummary(String originalTestFailureSummary) {
        this.originalTestFailureSummary = originalTestFailureSummary;
    }

    public String getOriginalTestFailureClassname() {
        return originalTestFailureClassname;
    }

    public void setOriginalTestFailureClassname(String originalTestFailureClassname) {
        this.originalTestFailureClassname = originalTestFailureClassname;
    }

    public String getOriginalTestFailureMessage() {
        return originalTestFailureMessage;
    }

    public void setOriginalTestFailureMessage(String originalTestFailureMessage) {
        this.originalTestFailureMessage = originalTestFailureMessage;
    }

    public List<String> getBrowserConsole() {
        return browserConsole;
    }

    public void setBrowserConsole(List<String> browserConsole) {
        this.browserConsole = browserConsole == null ? new CopyOnWriteArrayList<>() : browserConsole;
    }

    public List<String> getBrowserErrors() {
        return browserErrors;
    }

    public void setBrowserErrors(List<String> browserErrors) {
        this.browserErrors = browserErrors == null ? new CopyOnWriteArrayList<>() : browserErrors;
    }

    public Boolean getScenarioOutline() {
        return scenarioOutline;
    }

    public void setScenarioOutline(Boolean scenarioOutline) {
        this.scenarioOutline = scenarioOutline;
    }

    public List<String> getExampleHeaders() {
        return exampleHeaders;
    }

    public void setExampleHeaders(List<String> exampleHeaders) {
        this.exampleHeaders = exampleHeaders;
    }

    public List<String> getExampleValues() {
        return exampleValues;
    }

    public void setExampleValues(List<String> exampleValues) {
        this.exampleValues = exampleValues;
    }

    public Integer getExampleRowNumber() {
        return exampleRowNumber;
    }

    public void setExampleRowNumber(Integer exampleRowNumber) {
        this.exampleRowNumber = exampleRowNumber;
    }

    public String getOutlineKey() {
        return outlineKey;
    }

    public void setOutlineKey(String outlineKey) {
        this.outlineKey = outlineKey;
    }

    public List<TestOutcome> getExamples() {
        return examples;
    }

    public void setExamples(List<TestOutcome> examples) {
        this.examples = examples;
    }
}

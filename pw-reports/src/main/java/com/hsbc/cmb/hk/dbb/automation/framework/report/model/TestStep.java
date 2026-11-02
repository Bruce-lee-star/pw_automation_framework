package com.hsbc.cmb.hk.dbb.automation.framework.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 报告步骤——JSON 结构镜像 Serenity {@code TestStep}（DR-1）。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code stepId}：自有，句柄目标（{@code StepHandle.currentStep}），跨线程证据挂靠它；</li>
 *   <li>{@code description / result / duration / startTime / children / exception}：镜像 Serenity；</li>
 *   <li>{@code duration}：<b>毫秒</b>（与 Serenity 4.3.4 实证口径一致：{@code ChronoUnit.MILLIS.between}）；</li>
 *   <li>{@code evidence}：自有，已脱敏、行数受限（ScrubGate + 50 行上限）；</li>
 *   <li>{@code screenshot}：自有，相对文件名 {@code step-NNN.png}（确定性命名，R6）。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TestStep {

    private String stepId;

    private String description;

    private TestResult result;

    /** 毫秒。 */
    private long duration;

    /** ISO-8601 字符串（预格式化，避免 JSON 依赖 jsr310 模块）。 */
    private String startTime;

    /** 步骤定义所在行（Cucumber 来源步骤有值；@Step 业务步骤为 null）。 */
    private Integer lineNumber;

    private List<TestStep> children = new CopyOnWriteArrayList<>();

    private List<String> evidence = new CopyOnWriteArrayList<>();

    private String screenshot;

    /** 步骤级附件（HTML/文本块）。additive-only。 */
    private List<TestAttachment> attachments = new CopyOnWriteArrayList<>();

    /** 步骤内捕获的 stdout（脱敏，限行）。additive-only。 */
    private List<String> consoleOutput = new CopyOnWriteArrayList<>();

    /** 步骤内捕获的 stderr（脱敏，限行）。additive-only。 */
    private List<String> consoleError = new CopyOnWriteArrayList<>();
    /** 步骤级 API 操作记录（REST Assured / route 抓包按发生步骤归位到此；脱敏，限行）。additive-only。 */
    private List<String> apiOperations = new CopyOnWriteArrayList<>();

    /**
     * 步骤级 RouteDsl 路由注册行（注册发生在哪个步骤就归到哪个步骤；脱敏，限行）。additive-only。
     *
     * <p>与用例级 {@code TestOutcome#routeRegistrations} 的关系：注册时若处于步骤上下文归位到本步骤，
     * 否则（如 {@code @Before} 钩子内注册）回落用例级列表。
     */
    private List<String> routeRegistrations = new CopyOnWriteArrayList<>();

    /** Gherkin DataTable（行 × 列，含表头行）；非 DataTable 步骤为 null。additive-only。 */
    private List<List<String>> dataTable;

    /** Gherkin DocString 内容；非 DocString 步骤为 null。additive-only。 */
    private String docString;

    public List<List<String>> getDataTable() {
        return dataTable;
    }

    public void setDataTable(List<List<String>> dataTable) {
        this.dataTable = dataTable;
    }

    public String getDocString() {
        return docString;
    }

    public void setDocString(String docString) {
        this.docString = docString;
    }

    private FailureCause exception;

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TestResult getResult() {
        return result;
    }

    public void setResult(TestResult result) {
        this.result = result;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public List<TestStep> getChildren() {
        return children;
    }

    public void setChildren(List<TestStep> children) {
        this.children = children;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence;
    }

    public String getScreenshot() {
        return screenshot;
    }

    public void setScreenshot(String screenshot) {
        this.screenshot = screenshot;
    }

    public List<TestAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<TestAttachment> attachments) {
        this.attachments = attachments;
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

    public List<String> getApiOperations() {
        return apiOperations;
    }

    public void setApiOperations(List<String> apiOperations) {
        this.apiOperations = apiOperations;
    }

    public List<String> getRouteRegistrations() {
        return routeRegistrations;
    }

    public void setRouteRegistrations(List<String> routeRegistrations) {
        this.routeRegistrations = routeRegistrations;
    }

    public void setConsoleError(List<String> consoleError) {
        this.consoleError = consoleError;
    }

    public FailureCause getException() {
        return exception;
    }

    public void setException(FailureCause exception) {
        this.exception = exception;
    }
}

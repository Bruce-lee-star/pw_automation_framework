package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

/**
 * AgentTrace 的跨模块传输载体（R9 审计轨迹）。
 *
 * <p>定义在 pw-core 的 {@code common.reporting}，使执行链（pw-core 的
 * {@code agent.channel.StepDriver}）无需依赖 pw-reports 的 {@code AgentTrace} 模型即可埋点；
 * 具体组装由 pw-reports 的实现端完成。仅携带最小元数据，绝不携带原始 URL 完整串 / header /
 * 表单值等敏感内容（脱敏由实现端 {@code AgentTrace.forAction} 完成）。</p>
 */
public final class AgentTraceEvent {

    private final String stepId;
    private final String policyVersion;
    private final String actionCategory;
    private final String rawTargetUrl;
    private final long durationMs;
    private final boolean success;
    private final String approvalRef;
    private final String errorClass;

    public AgentTraceEvent(String stepId, String policyVersion, String actionCategory,
                           String rawTargetUrl, long durationMs, boolean success,
                           String approvalRef, String errorClass) {
        this.stepId = stepId;
        this.policyVersion = policyVersion;
        this.actionCategory = actionCategory;
        this.rawTargetUrl = rawTargetUrl;
        this.durationMs = durationMs;
        this.success = success;
        this.approvalRef = approvalRef;
        this.errorClass = errorClass;
    }

    public String stepId() { return stepId; }
    public String policyVersion() { return policyVersion; }
    public String actionCategory() { return actionCategory; }
    public String rawTargetUrl() { return rawTargetUrl; }
    public long durationMs() { return durationMs; }
    public boolean success() { return success; }
    public String approvalRef() { return approvalRef; }
    public String errorClass() { return errorClass; }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * AgentTrace —— 最小必要审计轨迹（R9，设计 §10.4 / §11 基线表）。
 *
 * <p><b>铁律（R9 不可重执行 + 禁止保存原始 secret）</b>：
 * <ul>
 *   <li>只记最小元数据：策略版本 / 动作类别 / 目标 host（仅 host，不含 path/query/secret） /
 *       耗时 / 结果 / 审批引用 / 异常类名（仅 simpleName，不含可能带 URL/token 的 message） /
 *       时间戳 / step 引用。</li>
 *   <li><b>绝不</b>保存原始敏感值：URL 完整串、请求/响应体、header 值、密码、token、表单填充值等。</li>
 *   <li>导出物不是可重执行脚本：{@link AgentTraceWriter} 输出仅含上述元数据，无命令/回放指令。</li>
 * </ul>
 *
 * <p>目标 host 由 {@link #forAction} 从原始 URL 抽取 host（失败则记占位符）；异常只取 simpleName，
 * 二者都不回吐可能含 secret 的原始串。</p>
 */
public final class AgentTrace {

    /** 轨迹结果。 */
    public enum Outcome { SUCCESS, FAILURE }

    private final String traceId;
    private final String stepId;          // 可空
    private final String policyVersion;
    private final String actionCategory;  // NAVIGATE/CLICK/FILL/...（调用方传入，不与 pw-core 耦合）
    private final String targetHost;      // 仅 host
    private final long durationMs;
    private final Outcome outcome;
    private final String approvalRef;     // 可空：审批引用，非 secret
    private final String errorClass;      // 可空：异常 simpleName，非 message
    private final Instant recordedAt;

    private AgentTrace(Builder b) {
        this.traceId = requireNonBlank(b.traceId, "traceId");
        this.stepId = b.stepId;
        this.policyVersion = b.policyVersion == null ? "<unknown>" : b.policyVersion;
        this.actionCategory = b.actionCategory == null ? "<unknown>" : b.actionCategory;
        this.targetHost = b.targetHost == null ? "<unknown>" : b.targetHost;
        this.durationMs = Math.max(0L, b.durationMs);
        this.outcome = b.outcome == null ? Outcome.SUCCESS : b.outcome;
        this.approvalRef = b.approvalRef;
        this.errorClass = b.errorClass;
        this.recordedAt = b.recordedAt == null ? Instant.now() : b.recordedAt;
    }

    // ---- 工厂 ----

    /** 从一次动作构造轨迹：原始 URL 仅抽 host，异常仅取 simpleName。 */
    public static AgentTrace forAction(String stepId, String policyVersion, String actionCategory,
                                      String rawTargetUrl, long durationMs, boolean success,
                                      String approvalRef, Throwable error) {
        return builder()
                .traceId(UUID.randomUUID().toString())
                .stepId(stepId)
                .policyVersion(policyVersion)
                .actionCategory(actionCategory)
                .targetHost(hostOf(rawTargetUrl))
                .durationMs(durationMs)
                .outcome(success ? Outcome.SUCCESS : Outcome.FAILURE)
                .approvalRef(approvalRef)
                .errorClass(error == null ? null : error.getClass().getSimpleName())
                .recordedAt(Instant.now())
                .build();
    }

    /** 从原始 URL 抽取 host（仅 host）；无法解析绝不回吐原始串，避免泄漏 path/query 中的 secret。 */
    private static String hostOf(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "<unknown>";
        }
        try {
            URI uri = new URI(rawUrl);
            String host = uri.getHost();
            if (host != null) {
                return host.toLowerCase(Locale.ROOT);
            }
            // 无 scheme（相对路径 / host-only）：去掉 query 后保守返回，不含 secret
            int q = rawUrl.indexOf('?');
            String noQuery = q < 0 ? rawUrl : rawUrl.substring(0, q);
            return noQuery.isBlank() ? "<unknown>" : noQuery;
        } catch (URISyntaxException e) {
            return "<unparsed>";
        }
    }

    // ---- 安全投影（R9：导出只含最小元数据） ----

    /** 导出为安全 Map：仅含最小字段，任何原始敏感值都不会出现。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("traceId", traceId);
        m.put("stepId", stepId == null ? "" : stepId);
        m.put("policyVersion", policyVersion);
        m.put("actionCategory", actionCategory);
        m.put("targetHost", targetHost);
        m.put("durationMs", durationMs);
        m.put("outcome", outcome.name());
        m.put("approvalRef", approvalRef == null ? "" : approvalRef);
        m.put("errorClass", errorClass == null ? "" : errorClass);
        m.put("recordedAt", recordedAt.toString());
        return m;
    }

    // ---- accessors ----

    public String traceId() { return traceId; }
    public String stepId() { return stepId; }
    public String policyVersion() { return policyVersion; }
    public String actionCategory() { return actionCategory; }
    public String targetHost() { return targetHost; }
    public long durationMs() { return durationMs; }
    public Outcome outcome() { return outcome; }
    public String approvalRef() { return approvalRef; }
    public String errorClass() { return errorClass; }
    public Instant recordedAt() { return recordedAt; }

    // ---- builder ----

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String traceId;
        private String stepId;
        private String policyVersion;
        private String actionCategory;
        private String targetHost;
        private long durationMs;
        private Outcome outcome;
        private String approvalRef;
        private String errorClass;
        private Instant recordedAt;

        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder stepId(String v) { this.stepId = v; return this; }
        public Builder policyVersion(String v) { this.policyVersion = v; return this; }
        public Builder actionCategory(String v) { this.actionCategory = v; return this; }
        public Builder targetHost(String v) { this.targetHost = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder outcome(Outcome v) { this.outcome = v; return this; }
        public Builder approvalRef(String v) { this.approvalRef = v; return this; }
        public Builder errorClass(String v) { this.errorClass = v; return this; }
        public Builder recordedAt(Instant v) { this.recordedAt = v; return this; }

        public AgentTrace build() { return new AgentTrace(this); }
    }

    private static String requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("AgentTrace." + name + " must not be blank");
        }
        return v;
    }
}

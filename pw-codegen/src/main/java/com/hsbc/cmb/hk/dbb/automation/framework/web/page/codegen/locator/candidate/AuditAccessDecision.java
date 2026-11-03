package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

/**
 * 审计读取侧鉴权的<b>判定结果</b>（R9 访问控制 / fail-closed）。
 *
 * <p>要么 {@link Result#ALLOWED}（携带实际生效的导出级别，等于请求级别，因越权请求已被拒绝而非降级），
 * 要么 {@link Result#DENIED}（携带拒绝原因，不暴露任何内容）。
 *
 * <p>设计取舍：越权请求<b>拒绝</b>而非"静默降级到角色上限"。降级看似温和，却会掩盖"调用方以为拿到了 FULL、
 * 实际只拿到 SANITIZED"的错位，削弱访问控制的可观测性；直接拒绝能让调用方立刻知道越权，符合 R9 红线矩阵
 * "越权访问测试失败"的要求。
 */
public final class AuditAccessDecision {

    /** 判定结果。 */
    public enum Result {
        /** 允许，按 {@link #exposure()} 导出。 */
        ALLOWED,
        /** 拒绝，不得导出任何内容。 */
        DENIED
    }

    private final Result result;
    private final AuditExposure exposure;
    private final String reason;

    private AuditAccessDecision(Result result, AuditExposure exposure, String reason) {
        this.result = result;
        this.exposure = exposure;
        this.reason = reason;
    }

    /** 允许导出，生效级别即请求级别。 */
    public static AuditAccessDecision allow(AuditExposure exposure) {
        return new AuditAccessDecision(Result.ALLOWED, exposure, null);
    }

    /** 拒绝导出，附带可读原因（供 CLI 报错 / 日志）。 */
    public static AuditAccessDecision deny(String reason) {
        return new AuditAccessDecision(Result.DENIED, null, reason);
    }

    /** 是否允许导出。 */
    public boolean allowed() {
        return result == Result.ALLOWED;
    }

    /**
     * 实际生效的导出级别（仅 {@link Result#ALLOWED} 时有效）。
     * 被拒绝时调用会抛 {@link IllegalStateException}。
     */
    public AuditExposure exposure() {
        if (result != Result.ALLOWED) {
            throw new IllegalStateException("拒绝状态下没有生效导出级别：" + reason);
        }
        return exposure;
    }

    /** 拒绝原因（仅 {@link Result#DENIED} 时非空）。 */
    public String reason() {
        return reason;
    }
}

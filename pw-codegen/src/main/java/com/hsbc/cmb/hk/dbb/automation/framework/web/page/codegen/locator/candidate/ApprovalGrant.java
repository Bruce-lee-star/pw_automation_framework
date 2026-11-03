package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <b>授权凭据</b>（设计 §9 P4）：一次审批的完整结果，而不是一个审批人名字。
 *
 * <h2>为什么审批要落成凭据</h2>
 * <p>旧实现只记录 {@code approvedBy + appVersion}。这留下四个真实缺口：
 * <ol>
 *   <li><b>批的不是眼前这份内容</b>——定位符在审批前后被改过，凭据上看不出来；</li>
 *   <li><b>在测试环境批的，拿到生产用</b>——凭据不绑定 origin；</li>
 *   <li><b>一次审批永久有效</b>——页面早就改版，凭据还"活着"；</li>
 *   <li><b>谁批的都算</b>——没有业务后果分级，也没有职责分离。</li>
 * </ol>
 * 凭据把「谁、批的哪份内容、在哪个环境、哪个版本、到什么时候为止」写成不可变的五个绑定，
 * 回注时逐项核对，任何一项对不上就返回空（fail-closed）。
 *
 * <h2>有效期不是"自动续期"的理由</h2>
 * <p>过期即视为无效，{@link CandidateLibrary#productionLocator} 不会因为"昨天刚过期"而放行。
 * 续期只能重新取证、重新验证、重新审批——这是让定位器持续被真实页面校验的唯一手段。
 */
public final class ApprovalGrant {

    private static final String DIGEST_PREFIX = "locator-v1";

    private final String candidateId;
    private final RiskTier riskTier;
    private final String planDigest;
    private final String origin;
    private final String appVersion;
    private final List<String> approvers;
    private final String note;
    private final Instant issuedAt;
    private final Instant expiresAt;

    private ApprovalGrant(String candidateId, RiskTier riskTier, String planDigest, String origin,
                          String appVersion, List<String> approvers, String note,
                          Instant issuedAt, Instant expiresAt) {
        this.candidateId = Objects.requireNonNull(candidateId, "candidateId");
        this.riskTier = Objects.requireNonNull(riskTier, "riskTier");
        this.planDigest = Objects.requireNonNull(planDigest, "planDigest");
        this.origin = normalizeOrigin(origin);
        if (appVersion == null || appVersion.isBlank()) {
            throw new IllegalArgumentException("appVersion 不可为空：审批必须绑定目标应用版本");
        }
        this.appVersion = appVersion.trim();
        this.approvers = List.copyOf(approvers);
        this.note = note;
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * 签发凭据（只在 {@link ApprovalPolicy} 全部规则通过后调用）。
     *
     * @param validityDays 有效期天数；上限由 {@link RiskTier#maxValidityDays()} 约束，越界应在策略层拦截
     */
    public static ApprovalGrant issue(String candidateId, RiskTier riskTier, String planDigest, String origin,
                                      String appVersion, List<String> approvers, String note,
                                      Instant issuedAt, int validityDays) {
        if (validityDays <= 0) {
            throw new IllegalArgumentException("有效期必须为正天数，实际：" + validityDays);
        }
        return new ApprovalGrant(candidateId, riskTier, planDigest, normalizeOrigin(origin), appVersion,
                cleanApprovers(approvers), blankToNull(note), issuedAt,
                issuedAt.plus(Duration.ofDays(validityDays)));
    }

    /**
     * 从持久化恢复（保留原始签发与到期时刻，不按天数重算）。
     *
     * <p>重算会让"落盘 → 读取"之间出现秒级漂移，审计对账时难以判断是同一条审批还是两次审批。
     */
    static ApprovalGrant restore(String candidateId, RiskTier riskTier, String planDigest, String origin,
                                 String appVersion, List<String> approvers, String note,
                                 Instant issuedAt, Instant expiresAt) {
        return new ApprovalGrant(candidateId, riskTier, planDigest, origin, appVersion,
                cleanApprovers(approvers), blankToNull(note), issuedAt, expiresAt);
    }

    /**
     * 计划摘要：把「被批准的那份内容」压成一个可比较的指纹。
     *
     * <p>覆盖目标语义 + 定位符 + 期望角色——这三个字段任何一处变化都意味着"批的东西变了"。
     * 摘要<b>不</b>包含证据与审批信息：重新取证不应该让摘要变化，否则每次取证都要重新审批，人会开始嫌麻烦而绕过流程。
     */
    public static String planDigestOf(LocatorCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String payload = DIGEST_PREFIX + '\n'
                + candidate.targetRef() + '\n'
                + candidate.canonical() + '\n'
                + (candidate.expectedRole() == null ? "" : candidate.expectedRole());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境被裁剪——不能降级成弱摘要，只能失败。
            throw new IllegalStateException("运行环境缺少 SHA-256，无法计算计划摘要", e);
        }
    }

    public String candidateId() {
        return candidateId;
    }

    public RiskTier riskTier() {
        return riskTier;
    }

    public String planDigest() {
        return planDigest;
    }

    /** 授权适用的页面来源（scheme://host 或环境标识），回注时逐字比对。 */
    public String origin() {
        return origin;
    }

    public String appVersion() {
        return appVersion;
    }

    public List<String> approvers() {
        return approvers;
    }

    /** 首位审批人（兼容旧的 approvedBy 语义，用于列表展示）。 */
    public String primaryApprover() {
        return approvers.isEmpty() ? null : approvers.get(0);
    }

    /** 审批理由/工单引用（可空）。 */
    public String note() {
        return note;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean expiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(expiresAt);
    }

    /** 距到期剩余天数（已过期为负数），供审计报告提示"即将到期"。 */
    public long daysUntilExpiry(Instant now) {
        return Duration.between(now, expiresAt).toDays();
    }

    /**
     * 回注前的逐项核对：<b>未过期 + origin 一致 + 应用版本一致 + 计划摘要一致</b>。
     *
     * <p>任一项不满足即返回 {@code false}，调用方不得据此推断"那就用旧的"。
     */
    public boolean covers(String origin, String appVersion, String currentPlanDigest, Instant now) {
        if (expiredAt(now)) {
            return false;
        }
        if (origin == null || appVersion == null || currentPlanDigest == null) {
            return false;
        }
        return this.origin.equals(normalizeOrigin(origin))
                && this.appVersion.equals(appVersion.trim())
                && this.planDigest.equalsIgnoreCase(currentPlanDigest.trim());
    }

    /** 不满足绑定的具体原因（回注失败时逐条告知，避免"就是取不到"的黑箱）。 */
    public String describeMismatch(String origin, String appVersion, String currentPlanDigest, Instant now) {
        List<String> reasons = new ArrayList<>();
        if (expiredAt(now)) {
            reasons.add("审批已于 " + expiresAt + " 过期，须重新取证并重新审批");
        }
        if (origin == null || !this.origin.equals(normalizeOrigin(origin))) {
            reasons.add("origin 不匹配（审批绑定 " + this.origin + "，请求 " + origin + "）");
        }
        if (appVersion == null || !this.appVersion.equals(appVersion.trim())) {
            reasons.add("应用版本不匹配（审批绑定 " + this.appVersion + "，请求 " + appVersion + "）");
        }
        if (currentPlanDigest == null || !this.planDigest.equalsIgnoreCase(currentPlanDigest.trim())) {
            reasons.add("计划摘要不匹配（审批绑定 " + this.planDigest + "，当前 " + currentPlanDigest
                    + "）：被批准的内容已变更");
        }
        return reasons.isEmpty() ? "（无差异）" : String.join("；", reasons);
    }

    @Override
    public String toString() {
        return "grant[" + candidateId + " " + riskTier + " origin=" + origin + " appVersion=" + appVersion
                + " digest=" + planDigest + " by=" + String.join(",", approvers)
                + " until=" + expiresAt + "]";
    }

    static String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("origin 不可为空：审批必须绑定页面来源/环境");
        }
        return origin.trim();
    }

    private static List<String> cleanApprovers(List<String> approvers) {
        List<String> cleaned = new ArrayList<>();
        if (approvers != null) {
            for (String a : approvers) {
                if (a != null && !a.isBlank()) {
                    cleaned.add(a.trim());
                }
            }
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("approvers 不可为空：审批必须落到具体的人");
        }
        return cleaned;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

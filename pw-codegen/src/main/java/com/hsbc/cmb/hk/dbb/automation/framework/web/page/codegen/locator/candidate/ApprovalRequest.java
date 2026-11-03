package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>审批请求</b>：审批人要显式声明"按什么后果、在哪个环境、批多久"。
 *
 * <p>它是 {@link ApprovalPolicy} 的唯一输入，刻意<b>不接受</b>"只给一个名字"的简写形式——
 * 少了任何一项，审批就退化成"某个人点了下头"，而不是一条可核对的授权。
 *
 * <p>{@code validityDays} 不填（0）时取该风险等级的上限；关键定位器通常应<b>显式给更短</b>的有效期。
 */
public final class ApprovalRequest {

    private final RiskTier riskTier;
    private final String origin;
    private final String appVersion;
    private final List<String> approvers;
    private final int validityDays;
    private final String planDigest;
    private final String note;

    private ApprovalRequest(RiskTier riskTier, String origin, String appVersion, List<String> approvers,
                            int validityDays, String planDigest, String note) {
        this.riskTier = riskTier;
        this.origin = origin;
        this.appVersion = appVersion;
        this.approvers = approvers;
        this.validityDays = validityDays;
        this.planDigest = planDigest;
        this.note = note;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RiskTier riskTier() {
        return riskTier;
    }

    public String origin() {
        return origin;
    }

    public String appVersion() {
        return appVersion;
    }

    public List<String> approvers() {
        return approvers == null ? List.of() : List.copyOf(approvers);
    }

    /** 声明有效期（天）；0 表示取风险等级上限。 */
    public int declaredValidityDays() {
        return validityDays;
    }

    /** 实际生效的有效期：未声明则取上限。 */
    public int effectiveValidityDays() {
        if (validityDays > 0) {
            return validityDays;
        }
        return riskTier == null ? 0 : riskTier.maxValidityDays();
    }

    /** 审批人自报的摘要（可空；空表示"按当前候选内容"，由策略层填入并回显）。 */
    public String planDigest() {
        return planDigest;
    }

    public String note() {
        return note;
    }

    /** 支持 {@code --approver "alice,bob"} 这种一次给多人的写法（CRITICAL 至少两人）。 */
    public static List<String> splitApprovers(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    public static final class Builder {
        private RiskTier riskTier;
        private String origin;
        private String appVersion;
        private List<String> approvers;
        private int validityDays;
        private String planDigest;
        private String note;

        public Builder riskTier(RiskTier riskTier) {
            this.riskTier = riskTier;
            return this;
        }

        public Builder riskTier(String raw) {
            this.riskTier = RiskTier.parse(raw);
            return this;
        }

        public Builder origin(String origin) {
            this.origin = origin;
            return this;
        }

        public Builder appVersion(String appVersion) {
            this.appVersion = appVersion;
            return this;
        }

        public Builder approvers(List<String> approvers) {
            this.approvers = approvers;
            return this;
        }

        public Builder approvers(String commaSeparated) {
            this.approvers = splitApprovers(commaSeparated);
            return this;
        }

        public Builder validityDays(int validityDays) {
            this.validityDays = validityDays;
            return this;
        }

        public Builder planDigest(String planDigest) {
            this.planDigest = planDigest;
            return this;
        }

        public Builder note(String note) {
            this.note = note;
            return this;
        }

        public ApprovalRequest build() {
            return new ApprovalRequest(riskTier, origin == null ? null : origin.trim(),
                    appVersion == null ? null : appVersion.trim(),
                    approvers == null ? List.of() : List.copyOf(approvers),
                    validityDays, planDigest == null ? null : planDigest.trim(),
                    note == null || note.isBlank() ? null : note.trim());
        }
    }
}

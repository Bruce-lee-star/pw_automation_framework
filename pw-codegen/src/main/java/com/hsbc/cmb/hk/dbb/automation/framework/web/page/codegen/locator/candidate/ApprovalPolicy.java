package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * <b>审批授权策略</b>（设计 §9 P4「按业务后果授权」）：判断一次审批是否构成有效授权，并签发 {@link ApprovalGrant}。
 *
 * <h2>与 {@link CandidateValidator} 的分工</h2>
 * <p>验证器回答"这条定位器是否<b>可以提交审批</b>"（技术事实）；策略回答"这次审批是否<b>构成授权</b>"（治理规则）。
 * 两者都 fail-closed，都不产出"次优结果"。
 *
 * <h2>七条规则，全部不短路</h2>
 * <p>一次把所有问题说完，避免审批人改一条跑一次、来回试到通过。
 *
 * <table border="1">
 *   <caption>规则与拒绝条件</caption>
 *   <tr><th>规则</th><th>拒绝条件</th></tr>
 *   <tr><td>{@code RISK_TIER_DECLARED}</td><td>没有声明业务后果等级（不提供默认档）</td></tr>
 *   <tr><td>{@code APPROVER_IDENTITY}</td><td>审批人为空、为占位符（manual/cli/system/…）或形如配置值</td></tr>
 *   <tr><td>{@code APPROVAL_COUNT}</td><td>不同审批人数少于该等级要求（CRITICAL 需 2 人）</td></tr>
 *   <tr><td>{@code SEPARATION_OF_DUTIES}</td><td>审批人 = 提出者；或该等级要求职责分离但提出者未登记</td></tr>
 *   <tr><td>{@code PLAN_DIGEST}</td><td>显式给出的摘要与当前候选内容不一致（批的是另一份东西）</td></tr>
 *   <tr><td>{@code ORIGIN_BOUND}</td><td>未绑定来源、含通配符 {@code *}、含空白</td></tr>
 *   <tr><td>{@code VALIDITY_WINDOW}</td><td>有效期非正，或超过该等级上限</td></tr>
 * </table>
 *
 * <h2>为什么"提出者未登记"要拒绝职责分离</h2>
 * <p>"没记录提出者"不等于"审批人不是提出者"。把它当通过，等于给了一条稳定绕过职责分离的路：
 * 只要提出时不署名，任何人都能自己批自己。宁可拒绝，让候选补齐署名。
 */
public final class ApprovalPolicy {

    public static final String RULE_RISK_TIER = "RISK_TIER_DECLARED";
    public static final String RULE_APPROVER_IDENTITY = "APPROVER_IDENTITY";
    public static final String RULE_APPROVAL_COUNT = "APPROVAL_COUNT";
    public static final String RULE_SEPARATION_OF_DUTIES = "SEPARATION_OF_DUTIES";
    public static final String RULE_PLAN_DIGEST = "PLAN_DIGEST";
    public static final String RULE_ORIGIN_BOUND = "ORIGIN_BOUND";
    public static final String RULE_APP_VERSION_BOUND = "APP_VERSION_BOUND";
    public static final String RULE_VALIDITY_WINDOW = "VALIDITY_WINDOW";

    /** 不能当作审批人的占位身份：这些值通常来自"没填"或"脚本默认值"，不是真实的人。 */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "manual", "cli", "system", "unknown", "anonymous", "nobody", "true", "false", "none", "-");

    /**
     * 判定一次审批。
     *
     * @param record  已通过验证、等待审批的候选记录（状态检查由 {@link CandidateLibrary} 负责）
     * @param request 审批请求
     * @param now     判定时刻（由注入的 Clock 提供，便于测试固定时间）
     */
    public Verdict evaluate(CandidateRecord record, ApprovalRequest request, Instant now) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");

        RiskTier tier = request.riskTier();
        List<String> approvers = distinctApprovers(request.approvers());
        String currentDigest = ApprovalGrant.planDigestOf(record.candidate());

        List<CandidateValidator.Finding> findings = new ArrayList<>();
        findings.add(riskTierDeclared(tier));
        findings.add(approverIdentity(approvers));
        findings.add(approvalCount(tier, approvers));
        findings.add(separationOfDuties(tier, approvers, record.candidate().proposedBy()));
        findings.add(planDigest(request.planDigest(), currentDigest));
        findings.add(originBound(request.origin()));
        findings.add(appVersionBound(request.appVersion()));
        findings.add(validityWindow(tier, request.declaredValidityDays(), request.effectiveValidityDays()));

        ApprovalGrant grant = null;
        if (findings.stream().allMatch(CandidateValidator.Finding::passed)) {
            grant = ApprovalGrant.issue(record.id(), tier, currentDigest, request.origin(),
                    request.appVersion(), approvers, request.note(), now, request.effectiveValidityDays());
        }
        return new Verdict(findings, grant);
    }

    private CandidateValidator.Finding riskTierDeclared(RiskTier tier) {
        return tier != null
                ? CandidateValidator.Finding.pass(RULE_RISK_TIER, tier + "：" + tier.describe())
                : CandidateValidator.Finding.fail(RULE_RISK_TIER,
                        "未声明业务后果等级（risk）：审批强度必须显式给出，不提供默认档");
    }

    private CandidateValidator.Finding approverIdentity(List<String> approvers) {
        if (approvers.isEmpty()) {
            return CandidateValidator.Finding.fail(RULE_APPROVER_IDENTITY,
                    "未提供审批人：审批必须落到具体的人");
        }
        for (String a : approvers) {
            if (a.length() < 2) {
                return CandidateValidator.Finding.fail(RULE_APPROVER_IDENTITY, "审批人过短，疑似误填：" + a);
            }
            if (PLACEHOLDERS.contains(a.toLowerCase(Locale.ROOT))) {
                return CandidateValidator.Finding.fail(RULE_APPROVER_IDENTITY,
                        "审批人是占位符而非具体的人：" + a + "（这类值通常来自脚本默认值）");
            }
            if (a.chars().anyMatch(Character::isWhitespace) || a.contains("=")) {
                return CandidateValidator.Finding.fail(RULE_APPROVER_IDENTITY,
                        "审批人含空白或等号，疑似误填参数：" + a);
            }
        }
        return CandidateValidator.Finding.pass(RULE_APPROVER_IDENTITY, "审批人：" + String.join(", ", approvers));
    }

    private CandidateValidator.Finding approvalCount(RiskTier tier, List<String> approvers) {
        if (tier == null) {
            return CandidateValidator.Finding.fail(RULE_APPROVAL_COUNT, "等级未声明，无法确定所需审批人数");
        }
        int required = tier.requiredApprovals();
        if (approvers.size() < required) {
            return CandidateValidator.Finding.fail(RULE_APPROVAL_COUNT,
                    tier + " 需要 " + required + " 名不同审批人，实际 " + approvers.size()
                            + "（同一人重复填写只算一名）");
        }
        return CandidateValidator.Finding.pass(RULE_APPROVAL_COUNT,
                "审批人数满足：" + approvers.size() + " / " + required);
    }

    private CandidateValidator.Finding separationOfDuties(RiskTier tier, List<String> approvers, String proposedBy) {
        if (tier == null || !tier.separationOfDuties()) {
            return CandidateValidator.Finding.pass(RULE_SEPARATION_OF_DUTIES,
                    "该等级允许自审（自审会在事件链中标注）");
        }
        if (proposedBy == null || proposedBy.isBlank()) {
            return CandidateValidator.Finding.fail(RULE_SEPARATION_OF_DUTIES,
                    "候选未登记提出者：无法确认职责分离，不得审批 " + tier + " 及以上的定位器");
        }
        for (String a : approvers) {
            if (a.equalsIgnoreCase(proposedBy)) {
                return CandidateValidator.Finding.fail(RULE_SEPARATION_OF_DUTIES,
                        "审批人与提出者相同（" + a + "）：" + tier + " 要求职责分离");
            }
        }
        return CandidateValidator.Finding.pass(RULE_SEPARATION_OF_DUTIES,
                "审批人均非提出者（提出者：" + proposedBy + "）");
    }

    private CandidateValidator.Finding planDigest(String declared, String current) {
        if (declared == null || declared.isBlank()) {
            return CandidateValidator.Finding.pass(RULE_PLAN_DIGEST, "按当前内容签发（摘要 " + current + "）");
        }
        if (!declared.equalsIgnoreCase(current)) {
            return CandidateValidator.Finding.fail(RULE_PLAN_DIGEST,
                    "审批声明的摘要 " + declared + " 与当前内容 " + current + " 不一致："
                            + "被批准的对象已变更，须重新核对后再批");
        }
        return CandidateValidator.Finding.pass(RULE_PLAN_DIGEST, "摘要与当前内容一致：" + current);
    }

    private CandidateValidator.Finding originBound(String origin) {
        if (origin == null || origin.isBlank()) {
            return CandidateValidator.Finding.fail(RULE_ORIGIN_BOUND,
                    "未绑定 origin：审批必须限定在具体环境/来源，避免测试环境审批被拿去生产");
        }
        String o = origin.trim();
        if (o.contains("*")) {
            return CandidateValidator.Finding.fail(RULE_ORIGIN_BOUND,
                    "origin 不得含通配符：" + o + "（通配等于不限定环境）");
        }
        if (o.chars().anyMatch(Character::isWhitespace)) {
            return CandidateValidator.Finding.fail(RULE_ORIGIN_BOUND, "origin 含空白：" + o);
        }
        if (o.length() > 200) {
            return CandidateValidator.Finding.fail(RULE_ORIGIN_BOUND, "origin 过长（>200）：" + o);
        }
        return CandidateValidator.Finding.pass(RULE_ORIGIN_BOUND, "绑定来源：" + o);
    }

    private CandidateValidator.Finding appVersionBound(String appVersion) {
        if (appVersion == null || appVersion.isBlank()) {
            return CandidateValidator.Finding.fail(RULE_APP_VERSION_BOUND,
                    "未绑定目标应用版本：审批只对某个版本有效，页面改版后须重新审批");
        }
        return CandidateValidator.Finding.pass(RULE_APP_VERSION_BOUND, "绑定应用版本：" + appVersion.trim());
    }

    private CandidateValidator.Finding validityWindow(RiskTier tier, int declared, int effective) {        if (tier == null) {
            return CandidateValidator.Finding.fail(RULE_VALIDITY_WINDOW, "等级未声明，无法确定有效期上限");
        }
        if (effective <= 0) {
            return CandidateValidator.Finding.fail(RULE_VALIDITY_WINDOW, "有效期必须为正天数，实际：" + effective);
        }
        if (declared > tier.maxValidityDays()) {
            return CandidateValidator.Finding.fail(RULE_VALIDITY_WINDOW,
                    "声明有效期 " + declared + " 天超过 " + tier + " 上限 " + tier.maxValidityDays()
                            + " 天：不允许靠延长有效期省掉重新取证");
        }
        return CandidateValidator.Finding.pass(RULE_VALIDITY_WINDOW, "有效期 " + effective + " 天（上限 "
                + tier.maxValidityDays() + " 天）");
    }

    /** 去空、去重（大小写不敏感）——同一人写两遍不能凑成"两人审批"。 */
    private static List<String> distinctApprovers(List<String> raw) {
        Set<String> seen = new LinkedHashSet<>();
        for (String a : raw) {
            if (a != null && !a.isBlank()) {
                boolean dup = seen.stream().anyMatch(x -> x.equalsIgnoreCase(a.trim()));
                if (!dup) {
                    seen.add(a.trim());
                }
            }
        }
        return new ArrayList<>(seen);
    }

    /** 审批结论：{@link #approved()} 为真时才带 {@link #grant()}。 */
    public static final class Verdict {

        private final List<CandidateValidator.Finding> findings;
        private final ApprovalGrant grant;

        Verdict(List<CandidateValidator.Finding> findings, ApprovalGrant grant) {
            this.findings = List.copyOf(findings);
            this.grant = grant;
        }

        public boolean approved() {
            return grant != null;
        }

        public List<CandidateValidator.Finding> findings() {
            return findings;
        }

        public List<CandidateValidator.Finding> failures() {
            return findings.stream().filter(f -> !f.passed()).toList();
        }

        /** 通过时非空；未通过时为 {@code null}（不会给出"半个凭据"）。 */
        public ApprovalGrant grant() {
            return grant;
        }

        public String summary() {
            if (approved()) {
                return "PASS（" + findings.size() + " 条规则全部通过，有效期至 " + grant.expiresAt() + "）";
            }
            StringBuilder sb = new StringBuilder("FAIL");
            for (CandidateValidator.Finding f : failures()) {
                sb.append("\n- ").append(f.ruleId()).append(": ").append(f.detail());
            }
            return sb.toString();
        }
    }
}

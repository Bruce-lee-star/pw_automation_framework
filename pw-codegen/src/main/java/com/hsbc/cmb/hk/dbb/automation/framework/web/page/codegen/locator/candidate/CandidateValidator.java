package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选定位器的<b>确定性验证器</b>（设计 §4.4 / P3）：目标语义、唯一性、可见性、可操作性、业务断言。
 *
 * <h2>它只回答一个问题</h2>
 * <p>"这条候选<b>是否可以提交审批</b>"——不是"是否生产可用"。验证通过只是 {@code VALIDATED}，
 * 变成生产定位器必须再经 {@link CandidateLibrary#approve} 的人工审批。
 *
 * <h2>fail-closed</h2>
 * <p>五条规则<b>全部</b>要有正面证据才通过；缺证据（未观测命中数、未提供业务断言）一律判不通过，
 * 而不是"默认没问题"。无法确认时宁可停下转人工，也不回退到可能命中别的元素的定位器。
 *
 * <h2>业务断言为什么必填</h2>
 * <p>"定位到了"与"点的是对的"是两件事：一个 CSS 可能稳定命中唯一元素，却在业务上早已不是那个按钮。
 * 因此业务断言（点击后的可见结果 / 页面状态）是候选可信的必要条件，不是加分项。
 */
public final class CandidateValidator {

    public static final String RULE_TARGET_SEMANTICS = "TARGET_SEMANTICS";
    public static final String RULE_UNIQUENESS = "UNIQUENESS";
    public static final String RULE_VISIBILITY = "VISIBILITY";
    public static final String RULE_ACTIONABILITY = "ACTIONABILITY";
    public static final String RULE_BUSINESS_ASSERTION = "BUSINESS_ASSERTION";

    /** 五条规则全跑一遍（不短路，便于一次看清所有问题）。 */
    public Validation validate(LocatorCandidate candidate, CandidateEvidence evidence) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate 不可为空");
        }
        if (evidence == null) {
            throw new IllegalArgumentException("evidence 不可为空：无证据不得验证（fail-closed）");
        }
        List<Finding> findings = new ArrayList<>();
        findings.add(targetSemantics(candidate, evidence));
        findings.add(uniqueness(evidence));
        findings.add(visibility(evidence));
        findings.add(actionability(evidence));
        findings.add(businessAssertion(evidence));
        return new Validation(findings);
    }

    private Finding targetSemantics(LocatorCandidate c, CandidateEvidence e) {
        LocatorSpec spec = c.spec();
        if (c.expectedRole() != null) {
            if (spec.strategy() != LocatorSpec.Strategy.ROLE) {
                return Finding.fail(RULE_TARGET_SEMANTICS, "目标语义要求 role=" + c.expectedRole()
                        + "，但候选策略是 " + spec.strategy() + "（不得用结构定位顶替语义定位）");
            }
            if (!c.expectedRole().equalsIgnoreCase(spec.role())) {
                return Finding.fail(RULE_TARGET_SEMANTICS, "目标语义 role=" + c.expectedRole()
                        + "，候选 role=" + spec.role());
            }
        }
        if (!e.textMatched()) {
            return Finding.fail(RULE_TARGET_SEMANTICS,
                    "可访问名/文案与目标不一致（未提供匹配证据视为不一致）");
        }
        return Finding.pass(RULE_TARGET_SEMANTICS, "语义锚点一致：" + c.canonical());
    }

    private Finding uniqueness(CandidateEvidence e) {
        if (e.matches() < 0) {
            return Finding.fail(RULE_UNIQUENESS, "未观测命中数（缺证据不得判通过）");
        }
        if (e.matches() != 1) {
            return Finding.fail(RULE_UNIQUENESS, "命中 " + e.matches() + " 个元素，要求恰好 1 个");
        }
        return Finding.pass(RULE_UNIQUENESS, "唯一命中");
    }

    private Finding visibility(CandidateEvidence e) {
        return e.visible()
                ? Finding.pass(RULE_VISIBILITY, "元素可见")
                : Finding.fail(RULE_VISIBILITY, "元素不可见（display/visibility/offscreen）");
    }

    private Finding actionability(CandidateEvidence e) {
        return e.enabled()
                ? Finding.pass(RULE_ACTIONABILITY, "元素可操作")
                : Finding.fail(RULE_ACTIONABILITY, "元素不可操作（disabled / 被遮罩 / readonly）");
    }

    private Finding businessAssertion(CandidateEvidence e) {
        if (e.businessAssertion() == null || e.businessAssertion().isBlank()) {
            return Finding.fail(RULE_BUSINESS_ASSERTION, "未提供业务断言（fail-closed，业务断言为必要条件）");
        }
        if (e.assertionPassed() == null) {
            return Finding.fail(RULE_BUSINESS_ASSERTION,
                    "业务断言未执行：" + e.businessAssertion());
        }
        if (!e.assertionPassed()) {
            return Finding.fail(RULE_BUSINESS_ASSERTION,
                    "业务断言失败：" + e.businessAssertion());
        }
        return Finding.pass(RULE_BUSINESS_ASSERTION, "业务断言通过：" + e.businessAssertion());
    }

    /** 单条规则结论。 */
    public record Finding(String ruleId, boolean passed, String detail) {

        static Finding pass(String ruleId, String detail) {
            return new Finding(ruleId, true, detail);
        }

        static Finding fail(String ruleId, String detail) {
            return new Finding(ruleId, false, detail);
        }
    }

    /** 验证结论：{@link #passed()} = 可提交审批（不等同生产可用）。 */
    public static final class Validation {

        private final List<Finding> findings;

        Validation(List<Finding> findings) {
            this.findings = List.copyOf(findings);
        }

        public boolean passed() {
            return findings.stream().allMatch(Finding::passed);
        }

        public List<Finding> findings() {
            return findings;
        }

        public List<Finding> failures() {
            return findings.stream().filter(f -> !f.passed()).toList();
        }

        /** 失败摘要（一条一行），用于日志 / 候选库 detail。 */
        public String summary() {
            if (passed()) {
                return "PASS（" + findings.size() + " 条规则全部通过）";
            }
            StringBuilder sb = new StringBuilder("FAIL");
            for (Finding f : failures()) {
                sb.append("\n- ").append(f.ruleId()).append(": ").append(f.detail());
            }
            return sb.toString();
        }
    }
}

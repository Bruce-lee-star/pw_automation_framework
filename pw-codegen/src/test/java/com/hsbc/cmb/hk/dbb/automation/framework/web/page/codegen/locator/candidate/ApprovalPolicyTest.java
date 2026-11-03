package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ApprovalPolicy} 规则测试：<b>审批强度必须与业务后果对齐</b>，且任何一条不满足都不得签发凭据。
 *
 * <p>每条用例对应一条规则，断言"该规则确实被触发"，而不只是"整体被拒"——
 * 否则规则之间互相掩盖，改坏一条也测不出来。
 */
public class ApprovalPolicyTest {

    private static final Instant T0 = Instant.parse("2026-11-03T02:00:00Z");
    private static final String ORIGIN = "https://dbb-uat.example.com";

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final CandidateLibrary library = new CandidateLibrary(new CandidateValidator(), clock);
    private final ApprovalPolicy policy = new ApprovalPolicy();

    private CandidateRecord validated(String proposedBy) {
        LocatorCandidate c = LocatorCandidate.of("login.submit", LocatorSpec.role("button", "Log on"))
                .withExpectedRole("button")
                .withProposedBy(proposedBy);
        CandidateRecord r = library.propose(c);
        library.submitForValidation(r.id(), CandidateEvidence.builder()
                .matches(1).visible(true).enabled(true).textMatched(true)
                .businessAssertion("提交后出现成功提示").assertionPassed(true).build());
        assertEquals(CandidateState.VALIDATED, r.state());
        return r;
    }

    private ApprovalRequest.Builder req() {
        return ApprovalRequest.builder().approvers("bob").origin(ORIGIN).appVersion("APP-1.0");
    }

    private static void assertRule(ApprovalPolicy.Verdict v, String ruleId, boolean expectedPass) {
        CandidateValidator.Finding f = v.findings().stream()
                .filter(x -> x.ruleId().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结论里缺少规则 " + ruleId));
        assertEquals(ruleId + " 期望 " + (expectedPass ? "PASS" : "FAIL") + "，实际：" + f.detail(),
                expectedPass, f.passed());
    }

    @Test
    public void risk_tier_has_no_default() {
        ApprovalPolicy.Verdict v = policy.evaluate(validated("alice"), req().build(), T0);

        assertRule(v, ApprovalPolicy.RULE_RISK_TIER, false);
        assertFalse(v.approved());
        assertNull("未通过时不得给出半个凭据", v.grant());
        assertTrue("提示要说明等级不提供默认值", v.summary().contains("不提供默认档"));
    }

    @Test
    public void placeholder_approver_is_not_a_person() {
        for (String placeholder : List.of("manual", "cli", "system", "true")) {
            ApprovalPolicy.Verdict v = policy.evaluate(validated("alice"),
                    req().approvers(placeholder).riskTier(RiskTier.LOW).build(), T0);
            assertRule(v, ApprovalPolicy.RULE_APPROVER_IDENTITY, false);
            assertFalse("占位符不能当审批人：" + placeholder, v.approved());
        }
    }

    @Test
    public void critical_requires_two_distinct_people() {
        ApprovalPolicy.Verdict one = policy.evaluate(validated("alice"),
                req().approvers("bob").riskTier(RiskTier.CRITICAL).build(), T0);
        assertRule(one, ApprovalPolicy.RULE_APPROVAL_COUNT, false);

        ApprovalPolicy.Verdict dupe = policy.evaluate(validated("alice"),
                req().approvers("bob,bob").riskTier(RiskTier.CRITICAL).build(), T0);
        assertRule(dupe, ApprovalPolicy.RULE_APPROVAL_COUNT, false);

        ApprovalPolicy.Verdict two = policy.evaluate(validated("alice"),
                req().approvers("bob,carol").riskTier(RiskTier.CRITICAL).build(), T0);
        assertRule(two, ApprovalPolicy.RULE_APPROVAL_COUNT, true);
        assertTrue(two.approved());
        assertEquals(List.of("bob", "carol"), two.grant().approvers());
    }

    @Test
    public void self_approval_allowed_only_for_low_risk() {
        ApprovalPolicy.Verdict low = policy.evaluate(validated("alice"),
                req().approvers("alice").riskTier(RiskTier.LOW).build(), T0);
        assertRule(low, ApprovalPolicy.RULE_SEPARATION_OF_DUTIES, true);
        assertTrue("LOW 允许自审", low.approved());

        ApprovalPolicy.Verdict medium = policy.evaluate(validated("alice"),
                req().approvers("alice").riskTier(RiskTier.MEDIUM).build(), T0);
        assertRule(medium, ApprovalPolicy.RULE_SEPARATION_OF_DUTIES, false);
        assertFalse(medium.approved());
    }

    @Test
    public void unknown_proposer_cannot_satisfy_separation_of_duties() {
        ApprovalPolicy.Verdict v = policy.evaluate(validated(null),
                req().approvers("bob").riskTier(RiskTier.HIGH).build(), T0);

        assertRule(v, ApprovalPolicy.RULE_SEPARATION_OF_DUTIES, false);
        assertTrue("必须说清为什么：没署名就等于任何人都能自批",
                v.summary().contains("未登记提出者"));
    }

    @Test
    public void declared_digest_must_match_current_content() {
        CandidateRecord r = validated("alice");
        String real = ApprovalGrant.planDigestOf(r.candidate());

        ApprovalPolicy.Verdict ok = policy.evaluate(r,
                req().riskTier(RiskTier.MEDIUM).planDigest(real).build(), T0);
        assertRule(ok, ApprovalPolicy.RULE_PLAN_DIGEST, true);

        ApprovalPolicy.Verdict stale = policy.evaluate(r,
                req().riskTier(RiskTier.MEDIUM).planDigest("0000000000000000").build(), T0);
        assertRule(stale, ApprovalPolicy.RULE_PLAN_DIGEST, false);
        assertFalse(stale.approved());
    }

    @Test
    public void origin_must_be_concrete() {
        for (String bad : List.of("", "  ", "https://*.example.com", "https://a b.example.com")) {
            ApprovalPolicy.Verdict v = policy.evaluate(validated("alice"),
                    req().origin(bad).riskTier(RiskTier.MEDIUM).build(), T0);
            assertRule(v, ApprovalPolicy.RULE_ORIGIN_BOUND, false);
            assertFalse("非法 origin 不得审批：" + bad, v.approved());
        }
    }

    @Test
    public void validity_cannot_exceed_tier_limit() {
        ApprovalPolicy.Verdict tooLong = policy.evaluate(validated("alice"),
                req().approvers("bob,carol").riskTier(RiskTier.CRITICAL).validityDays(30).build(), T0);
        assertRule(tooLong, ApprovalPolicy.RULE_VALIDITY_WINDOW, false);
        assertTrue(tooLong.summary().contains("上限"));

        ApprovalPolicy.Verdict ok = policy.evaluate(validated("alice"),
                req().approvers("bob,carol").riskTier(RiskTier.CRITICAL).validityDays(7).build(), T0);
        assertRule(ok, ApprovalPolicy.RULE_VALIDITY_WINDOW, true);
        assertEquals(T0.plusSeconds(7 * 24 * 3600L), ok.grant().expiresAt());
    }

    @Test
    public void default_validity_is_the_tier_ceiling() {
        ApprovalPolicy.Verdict v = policy.evaluate(validated("alice"),
                req().riskTier(RiskTier.MEDIUM).build(), T0);

        assertTrue(v.approved());
        assertEquals("未声明有效期时取该等级上限",
                T0.plusSeconds(RiskTier.MEDIUM.maxValidityDays() * 24 * 3600L), v.grant().expiresAt());
    }

    @Test
    public void grant_records_every_binding() {
        CandidateRecord r = validated("alice");
        ApprovalPolicy.Verdict v = policy.evaluate(r,
                req().riskTier(RiskTier.HIGH).note("工单 CHG-1024").build(), T0);

        ApprovalGrant g = v.grant();
        assertNotNull(g);
        assertEquals(r.id(), g.candidateId());
        assertEquals(RiskTier.HIGH, g.riskTier());
        assertEquals(ORIGIN, g.origin());
        assertEquals("APP-1.0", g.appVersion());
        assertEquals("工单 CHG-1024", g.note());
        assertEquals(ApprovalGrant.planDigestOf(r.candidate()), g.planDigest());
        assertEquals(T0, g.issuedAt());
        assertFalse(g.expiredAt(T0));
    }

    @Test
    public void plan_digest_tracks_content_not_evidence() {
        LocatorCandidate base = LocatorCandidate.of("login.submit", LocatorSpec.role("button", "Log on"))
                .withExpectedRole("button");
        String digest = ApprovalGrant.planDigestOf(base);

        assertEquals("同样内容 → 同样摘要", digest,
                ApprovalGrant.planDigestOf(LocatorCandidate.of("login.submit",
                        LocatorSpec.role("button", "Log on")).withExpectedRole("button")));
        assertFalse("定位符变了摘要必须变",
                digest.equals(ApprovalGrant.planDigestOf(
                        LocatorCandidate.of("login.submit", LocatorSpec.role("button", "Submit"))
                                .withExpectedRole("button"))));
        assertFalse("目标语义变了摘要必须变",
                digest.equals(ApprovalGrant.planDigestOf(
                        LocatorCandidate.of("login.cancel", LocatorSpec.role("button", "Log on"))
                                .withExpectedRole("button"))));
        assertFalse("语义角色变了摘要必须变",
                digest.equals(ApprovalGrant.planDigestOf(
                        LocatorCandidate.of("login.submit", LocatorSpec.role("link", "Log on"))
                                .withExpectedRole("link"))));
    }
}

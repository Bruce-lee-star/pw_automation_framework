package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import org.junit.Test;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 保留/删除策略：按状态设窗口；APPROVED 在授权过期 + 宽限后才可清；绝不误删仍可用的定位器。 */
public class RetentionPolicyTest {

    private static final Instant T0 = Instant.parse("2026-11-03T02:00:00Z");

    private static LocatorCandidate candidate(String target, String name) {
        return LocatorCandidate.of(target, LocatorSpec.role("button", name))
                .withExpectedRole("button")
                .withSource("recorder")
                .withProposedBy("recorder");
    }

    /**
     * 在"ageDays 天前"的时钟下构造一条处于给定状态的记录，使 lastEventAt 落在过去。
     * APPROVED 的授权从同一时刻签发、有效期 grantValidityDays 天。
     */
    private static CandidateRecord build(CandidateState state, long ageDays, int grantValidityDays) {
        Clock clock = Clock.fixed(T0.minus(Duration.ofDays(ageDays)), ZoneOffset.UTC);
        CandidateLibrary lib = new CandidateLibrary(new CandidateValidator(), clock);
        CandidateRecord r = lib.propose(candidate("t", "P"));
        Instant at = Instant.now(clock);
        CandidateEvidence pass = CandidateEvidence.builder().matches(1).visible(true).enabled(true)
                .textMatched(true).businessAssertion("a").assertionPassed(true).build();
        switch (state) {
            case VALIDATED:
                r.markValidated(new CandidateValidator().validate(r.candidate(), pass), pass, at);
                break;
            case REJECTED:
                r.markRejected(new CandidateValidator().validate(r.candidate(), pass), pass, "未命中", "recorder", at);
                break;
            case APPROVED:
                r.markValidated(new CandidateValidator().validate(r.candidate(), pass), pass, at);
                ApprovalGrant g = ApprovalGrant.issue(r.id(), RiskTier.CRITICAL,
                        ApprovalGrant.planDigestOf(r.candidate()), "https://uat", "APP-1",
                        List.of("bob", "carol"), null, at, grantValidityDays);
                r.markApproved(g, at);
                break;
            case RETIRED:
                r.markValidated(new CandidateValidator().validate(r.candidate(), pass), pass, at);
                r.markRetired("已下架", "alice", at);
                break;
            default:
                break;
        }
        return r;
    }

    @Test
    public void proposed_expires_after_30_days() {
        RetentionPolicy p = RetentionPolicy.defaults();
        assertFalse("29 天仍在保留期", p.isExpired(build(CandidateState.PROPOSED, 29, 0), T0));
        assertTrue("31 天应被清除", p.isExpired(build(CandidateState.PROPOSED, 31, 0), T0));
    }

    @Test
    public void validated_expires_after_180_days() {
        RetentionPolicy p = RetentionPolicy.defaults();
        assertFalse(p.isExpired(build(CandidateState.VALIDATED, 179, 0), T0));
        assertTrue(p.isExpired(build(CandidateState.VALIDATED, 181, 0), T0));
    }

    @Test
    public void rejected_expires_after_90_days() {
        RetentionPolicy p = RetentionPolicy.defaults();
        assertTrue(p.isExpired(build(CandidateState.REJECTED, 100, 0), T0));
        assertFalse(p.isExpired(build(CandidateState.REJECTED, 80, 0), T0));
    }

    @Test
    public void retired_expires_after_180_days() {
        RetentionPolicy p = RetentionPolicy.defaults();
        assertTrue(p.isExpired(build(CandidateState.RETIRED, 200, 0), T0));
        assertFalse(p.isExpired(build(CandidateState.RETIRED, 170, 0), T0));
    }

    @Test
    public void approved_not_expired_while_grant_valid_nor_during_grace() {
        RetentionPolicy p = RetentionPolicy.defaults();
        // 授权签发于 T0（ageDays=0）、有效期 7 天
        CandidateRecord r = build(CandidateState.APPROVED, 0, 7);
        assertFalse("授权未过期，绝不可被清除", p.isExpired(r, T0.plus(Duration.ofDays(6))));
        assertFalse("授权刚过期，仍在 365 天宽限内", p.isExpired(r, T0.plus(Duration.ofDays(8))));
        assertTrue("授权过期 + 宽限 365 天后才可被清除", p.isExpired(r, T0.plus(Duration.ofDays(400))));
    }

    @Test
    public void describe_includes_windows_and_grace() {
        assertTrue(RetentionPolicy.defaults().describe().contains("PROPOSED≤30d"));
        assertTrue(RetentionPolicy.defaults().describe().contains("365d"));
    }

    @Test
    public void purge_removes_only_expired_and_keeps_usable_approved() {
        // 库时钟设在 60 天前：A 是 60 天前的 PROPOSED（已过期）
        Clock past = Clock.fixed(T0.minus(Duration.ofDays(60)), ZoneOffset.UTC);
        CandidateLibrary lib = new CandidateLibrary(new CandidateValidator(), past);
        CandidateRecord a = lib.propose(candidate("a", "A")); // 60 天前 PROPOSED → 过期
        CandidateRecord b = lib.propose(candidate("b", "B"));
        b.markValidated(new CandidateValidator().validate(b.candidate(),
                        CandidateEvidence.builder().matches(1).visible(true).enabled(true)
                                .textMatched(true).businessAssertion("x").assertionPassed(true).build()),
                CandidateEvidence.builder().matches(1).visible(true).enabled(true)
                        .textMatched(true).businessAssertion("x").assertionPassed(true).build(), Instant.now(past));
        // B 的授权从 T0 签发、有效期 7 天：在 now=T0 时仍可用，宽限远未到
        ApprovalGrant g = ApprovalGrant.issue(b.id(), RiskTier.CRITICAL,
                ApprovalGrant.planDigestOf(b.candidate()), "https://uat", "APP-1",
                List.of("bob", "carol"), null, T0, 7);
        b.markApproved(g, Instant.now(past));

        PurgeReport report = lib.purgeExpired(T0);
        assertEquals("只清除了 1 条（过期的 A）", 1, report.removedCount());
        assertEquals("保留了 1 条（仍可用的 B）", 1, report.retainedCount());
        // 被清的是 A；B 仍在
        assertEquals(List.of(a.id()), report.removedIds());
        assertEquals(1, lib.size());
        assertEquals(1, lib.all().size());
        assertEquals(b.id(), lib.all().get(0).id());
    }
}

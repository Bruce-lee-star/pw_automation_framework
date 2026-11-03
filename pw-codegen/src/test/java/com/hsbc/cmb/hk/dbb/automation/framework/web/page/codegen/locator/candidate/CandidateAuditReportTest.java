package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import org.junit.Test;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 审计报告脱敏：FULL 与 SANITIZED 的输出对比。 */
public class CandidateAuditReportTest {

    private static final Instant T0 = Instant.parse("2026-11-03T02:00:00Z");

    private static CandidateLibrary approvedLibrary() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        CandidateLibrary lib = new CandidateLibrary(new CandidateValidator(), clock);
        CandidateRecord r = lib.propose(LocatorCandidate.of("transfer.confirm",
                        LocatorSpec.role("button", "Confirm transfer"))
                .withExpectedRole("button").withSource("recorder").withProposedBy("alice"));
        CandidateEvidence pass = CandidateEvidence.builder().matches(1).visible(true).enabled(true)
                .textMatched(true).businessAssertion("确认后出现回执").assertionPassed(true).build();
        r.markValidated(new CandidateValidator().validate(r.candidate(), pass), pass, T0);
        ApprovalGrant g = ApprovalGrant.issue(r.id(), RiskTier.CRITICAL,
                ApprovalGrant.planDigestOf(r.candidate()),
                "https://dbb-uat.example.com/transfer?token=secret", "APP-1",
                List.of("bob", "carol"), "工单 CHG-1024", T0, 7);
        r.markApproved(g, T0);
        return lib;
    }

    @Test
    public void full_report_keeps_identities_and_full_origin() {
        String md = CandidateAuditReport.toMarkdown(approvedLibrary(), null, T0, AuditExposure.FULL);
        assertTrue("完整报告含审批人实名", md.contains("bob") && md.contains("carol"));
        assertTrue("完整报告含 origin 全文（含路径/查询）",
                md.contains("https://dbb-uat.example.com/transfer?token=secret"));
        assertTrue("完整报告含 note", md.contains("CHG-1024"));
        assertTrue("完整报告含事件链自由文本", md.contains("人工审批通过"));
    }

    @Test
    public void sanitized_report_pseudonymizes_and_reduces_origin() {
        String md = CandidateAuditReport.toMarkdown(approvedLibrary(), null, T0, AuditExposure.SANITIZED);
        assertFalse("脱敏报告不应出现真实审批人", md.contains("bob") || md.contains("carol"));
        assertTrue("脱敏报告含确定性假名", md.contains("APPROVER-"));
        assertTrue("脱敏报告 origin 仅留 host", md.contains("https://dbb-uat.example.com"));
        assertFalse("脱敏报告不应包含 token 查询串", md.contains("token=secret"));
        assertTrue("脱敏报告隐去 note", md.contains("(redacted)"));
        assertFalse("脱敏报告不应出现事件链自由文本", md.contains("人工审批通过"));
        assertTrue("脱敏报告标注级别", md.contains("脱敏"));
    }

    @Test
    public void internal_report_keeps_names_and_full_origin() {
        String md = CandidateAuditReport.toMarkdown(approvedLibrary(), null, T0, AuditExposure.INTERNAL);
        assertTrue("内部报告保留审批人实名", md.contains("bob") && md.contains("carol"));
        assertTrue("内部报告保留 origin 全文", md.contains("https://dbb-uat.example.com/transfer?token=secret"));
        assertTrue("内部报告保留 note", md.contains("CHG-1024"));
        assertFalse("内部报告不应出现脱敏假名", md.contains("APPROVER-"));
    }
}

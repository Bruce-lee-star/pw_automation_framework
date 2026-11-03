package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import org.junit.Test;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R9 门禁矩阵（设计 line 817 / 994）：<b>审计记录不等于可安全重执行的回放脚本</b>。
 *
 * <p>本测试把这条不可变契约落成可证伪守护：无论脱敏级别，审计报告都是只读 Markdown 投影，
 * 不得包含任何可直接执行的命令、脚本或回放指令。防止将来有人往导出物里塞脚本、或把凭据以可重放形式呈现。
 */
public class CandidateAuditReportNonReplayableTest {

    private static final Instant T0 = Instant.parse("2026-11-03T02:00:00Z");

    /** 可直接执行的标记黑名单——命中任一项即说明导出物不再是"只读投影"。 */
    private static final String[] EXECUTABLE_MARKERS = {
            "<script", "<iframe", "<img", "#!", "curl ", "wget ",
            "POST ", "GET ", "PUT ", "DELETE ", "Bearer ", "authorization:", "password="
    };

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
    public void full_report_declares_non_replayable_and_has_no_executable_markers() {
        String md = CandidateAuditReport.toMarkdown(approvedLibrary(), null, T0, AuditExposure.FULL);
        assertR9DeclarationPresent(md);
        assertNoExecutableMarker(md, AuditExposure.FULL);
    }

    @Test
    public void internal_report_declares_non_replayable_and_has_no_executable_markers() {
        String md = CandidateAuditReport.toMarkdown(approvedLibrary(), null, T0, AuditExposure.INTERNAL);
        assertR9DeclarationPresent(md);
        assertNoExecutableMarker(md, AuditExposure.INTERNAL);
    }

    @Test
    public void sanitized_report_declares_non_replayable_and_has_no_executable_markers() {
        String md = CandidateAuditReport.toMarkdown(approvedLibrary(), null, T0, AuditExposure.SANITIZED);
        assertR9DeclarationPresent(md);
        assertNoExecutableMarker(md, AuditExposure.SANITIZED);
    }

    private static void assertR9DeclarationPresent(String md) {
        assertTrue("报告必须声明为只读审计投影", md.contains("只读审计投影"));
        assertTrue("报告必须声明不是可重执行的回放脚本", md.contains("不是可安全重执行的回放脚本"));
    }

    private static void assertNoExecutableMarker(String md, AuditExposure exposure) {
        for (String marker : EXECUTABLE_MARKERS) {
            assertFalse("导出物（" + exposure + "）不得包含可直接执行的标记：" + marker,
                    md.toLowerCase(java.util.Locale.ROOT).contains(marker.toLowerCase(java.util.Locale.ROOT)));
        }
    }
}

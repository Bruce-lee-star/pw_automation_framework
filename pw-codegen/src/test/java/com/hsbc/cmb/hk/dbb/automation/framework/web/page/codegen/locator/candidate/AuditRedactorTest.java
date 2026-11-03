package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 脱敏投影：不同级别对审批人/origin/证据路径/note/事件链的处理。 */
public class AuditRedactorTest {

    @Test
    public void full_keeps_everything() {
        AuditRedactor r = new AuditRedactor(AuditExposure.FULL);
        assertEquals("alice", r.approver("alice"));
        assertEquals("https://dbb-uat.example.com/path?token=x", r.origin("https://dbb-uat.example.com/path?token=x"));
        assertEquals("trace=C:\\Users\\alice\\builds\\t1.zip#frame-42",
                r.evidenceDetail("trace=C:\\Users\\alice\\builds\\t1.zip#frame-42"));
        assertEquals("工单 CHG-1024 含 PII", r.note("工单 CHG-1024 含 PII"));
        assertEquals("session#7", r.evidenceRef("session#7"));
        assertEquals("人工审批通过（origin=...）", r.eventDetail(CandidateState.APPROVED, "人工审批通过（origin=...）"));
    }

    @Test
    public void internal_strips_filesystem_paths_but_keeps_names() {
        AuditRedactor r = new AuditRedactor(AuditExposure.INTERNAL);
        assertEquals("alice", r.approver("alice"));
        assertEquals("https://dbb-uat.example.com/full/path", r.origin("https://dbb-uat.example.com/full/path"));
        // 绝对路径收敛为文件名，避免把用户名/目录带出
        // 输入带 trace= 前缀时，前缀必须原样保留
        assertEquals("trace=t1.zip#frame-42",
                r.evidenceDetail("trace=C:\\Users\\alice\\builds\\t1.zip#frame-42"));
        // 输入没有 trace= 前缀时（纯 Unix 绝对路径），不应凭空加前缀
        assertEquals("t1.zip#frame-42",
                r.evidenceDetail("/home/alice/builds/t1.zip#frame-42"));
        assertEquals("工单 CHG-1024 含 PII", r.note("工单 CHG-1024 含 PII"));
    }

    @Test
    public void sanitized_pseudonymizes_approver_and_reduces_origin() {
        AuditRedactor r = new AuditRedactor(AuditExposure.SANITIZED);
        String a1 = r.approver("alice");
        String a2 = r.approver("alice");
        assertTrue(a1.startsWith("APPROVER-"));
        assertEquals("同一人同一份报告内假名一致", a1, a2);
        // 不同的人假名不同
        assertFalse(r.approver("bob").equals(a1));
        // origin 仅留 host，剥掉路径/查询（可能带 token）
        assertEquals("https://dbb-uat.example.com",
                r.origin("https://dbb-uat.example.com/path?token=secret"));
        // 裸环境标识原样
        assertEquals("uat", r.origin("uat"));
    }

    @Test
    public void sanitized_hides_note_ref_and_drops_event_free_text() {
        AuditRedactor r = new AuditRedactor(AuditExposure.SANITIZED);
        assertEquals("(redacted)", r.note("工单 CHG-1024 含 PII"));
        assertEquals("(redacted)", r.evidenceRef("session#7"));
        // 事件链自由文本丢弃，只留状态名（detail 里常嵌 origin/审批人）
        assertEquals("APPROVED", r.eventDetail(CandidateState.APPROVED, "人工审批通过（risk=CRITICAL, origin=https://dbb-uat.example.com）"));
        assertEquals("PROPOSED", r.eventDetail(CandidateState.PROPOSED, "proposed"));
    }

    @Test
    public void sanitized_actor_shares_pseudonym_with_approver() {
        AuditRedactor r = new AuditRedactor(AuditExposure.SANITIZED);
        // 同一个人既是提出者又是审批者，假名应当一致，才能看出"自审"
        assertEquals(r.approver("alice"), r.actor("alice"));
    }

    @Test
    public void null_inputs_pass_through() {
        AuditRedactor r = new AuditRedactor(AuditExposure.SANITIZED);
        assertEquals(null, r.approver(null));
        assertEquals(null, r.note(null));
        assertEquals(null, r.evidenceRef(null));
        assertEquals(null, r.eventDetail(CandidateState.APPROVED, null));
    }
}

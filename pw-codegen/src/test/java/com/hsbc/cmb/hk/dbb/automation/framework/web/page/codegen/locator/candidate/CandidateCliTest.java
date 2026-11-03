package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * CLI 端到端测试：<b>提出 → 验证 → 审批 → 回注</b>用真实文件系统跑一遍。
 *
 * <p>每个 {@link #exec} 都独立 {@code load} 一次候选库，等同于"每条命令都是一个新进程"，
 * 因此这些用例同时验证了持久化与重启恢复，而不是只验证内存里的状态机。
 */
public class CandidateCliTest {

    private static final String GOOD_EVIDENCE = "{"
            + "\"matches\":1,\"visible\":true,\"enabled\":true,\"textMatched\":true,"
            + "\"businessAssertion\":\"提交后出现成功提示\",\"assertionPassed\":true,"
            + "\"detail\":\"trace=t1.zip#frame-42\"}";

    private static final String ORIGIN = "https://dbb-uat.example.com";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path lib;

    @Before
    public void setUp() {
        lib = tmp.getRoot().toPath().resolve("locators").resolve("candidates.json");
    }

    // ------------------------------------------------------------------
    // 主闭环
    // ------------------------------------------------------------------

    @Test
    public void full_loop_propose_validate_approve_then_resolve() throws IOException {
        Result p = exec("propose", "--lib", lib(), "--target", "login.submit",
                "--locator", "role=button;name=Log on", "--expected-role", "button",
                "--source", "manual", "--by", "alice");
        p.assertOk();
        assertTrue(p.out.contains("cand-0001"));
        assertTrue("提出阶段必须声明仍未验证", p.out.contains("PROPOSED"));

        Result earlyApprove = exec("approve", "--lib", lib(), "--id", "cand-0001",
                "--approver", "alice", "--risk", "LOW", "--origin", ORIGIN, "--app-version", "2026.11.3");
        assertEquals("未验证不得越级审批", CandidateCli.EXIT_REJECTED, earlyApprove.code);
        assertTrue(earlyApprove.err.contains("仅 VALIDATED 候选可审批"));

        Result beforeApproval = exec("locator", "--lib", lib(),
                "--target", "login.submit", "--app-version", "2026.11.3", "--origin", ORIGIN);
        assertEquals("未审批时不得回注", CandidateCli.EXIT_NOT_RESOLVED, beforeApproval.code);
        assertTrue(beforeApproval.err.contains("无一条 APPROVED"));
        assertEquals("失败时 stdout 必须为空（避免脚本拿到半截结果）", "", beforeApproval.out.trim());

        Result v = exec("validate", "--lib", lib(), "--id", "cand-0001",
                "--matches", "1", "--visible", "true", "--enabled", "true",
                "--text-matched", "true", "--assertion", "提交后出现成功提示", "--assertion-passed", "true");
        v.assertOk();
        assertTrue(v.out.contains("VALIDATED"));
        assertTrue(v.out.contains("[PASS] UNIQUENESS"));
        assertTrue(v.out.contains("[PASS] BUSINESS_ASSERTION"));

        Result a = exec("approve", "--lib", lib(), "--id", "cand-0001",
                "--approver", "alice", "--risk", "LOW", "--origin", ORIGIN, "--app-version", "2026.11.3");
        a.assertOk();
        assertTrue(a.out.contains("APPROVED"));
        assertTrue(a.out.contains("审批人    : alice"));
        assertTrue(a.out.contains("应用版本  : 2026.11.3"));
        assertTrue("审批必须写明绑定环境", a.out.contains(ORIGIN));
        assertTrue("审批必须回显计划摘要，便于对账", a.out.contains("计划摘要"));

        Result wrongVersion = exec("locator", "--lib", lib(),
                "--target", "login.submit", "--app-version", "2026.10.0", "--origin", ORIGIN);
        assertEquals("版本不符不得降级复用", CandidateCli.EXIT_NOT_RESOLVED, wrongVersion.code);
        assertTrue(wrongVersion.err.contains("应用版本不匹配"));

        Result hit = exec("locator", "--lib", lib(), "--target", "login.submit",
                "--app-version", "2026.11.3", "--origin", ORIGIN);
        hit.assertOk();
        assertEquals("stdout 只应有 canonical 一行，便于脚本直接取用",
                "role=button;name=Log on", hit.out.trim());
    }

    @Test
    public void evidence_can_be_supplied_as_json_file() throws IOException {
        exec("propose", "--lib", lib(), "--target", "login.submit",
                "--locator", "role=button;name=Log on").assertOk();
        Path evidence = tmp.getRoot().toPath().resolve("evidence.json");
        Files.writeString(evidence, GOOD_EVIDENCE, StandardCharsets.UTF_8);

        Result v = exec("validate", "--lib", lib(), "--id", "cand-0001",
                "--evidence", evidence.toString());
        v.assertOk();
        assertTrue(v.out.contains("VALIDATED"));

        Result show = exec("show", "--lib", lib(), "--id", "cand-0001");
        show.assertOk();
        assertTrue(show.out.contains("trace=t1.zip#frame-42"));
    }

    // ------------------------------------------------------------------
    // fail-closed 规则
    // ------------------------------------------------------------------

    @Test
    public void missing_evidence_is_usage_error_and_does_not_touch_library() throws IOException {
        exec("propose", "--lib", lib(), "--target", "login.submit",
                "--locator", "role=button;name=Log on").assertOk();
        byte[] before = Files.readAllBytes(lib);

        Result r = exec("validate", "--lib", lib(), "--id", "cand-0001");
        assertEquals(CandidateCli.EXIT_USAGE, r.code);
        assertTrue(r.err.contains("必须提供证据"));
        assertArrayEquals("用法错误绝不能把候选打成 REJECTED 终态", before, Files.readAllBytes(lib));
    }

    @Test
    public void non_unique_hit_rejects_candidate_and_keeps_evidence() throws IOException {
        exec("propose", "--lib", lib(), "--target", "login.submit",
                "--locator", "role=button;name=Log on").assertOk();

        Result v = exec("validate", "--lib", lib(), "--id", "cand-0001",
                "--matches", "2", "--visible", "true", "--enabled", "true",
                "--text-matched", "true", "--assertion", "提交后出现成功提示", "--assertion-passed", "true");
        assertEquals("命中 2 个即不通过", CandidateCli.EXIT_REJECTED, v.code);
        assertTrue(v.out.contains("REJECTED"));
        assertTrue(v.out.contains("[FAIL] UNIQUENESS"));
        assertTrue(v.err.contains("终态"));

        Result show = exec("show", "--lib", lib(), "--id", "cand-0001");
        show.assertOk();
        assertTrue("拒绝也要留证据以便追溯", show.out.contains("matches=2"));
        assertTrue(show.out.contains("REJECTED"));

        Result again = exec("validate", "--lib", lib(), "--id", "cand-0001",
                "--matches", "1", "--visible", "true", "--enabled", "true",
                "--text-matched", "true", "--assertion", "提交后出现成功提示", "--assertion-passed", "true");
        assertEquals("REJECTED 是终态，不可再验证", CandidateCli.EXIT_REJECTED, again.code);
        assertTrue(again.err.contains("REJECTED"));
    }

    @Test
    public void structural_locator_cannot_impersonate_semantic_target() throws IOException {
        exec("propose", "--lib", lib(), "--target", "login.submit",
                "--locator", "css=#submit", "--expected-role", "button").assertOk();

        Result v = exec("validate", "--lib", lib(), "--id", "cand-0001",
                "--matches", "1", "--visible", "true", "--enabled", "true",
                "--text-matched", "true", "--assertion", "提交后出现成功提示", "--assertion-passed", "true");
        assertEquals(CandidateCli.EXIT_REJECTED, v.code);
        assertTrue(v.out.contains("[FAIL] TARGET_SEMANTICS"));
        assertTrue(v.out.contains("不得用结构定位顶替语义定位"));
    }

    @Test
    public void critical_requires_two_distinct_approvers() throws IOException {
        String id = proposeAndValidate("alice");

        Result single = exec("approve", "--lib", lib(), "--id", id,
                "--approver", "alice", "--risk", "CRITICAL", "--origin", ORIGIN,
                "--app-version", "2026.11.3");
        assertEquals("CRITICAL 一名审批人不够", CandidateCli.EXIT_REJECTED, single.code);
        assertTrue(single.err.contains("APPROVAL_COUNT"));
        assertTrue("提出者不能自批资金类定位器", single.err.contains("SEPARATION_OF_DUTIES"));

        Result sameAgain = exec("approve", "--lib", lib(), "--id", id,
                "--approver", "bob,bob", "--risk", "CRITICAL", "--origin", ORIGIN,
                "--app-version", "2026.11.3");
        assertEquals("同一人写两遍不算两名审批人", CandidateCli.EXIT_REJECTED, sameAgain.code);
        assertTrue(sameAgain.err.contains("APPROVAL_COUNT"));

        exec("approve", "--lib", lib(), "--id", id,
                "--approver", "bob,carol", "--risk", "CRITICAL", "--origin", ORIGIN,
                "--app-version", "2026.11.3").assertOk();

        Result hit = exec("locator", "--lib", lib(), "--target", "login.submit",
                "--app-version", "2026.11.3", "--origin", ORIGIN);
        hit.assertOk();
        assertEquals("role=button;name=Log on", hit.out.trim());
    }

    @Test
    public void plan_digest_and_validity_window_are_enforced() throws IOException {
        String id = proposeAndValidate("alice");

        Result wrongDigest = exec("approve", "--lib", lib(), "--id", id,
                "--approver", "bob", "--risk", "LOW", "--origin", ORIGIN,
                "--app-version", "2026.11.3", "--plan-digest", "deadbeefdeadbeef");
        assertEquals("批的不是眼前这份内容 → 拒绝", CandidateCli.EXIT_REJECTED, wrongDigest.code);
        assertTrue(wrongDigest.err.contains("PLAN_DIGEST"));

        Result tooLong = exec("approve", "--lib", lib(), "--id", id,
                "--approver", "bob", "--risk", "HIGH", "--origin", ORIGIN,
                "--app-version", "2026.11.3", "--valid-days", "365");
        assertEquals("不给关键定位器批一年", CandidateCli.EXIT_REJECTED, tooLong.code);
        assertTrue(tooLong.err.contains("VALIDITY_WINDOW"));

        exec("approve", "--lib", lib(), "--id", id,
                "--approver", "bob", "--risk", "HIGH", "--origin", ORIGIN,
                "--app-version", "2026.11.3", "--valid-days", "30").assertOk();
    }

    @Test
    public void approval_bound_origin_cannot_be_reused_elsewhere() throws IOException {
        String id = proposeAndValidate("alice");
        exec("approve", "--lib", lib(), "--id", id,
                "--approver", "bob", "--risk", "MEDIUM", "--origin", ORIGIN,
                "--app-version", "2026.11.3").assertOk();

        Result otherEnv = exec("locator", "--lib", lib(), "--target", "login.submit",
                "--app-version", "2026.11.3", "--origin", "https://dbb-prod.example.com");
        assertEquals("测试环境审批不得拿去生产", CandidateCli.EXIT_NOT_RESOLVED, otherEnv.code);
        assertTrue(otherEnv.err.contains("origin 不匹配"));
        assertEquals("", otherEnv.out.trim());
    }

    @Test
    public void expired_approval_cannot_be_resolved() throws IOException {
        String id = proposeAndValidate("alice");
        exec("approve", "--lib", lib(), "--id", id,
                "--approver", "bob", "--risk", "LOW", "--origin", ORIGIN,
                "--app-version", "2026.11.3", "--valid-days", "7").assertOk();

        // 把到期时间改成过去：模拟"审批早就过期，但没人重新取证"
        String json = Files.readString(lib, StandardCharsets.UTF_8);
        String patched = json.replaceAll("\"expiresAt\":\\s*\"[^\"]+\"", "\"expiresAt\": \"2020-01-01T00:00:00Z\"");
        assertTrue("测试前提：确实改到了到期时间", patched.contains("2020-01-01"));
        Files.writeString(lib, patched, StandardCharsets.UTF_8);

        Result hit = exec("locator", "--lib", lib(), "--target", "login.submit",
                "--app-version", "2026.11.3", "--origin", ORIGIN);
        assertEquals("过期即不可回注，不自动续期", CandidateCli.EXIT_NOT_RESOLVED, hit.code);
        assertTrue(hit.err.contains("过期"));
    }

    @Test
    public void ambiguous_approvals_fail_closed() throws IOException {
        approveValid("role=button;name=Log on");
        approveValid("role=button;name=Submit");

        Result hit = exec("locator", "--lib", lib(), "--target", "login.submit",
                "--app-version", "2026.11.3", "--origin", ORIGIN);
        assertEquals("两条审批同为唯一命中 → 歧义即失败", CandidateCli.EXIT_NOT_RESOLVED, hit.code);
        assertTrue(hit.err.contains("歧义即失败"));
        assertEquals("", hit.out.trim());
    }

    @Test
    public void retired_candidate_is_never_resolved() throws IOException {
        approveValid("role=button;name=Log on");

        exec("retire", "--lib", lib(), "--id", "cand-0001",
                "--reason", "页面改版", "--by", "dave").assertOk();

        Result hit = exec("locator", "--lib", lib(), "--target", "login.submit",
                "--app-version", "2026.11.3", "--origin", ORIGIN);
        assertEquals(CandidateCli.EXIT_NOT_RESOLVED, hit.code);
        assertTrue(hit.err.contains("RETIRED"));

        Result show = exec("show", "--lib", lib(), "--id", "cand-0001");
        show.assertOk();
        assertTrue("失效必须记录执行人，审计链不能只剩一个 manual", show.out.contains("dave"));
    }

    // ------------------------------------------------------------------
    // 只读视图
    // ------------------------------------------------------------------

    @Test
    public void list_filters_by_state_and_supports_json() throws IOException {
        approveValid("role=button;name=Log on");
        exec("propose", "--lib", lib(), "--target", "login.cancel",
                "--locator", "role=button;name=Cancel").assertOk();

        Result all = exec("list", "--lib", lib());
        all.assertOk();
        assertTrue(all.out.contains("ID"));
        assertTrue(all.out.contains("cand-0001"));
        assertTrue(all.out.contains("cand-0002"));

        Result approvedOnly = exec("list", "--lib", lib(), "--state", "approved");
        approvedOnly.assertOk();
        assertTrue(approvedOnly.out.contains("cand-0001"));
        assertFalse(approvedOnly.out.contains("cand-0002"));

        Result json = exec("list", "--lib", lib(), "--json");
        json.assertOk();
        assertTrue(json.out.contains("\"state\": \"APPROVED\""));
    }

    @Test
    public void show_exposes_audit_trail_with_history() throws IOException {
        approveValid("role=button;name=Log on");

        Result json = exec("show", "--lib", lib(), "--id", "cand-0001", "--json");
        json.assertOk();
        assertTrue(json.out.contains("\"history\""));
        assertTrue(json.out.contains("VALIDATED"));
        assertTrue(json.out.contains("\"approvedBy\": \"alice\""));

        Result text = exec("show", "--lib", lib(), "--id", "cand-0001");
        text.assertOk();
        assertTrue(text.out.contains("事件链"));
        assertTrue(text.out.contains("v1"));
        assertTrue(text.out.contains("v3"));
    }

    @Test
    public void audit_report_can_be_written_to_file() throws IOException {
        approveValid("role=button;name=Log on");

        Path report = tmp.getRoot().toPath().resolve("out").resolve("report.md");
        Result r = exec("audit", "--lib", lib(), "--out", report.toString());
        r.assertOk();
        assertTrue(Files.isRegularFile(report));
        String md = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(md.contains("# 候选定位器审计报告"));
        assertTrue(md.contains("| APPROVED | 1 |"));
        assertTrue(md.contains("cand-0001"));
        assertTrue("审计报告必须写明 VALIDATED 不等于生产可用", md.contains("不代表生产可用"));
        assertTrue("审计报告要能看出授权绑定了什么", md.contains("授权绑定与到期"));
        assertTrue(md.contains("有效"));

        Result toStdout = exec("audit", "--lib", lib());
        toStdout.assertOk();
        assertTrue(toStdout.out.contains("# 候选定位器审计报告"));
    }

    // ------------------------------------------------------------------
    // 用法错误
    // ------------------------------------------------------------------

    @Test
    public void usage_errors_are_reported_with_exit_code_one() throws IOException {
        assertEquals(CandidateCli.EXIT_USAGE, exec().code);
        assertEquals(CandidateCli.EXIT_USAGE, exec("no-such-command").code);
        assertEquals(CandidateCli.EXIT_USAGE, exec("propose", "--lib", lib()).code);
        assertEquals(CandidateCli.EXIT_USAGE,
                exec("propose", "--lib", lib(), "--target", "t", "--locator", "不存在的定位符").code);
        assertEquals(CandidateCli.EXIT_USAGE,
                exec("locator", "--lib", lib(), "--target", "t").code);
        assertEquals(CandidateCli.EXIT_USAGE,
                exec("propose", "--lib", lib(), "--target", "t", "--locator", "role=button", "多余参数").code);
        assertEquals(CandidateCli.EXIT_USAGE, exec("show", "--lib", lib(), "--id", "cand-9999").code);
        assertEquals(CandidateCli.EXIT_USAGE,
                exec("list", "--lib", lib(), "--state", "NOT_A_STATE").code);
    }

    @Test
    public void help_lists_every_command_and_exit_codes() {
        Result h = exec("help");
        h.assertOk();
        for (String cmd : new String[]{"propose", "validate", "approve", "reject", "retire",
                "list", "show", "locator", "audit"}) {
            assertTrue("help 应列出命令 " + cmd, h.out.contains(cmd));
        }
        assertTrue(h.out.contains("退出码"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 提出 + 用合格证据验证到 VALIDATED（不审批），返回候选 id。 */
    private String proposeAndValidate(String proposedBy) {
        Result p = exec("propose", "--lib", lib(), "--target", "login.submit",
                "--locator", "role=button;name=Log on", "--expected-role", "button", "--by", proposedBy);
        p.assertOk();
        String id = p.out.substring(p.out.indexOf("cand-"), p.out.indexOf("cand-") + 9);

        exec("validate", "--lib", lib(), "--id", id,
                "--matches", "1", "--visible", "true", "--enabled", "true",
                "--text-matched", "true", "--assertion", "提交后出现成功提示", "--assertion-passed", "true")
                .assertOk();
        return id;
    }

    /** 提出 + 用合格证据验证 + 审批，返回候选 id。 */
    private String approveValid(String locator) {
        Result p = exec("propose", "--lib", lib(), "--target", "login.submit",
                "--locator", locator, "--expected-role", "button");
        p.assertOk();
        String id = p.out.substring(p.out.indexOf("cand-"), p.out.indexOf("cand-") + 9);

        exec("validate", "--lib", lib(), "--id", id,
                "--matches", "1", "--visible", "true", "--enabled", "true",
                "--text-matched", "true", "--assertion", "提交后出现成功提示", "--assertion-passed", "true")
                .assertOk();
        exec("approve", "--lib", lib(), "--id", id,
                "--approver", "alice", "--risk", "LOW", "--origin", ORIGIN,
                "--app-version", "2026.11.3").assertOk();
        return id;
    }

    private Result exec(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream po = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream pe = new PrintStream(err, true, StandardCharsets.UTF_8);
        int code = CandidateCli.run(args, po, pe);
        po.flush();
        pe.flush();
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private String lib() {
        return lib.toString();
    }

    private static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        Result assertOk() {
            assertEquals("期望成功，实际退出码 " + code + "\nstdout=" + out + "\nstderr=" + err,
                    CandidateCli.EXIT_OK, code);
            return this;
        }
    }
}

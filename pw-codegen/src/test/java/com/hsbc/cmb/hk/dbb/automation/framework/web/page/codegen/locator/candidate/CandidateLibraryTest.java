package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link CandidateLibrary} 契约测试：<b>验证通过不自动升级</b>、审批必须显式、版本不匹配即失败关闭、
 * 歧义失败关闭、失效不可回退、审计链可持久化。
 */
public class CandidateLibraryTest {

    private static final Instant T0 = Instant.parse("2026-11-03T02:00:00Z");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final CandidateLibrary library = new CandidateLibrary(new CandidateValidator(), clock);

    private static LocatorCandidate candidate(String target, String name) {
        return LocatorCandidate.of(target, LocatorSpec.role("button", name))
                .withExpectedRole("button")
                .withSource("recorder")
                .withEvidenceRef("session#7")
                .withProposedBy("recorder");
    }

    private static CandidateEvidence goodEvidence() {
        return CandidateEvidence.builder()
                .matches(1).visible(true).enabled(true).textMatched(true)
                .businessAssertion("提交后出现成功提示").assertionPassed(true)
                .detail("trace#42")
                .build();
    }

    private CandidateRecord validatedCandidate(String target, String name) {
        CandidateRecord r = library.propose(candidate(target, name));
        library.submitForValidation(r.id(), goodEvidence());
        return r;
    }

    @Test
    public void propose_starts_as_proposed_with_version_one() {
        CandidateRecord r = library.propose(candidate("login.submit", "Submit"));

        assertEquals(CandidateState.PROPOSED, r.state());
        assertEquals(1, r.version());
        assertEquals(1, r.history().size());
        assertEquals("recorder", r.history().get(0).actor());
        assertEquals(T0, r.history().get(0).at());
        assertEquals("cand-0001", r.id());
    }

    @Test
    public void validation_pass_only_yields_validated_not_production() {
        CandidateRecord r = validatedCandidate("login.submit", "Submit");

        assertEquals(CandidateState.VALIDATED, r.state());
        assertTrue(r.validation().passed());
        assertFalse("验证通过不等于生产可用", r.approved());
        assertTrue("未审批不得取出生产定位器",
                library.productionLocator("login.submit", "APP-1.0").isEmpty());
    }

    @Test
    public void approve_requires_explicit_approver_and_app_version() {
        CandidateRecord r = validatedCandidate("login.submit", "Submit");

        assertThrows(() -> library.approve(r.id(), "  ", "APP-1.0"), "approver");
        assertThrows(() -> library.approve(r.id(), "alice", ""), "appVersion");
        assertEquals("审批失败不得改状态", CandidateState.VALIDATED, r.state());
    }

    @Test
    public void unvalidated_candidate_cannot_be_approved() {
        CandidateRecord r = library.propose(candidate("login.submit", "Submit"));

        try {
            library.approve(r.id(), "alice", "APP-1.0");
            fail("未验证候选不得审批");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("验证通过不会自动升级"));
        }
        assertEquals(CandidateState.PROPOSED, r.state());
    }

    @Test
    public void approved_candidate_is_returned_only_for_matching_app_version() {
        CandidateRecord r = validatedCandidate("login.submit", "Submit");
        library.approve(r.id(), "alice", "APP-1.0");

        Optional<CandidateRecord> hit = library.productionLocator("login.submit", "APP-1.0");
        assertTrue(hit.isPresent());
        assertEquals(r.id(), hit.get().id());
        assertEquals("alice", hit.get().approvedBy());

        assertTrue("版本不匹配不回退", library.productionLocator("login.submit", "APP-1.1").isEmpty());
        assertTrue("目标不匹配不返回", library.productionLocator("login.cancel", "APP-1.0").isEmpty());
    }

    @Test
    public void failed_validation_rejects_and_blocks_approval() {
        CandidateRecord r = library.propose(candidate("login.submit", "Submit"));
        library.submitForValidation(r.id(), CandidateEvidence.builder()
                .matches(2).visible(true).enabled(true).textMatched(true)
                .businessAssertion("x").assertionPassed(true).build());

        assertEquals(CandidateState.REJECTED, r.state());
        assertFalse(r.validation().passed());

        try {
            library.approve(r.id(), "alice", "APP-1.0");
            fail("被驳回候选不得审批");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("VALIDATED"));
        }
    }

    @Test
    public void approved_locator_is_injected_only_after_approval() {
        CandidateRecord r = validatedCandidate("login.submit", "Submit");

        assertTrue("未审批时回注出口必须为空", library.approvedLocator("login.submit", "APP-1.0").isEmpty());

        library.approve(r.id(), "alice", "APP-1.0");
        var spec = library.approvedLocator("login.submit", "APP-1.0").orElseThrow();
        assertEquals("role=button;name=Submit", spec.canonical());
        assertTrue(library.approvedLocator("login.submit", "APP-9.9").isEmpty());
    }

    @Test
    public void ambiguous_approvals_fail_closed() {
        CandidateRecord a = validatedCandidate("login.submit", "Submit");
        CandidateRecord b = validatedCandidate("login.submit", " Submit ");
        library.approve(a.id(), "alice", "APP-1.0");
        library.approve(b.id(), "bob", "APP-1.0");

        assertTrue("同一目标同一版本两条审批 → 歧义即失败，不猜",
                library.productionLocator("login.submit", "APP-1.0").isEmpty());
    }

    @Test
    public void retired_candidate_never_comes_back() {
        CandidateRecord r = validatedCandidate("login.submit", "Submit");
        library.approve(r.id(), "alice", "APP-1.0");
        library.retire(r.id(), "登录页改版，按钮改为 a 标签");

        assertEquals(CandidateState.RETIRED, r.state());
        assertTrue(library.productionLocator("login.submit", "APP-1.0").isEmpty());
        CandidateRecord.Event last = r.history().get(r.history().size() - 1);
        assertEquals(CandidateState.RETIRED, last.state());
        assertTrue(last.detail().contains("登录页改版"));

        try {
            library.retire(r.id(), "again");
            fail("已失效候选不可重复失效");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("已失效"));
        }
    }

    @Test
    public void save_load_round_trip_preserves_state_approval_and_history() throws Exception {
        CandidateRecord r = validatedCandidate("login.submit", "Submit");
        library.approve(r.id(), "alice", "APP-1.0");
        Path file = tmp.getRoot().toPath().resolve("candidates/candidates.json");

        library.save(file);
        CandidateLibrary reloaded = CandidateLibrary.load(file, clock);

        assertEquals(1, reloaded.size());
        CandidateRecord back = reloaded.get(r.id());
        assertEquals(CandidateState.APPROVED, back.state());
        assertEquals("alice", back.approvedBy());
        assertEquals("APP-1.0", back.approvalAppVersion());
        assertEquals(r.version(), back.version());
        assertEquals(r.history().size(), back.history().size());
        assertEquals("role=button;name=Submit", back.candidate().canonical());
        assertEquals("button", back.candidate().expectedRole());
        assertTrue(back.validation().passed());
        assertEquals(1, back.evidence().matches());
        assertTrue("落盘后仍能取出生产定位器",
                reloaded.productionLocator("login.submit", "APP-1.0").isPresent());
    }

    @Test
    public void missing_file_loads_empty_library() throws Exception {
        CandidateLibrary empty = CandidateLibrary.load(tmp.getRoot().toPath().resolve("nope.json"), clock);
        assertEquals(0, empty.size());
    }

    @Test
    public void every_state_change_bumps_version() {
        CandidateRecord r = library.propose(candidate("login.submit", "Submit"));
        library.submitForValidation(r.id(), goodEvidence());
        library.approve(r.id(), "alice", "APP-1.0");

        assertEquals(3, r.version());
        assertEquals(3, r.history().size());
        assertEquals(CandidateState.PROPOSED, r.history().get(0).state());
        assertEquals(CandidateState.VALIDATED, r.history().get(1).state());
        assertEquals(CandidateState.APPROVED, r.history().get(2).state());
    }

    private static void assertThrows(Runnable action, String expectedFragment) {
        try {
            action.run();
            fail("应当拒绝：" + expectedFragment);
        } catch (IllegalArgumentException e) {
            assertTrue("异常信息应包含 [" + expectedFragment + "]，实际: " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        }
    }
}

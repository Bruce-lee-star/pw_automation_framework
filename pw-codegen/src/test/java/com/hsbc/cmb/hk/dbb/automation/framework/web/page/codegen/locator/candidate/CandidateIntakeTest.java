package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 一键取证的端到端行为：通过 → 验证；看不清 → 保持可重跑；缺断言 → 不落盘。
 */
public class CandidateIntakeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path lib() {
        return tmp.getRoot().toPath().resolve("locators").resolve("candidates.json");
    }

    private static LocatorCandidate submit() {
        return LocatorCandidate.of("login.submit", LocatorSpec.role("button", "Log on"))
                .withExpectedRole("button")
                .withSource("recorder")
                .withProposedBy("alice");
    }

    private static EvidenceProbe probeReturning(StructuralObservation obs) {
        return candidate -> obs;
    }

    private static StructuralObservation good() {
        return new StructuralObservation(1, true, true, "Log on", "Log on", "tag=button");
    }

    @Test
    public void intake_validates_and_persists() throws Exception {
        CandidateIntake.IntakeResult r = CandidateIntake.run(
                lib(), submit(), probeReturning(good()), "点击后出现欢迎页标题", true, "trace-1");

        assertTrue(r.validated());
        assertEquals(CandidateState.VALIDATED, r.state());
        assertNotNull(r.evidence());
        assertTrue("证据要落成可审计的文字", r.evidence().detail().contains("trace-1"));
        assertTrue(Files.isRegularFile(lib()));

        CandidateLibrary reloaded = CandidateLibrary.load(lib());
        assertEquals(1, reloaded.size());
        assertEquals(CandidateState.VALIDATED, reloaded.get(r.candidateId()).state());
        assertTrue("验证通过只是 VALIDATED，不能直接当生产定位器",
                reloaded.productionLocator("login.submit", "2026.11.3").isEmpty());
    }

    @Test
    public void ambiguous_hit_is_rejected() throws Exception {
        CandidateIntake.IntakeResult r = CandidateIntake.run(
                lib(), submit(),
                probeReturning(new StructuralObservation(2, false, false, "Log on", null,
                        "命中 2 个元素，严格模式下无法判定单一目标的状态")),
                "点击后出现欢迎页标题", true, null);

        assertEquals(CandidateState.REJECTED, r.state());
        assertFalse(r.validated());
        assertFalse(r.retryable());
        assertTrue(r.validation().failures().stream()
                .anyMatch(f -> CandidateValidator.RULE_UNIQUENESS.equals(f.ruleId())));
    }

    @Test
    public void unobserved_probe_keeps_candidate_retryable() throws Exception {
        CandidateIntake.IntakeResult first = CandidateIntake.run(
                lib(), submit(), probeReturning(StructuralObservation.unobserved("页面未加载")),
                "点击后出现欢迎页标题", true, null);

        assertEquals(CandidateState.PROPOSED, first.state());
        assertTrue("探针失败不等于候选错误，必须可重跑", first.retryable());
        assertTrue(first.note().contains("页面未加载"));

        // 重跑一次即可救回来：REJECTED 是不可再验证的终态，若这里被烧掉就永久废了
        CandidateIntake.IntakeResult second = CandidateIntake.run(
                lib(), submit(), probeReturning(good()), "点击后出现欢迎页标题", true, null);

        assertTrue(second.validated());
        assertFalse("同一目标两条候选并存时不得自动挑一条",
                CandidateLibrary.load(lib()).approvedLocator("login.submit", "2026.11.3").isPresent());
    }

    @Test
    public void missing_business_assertion_is_rejected_before_disk() throws Exception {
        try {
            CandidateIntake.run(lib(), submit(), probeReturning(good()), "  ", true, null);
            fail("空业务断言应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("业务断言"));
        }
        assertFalse("校验失败不得留下半截台账", Files.exists(lib()));
    }

    @Test
    public void assertion_must_be_explicitly_run() throws Exception {
        try {
            CandidateIntake.run(lib(), submit(), probeReturning(good()), "点击后出现欢迎页标题", null, null);
            fail("断言未执行应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("未执行"));
        }
        assertFalse(Files.exists(lib()));
    }

    @Test
    public void missing_probe_is_rejected() throws Exception {
        try {
            CandidateIntake.run(lib(), submit(), null, "点击后出现欢迎页标题", true, null);
            fail("无探针不得验证");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("探针"));
        }
        assertFalse(Files.exists(lib()));
    }

    @Test
    public void probe_exception_leaves_no_record_behind() throws Exception {
        EvidenceProbe exploding = candidate -> {
            throw new IllegalStateException("browser crashed");
        };

        try {
            CandidateIntake.run(lib(), submit(), exploding, "点击后出现欢迎页标题", true, null);
            fail("探针异常应向上抛出");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("browser crashed"));
        }
        assertFalse("异常路径不得写出候选", Files.exists(lib()));
    }

    @Test
    public void intake_acquires_lock_so_concurrent_write_fails_fast() throws Exception {
        Files.createDirectories(lib().getParent());
        try (LibraryLock ignored = LibraryLock.acquire(lib())) {
            try {
                CandidateIntake.run(lib(), submit(), probeReturning(good()), "点击后出现欢迎页标题", true, null);
                fail("已有写锁时不得强行写入");
            } catch (RuntimeException e) {
                assertTrue("冲突要与 I/O 错误区分开",
                        e instanceof LibraryConflictException || e.getMessage().contains("正在修改"));
            }
        }
    }

    @Test
    public void library_file_is_created_in_missing_parent_directory() throws Exception {
        Path deep = tmp.getRoot().toPath().resolve("a").resolve("b").resolve("candidates.json");

        CandidateIntake.IntakeResult r = CandidateIntake.run(
                deep, submit(), probeReturning(good()), "点击后出现欢迎页标题", true, null);

        assertTrue(r.validated());
        assertTrue(Files.isRegularFile(deep));
    }

    @Test
    public void intake_reports_failed_rules_for_diagnosis() throws IOException {
        CandidateIntake.IntakeResult r = CandidateIntake.run(
                lib(), submit(),
                probeReturning(new StructuralObservation(1, false, false, "Log on", "Log in", "tag=button")),
                "点击后出现欢迎页标题", true, null);

        assertEquals(CandidateState.REJECTED, r.state());
        assertTrue(r.validation().summary().contains(CandidateValidator.RULE_TARGET_SEMANTICS));
        assertTrue(r.validation().summary().contains(CandidateValidator.RULE_VISIBILITY));
        assertTrue(r.validation().summary().contains(CandidateValidator.RULE_ACTIONABILITY));
    }
}

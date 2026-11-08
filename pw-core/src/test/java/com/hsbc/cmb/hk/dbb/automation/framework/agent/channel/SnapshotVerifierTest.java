package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SnapshotVerifierTest {

    private static final String SUBMIT = "button[name=Submit]";
    private static final String USER = "textbox[name=username]";

    private static Snapshot sample() {
        Snapshot.ElementState submit = new Snapshot.ElementState(SUBMIT, "button", "Submit", true, true, "Submit");
        Snapshot.ElementState user = new Snapshot.ElementState(USER, "textbox", "username", true, true, "");
        return new Snapshot("page-1", List.of(submit, user));
    }

    @Test
    public void allMatchingExpectationsPass() {
        Snapshot s = sample();
        List<SnapshotExpectation> exps = List.of(
                SnapshotExpectation.forRef(SUBMIT).role("button").visible(true).enabled(true).text("Submit").build(),
                SnapshotExpectation.forRef(USER).role("textbox").build());
        SnapshotVerificationResult r = SnapshotVerifier.verify(s, exps);
        assertTrue(r.allPassed());
        assertEquals(2, r.checked());
        assertEquals(2, r.passed());
        assertTrue(r.failures().isEmpty());
    }

    @Test
    public void missingElementFails() {
        Snapshot s = sample();
        SnapshotVerificationResult r = SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef("link[name=missing]").build()));
        assertFalse(r.allPassed());
        assertEquals(1, r.failures().size());
        assertEquals(0, r.passed());
    }

    @Test
    public void fieldMismatchFailsWithExpectedActual() {
        Snapshot s = sample();
        SnapshotVerificationResult r = SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef(SUBMIT).text("Wrong").build()));
        assertFalse(r.allPassed());
        AssertionFailure f = r.failures().get(0);
        assertEquals("Wrong", f.getExpected());
        assertEquals("Submit", f.getActual());
    }

    @Test
    public void existsFalseNegatesPresence() {
        Snapshot s = sample();
        // 期望 SUBMIT 不存在 → 实际存在 → 失败
        assertFalse(SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef(SUBMIT).exists(false).build())).allPassed());
        // 期望 missing 不存在 → 实际不存在 → 通过
        assertTrue(SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef("link[x]").exists(false).build())).allPassed());
    }

    @Test
    public void textRegexMatches() {
        Snapshot s = sample();
        assertTrue(SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef(SUBMIT).textMatches("^Sub.*").build())).allPassed());
        assertFalse(SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef(SUBMIT).textMatches("^Nope.*").build())).allPassed());
    }

    @Test
    public void textAndTextMatchesMutuallyExclusive() {
        try {
            SnapshotExpectation.forRef(SUBMIT).text("a").textMatches("b").build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // text 与 textMatches 互斥
        }
    }

    @Test
    public void nullArgsRejected() {
        org.junit.Assert.assertThrows(NullPointerException.class,
                () -> SnapshotVerifier.verify((Snapshot) null, List.of()));
        org.junit.Assert.assertThrows(NullPointerException.class,
                () -> SnapshotVerifier.verify(sample(), null));
    }

    @Test
    public void verifyViaCommandResultUsesSnapshot() {
        Snapshot s = sample();
        CommandResult result = CommandResult.ok(s);
        assertTrue(SnapshotVerifier.verify(result,
                List.of(SnapshotExpectation.forRef(SUBMIT).role("button").build())).allPassed());
    }

    @Test
    public void partialMatchReportsOnlyFailingFields() {
        Snapshot s = sample();
        // role 对、text 错 → 仅 text 落败
        SnapshotVerificationResult r = SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef(SUBMIT).role("button").text("Nope").build()));
        assertFalse(r.allPassed());
        assertEquals(1, r.failures().size());
        assertEquals("text mismatch @" + SUBMIT, r.failures().get(0).getMessage());
        assertEquals("Nope", r.failures().get(0).getExpected());
    }
}

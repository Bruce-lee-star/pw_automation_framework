package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;

import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.domain.stacktrace.FailureCause;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SerenityOutcomeVerifierTest {

    @Test
    public void successOutcomeDoesNotThrow() {
        TestOutcome outcome = mock(TestOutcome.class);
        when(outcome.getResult()).thenReturn(TestResult.SUCCESS);
        SerenityOutcomeVerifier.verifyOutcome(outcome);
    }

    @Test
    public void nullOutcomeDoesNotThrow() {
        SerenityOutcomeVerifier.verifyOutcome(null);
    }

    @Test
    public void failureOutcomeThrowsWithOriginalCause() {
        TestOutcome outcome = mock(TestOutcome.class);
        when(outcome.getResult()).thenReturn(TestResult.FAILURE);
        FailureCause failureCause = mock(FailureCause.class);
        RuntimeException original = new RuntimeException("boom");
        when(failureCause.getOriginalCause()).thenReturn(original);
        when(outcome.getTestFailureCause()).thenReturn(failureCause);

        try {
            SerenityOutcomeVerifier.verifyOutcome(outcome);
            fail("expected SerenityOutcomeMismatchException");
        } catch (SerenityOutcomeMismatchException ex) {
            assertSame(original, ex.getCause());
        }
    }

    @Test
    public void errorOutcomeThrows() {
        assertThrowsOnResult(TestResult.ERROR);
    }

    @Test
    public void compromisedOutcomeThrows() {
        assertThrowsOnResult(TestResult.COMPROMISED);
    }

    @Test
    public void failureWithoutExtractableOriginalCauseStillThrows() {
        TestOutcome outcome = mock(TestOutcome.class);
        when(outcome.getResult()).thenReturn(TestResult.FAILURE);
        FailureCause failureCause = mock(FailureCause.class);
        when(failureCause.getOriginalCause()).thenReturn(null);
        when(outcome.getTestFailureCause()).thenReturn(failureCause);

        try {
            SerenityOutcomeVerifier.verifyOutcome(outcome);
            fail("expected SerenityOutcomeMismatchException");
        } catch (SerenityOutcomeMismatchException ex) {
            // 原始异常不可提取时仍抛（cause 可为 null）
        }
    }

    private void assertThrowsOnResult(TestResult result) {
        TestOutcome outcome = mock(TestOutcome.class);
        when(outcome.getResult()).thenReturn(result);
        try {
            SerenityOutcomeVerifier.verifyOutcome(outcome);
            fail("expected SerenityOutcomeMismatchException for " + result);
        } catch (SerenityOutcomeMismatchException ex) {
            // 预期
        }
    }
}

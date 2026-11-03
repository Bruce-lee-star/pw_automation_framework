package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ParamConstraintTest {

    @Test
    public void rangeAllowsWithinBounds() {
        ParamConstraint c = ParamConstraint.inRange(1, 100);
        assertTrue(c.allows("1"));
        assertTrue(c.allows("50"));
        assertTrue(c.allows("100"));
        assertTrue(c.isRange());
        assertFalse(c.isRegex());
    }

    @Test
    public void rangeRejectsOutsideAndNonNumeric() {
        ParamConstraint c = ParamConstraint.inRange(1, 100);
        assertFalse(c.allows("0"));
        assertFalse(c.allows("101"));
        assertFalse(c.allows("abc"));
        assertFalse(c.allows(""));
        assertFalse(c.allows(null));
    }

    @Test
    public void rangeRejectsMinGreaterThanMax() {
        assertThrowsIllegal(() -> ParamConstraint.inRange(10, 1));
    }

    @Test
    public void rangeCanonical() {
        assertEquals("range=1..100", ParamConstraint.inRange(1, 100).canonical());
    }

    @Test
    public void allowAndRegexUnchanged() {
        assertTrue(ParamConstraint.allowedValues("a", "b").allows("a"));
        assertTrue(ParamConstraint.matchingRegex("[0-9]+").allows("42"));
        assertFalse(ParamConstraint.matchingRegex("[0-9]+").allows("4a"));
    }

    private static void assertThrowsIllegal(ThrowingRunnable r) {
        try {
            r.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 期望的 fail-closed 拒绝
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}

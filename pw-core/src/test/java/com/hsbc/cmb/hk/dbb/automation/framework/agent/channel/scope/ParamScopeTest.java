package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ParamScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void parsesAllowList() {
        ParamScope s = ParamScope.fromJson(node("{\"value\":{\"allow\":[\"alice\",\"bob\"]}}"));
        ParamConstraint c = s.constraintFor("value").orElseThrow();
        assertTrue(c.allows("alice"));
        assertTrue(c.allows("bob"));
        assertFalse(c.allows("carol"));
    }

    @Test
    public void parsesRegex() {
        ParamScope s = ParamScope.fromJson(node("{\"value\":{\"regex\":\"^[0-9]+$\"}}"));
        ParamConstraint c = s.constraintFor("value").orElseThrow();
        assertTrue(c.isRegex());
        assertTrue(c.allows("12345"));
        assertFalse(c.allows("12a"));
    }

    @Test
    public void rejectsBothAllowAndRegex() {
        assertThrowsIllegal(() -> ParamScope.fromJson(node("{\"value\":{\"allow\":[\"a\"],\"regex\":\"x\"}}")));
    }

    @Test
    public void rejectsNeither() {
        assertThrowsIllegal(() -> ParamScope.fromJson(node("{\"value\":{}}")));
    }

    @Test
    public void rejectsNonObjectScope() {
        assertThrowsIllegal(() -> ParamScope.fromJson(node("[\"value\"]")));
    }

    @Test
    public void rejectsAllowNonArray() {
        assertThrowsIllegal(() -> ParamScope.fromJson(node("{\"value\":{\"allow\":\"alice\"}}")));
    }

    @Test
    public void emptyWhenAbsent() {
        assertTrue(ParamScope.fromJson(null).isEmpty());
        assertTrue(ParamScope.fromJson(node("null")).isEmpty());
    }

    @Test
    public void canonicalIsSortedAndStable() {
        ParamScope a = ParamScope.fromJson(node("{\"b\":{\"allow\":[\"x\"]},\"a\":{\"regex\":\"y\"}}"));
        ParamScope b = ParamScope.fromJson(node("{\"a\":{\"regex\":\"y\"},\"b\":{\"allow\":[\"x\"]}}"));
        assertEquals(a.canonical(), b.canonical());
        assertEquals("a{regex=y}b{allow=x}", a.canonical());
    }

    @Test
    public void parsesRange() {
        ParamScope s = ParamScope.fromJson(node("{\"count\":{\"min\":1,\"max\":10}}"));
        assertTrue(s.constraintFor("count").orElseThrow().isRange());
        assertTrue(s.constraintFor("count").get().allows("5"));
        assertFalse(s.constraintFor("count").get().allows("11"));
    }

    @Test
    public void rejectsRangeMissingBound() {
        assertThrowsIllegal(() -> ParamScope.fromJson(node("{\"count\":{\"min\":1}}")));
    }

    @Test
    public void rejectsConflictingForms() {
        assertThrowsIllegal(() -> ParamScope.fromJson(node("{\"v\":{\"allow\":[\"a\"],\"regex\":\"b\"}}")));
    }

    @Test
    public void composeWithOverridesBase() {
        ParamScope base = ParamScope.fromJson(node("{\"value\":{\"allow\":[\"a\",\"b\"]}}"));
        ParamScope step = ParamScope.fromJson(node("{\"value\":{\"allow\":[\"x\"]},\"opt\":{\"allow\":[\"y\"]}}"));
        ParamScope merged = step.composeWith(base);
        // step 覆盖 base 的 value
        assertTrue(merged.constraintFor("value").get().allows("x"));
        assertFalse(merged.constraintFor("value").get().allows("a"));
        // base 的 opt 不存在 → 沿用 step 的 opt
        assertTrue(merged.constraintFor("opt").get().allows("y"));
    }

    @Test
    public void composeWithEmptyShortCircuits() {
        ParamScope base = ParamScope.fromJson(node("{\"value\":{\"allow\":[\"a\"]}}"));
        assertEquals(base, base.composeWith(ParamScope.EMPTY));
        assertEquals(base, ParamScope.EMPTY.composeWith(base));
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

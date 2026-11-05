package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 录制骨架输入协议 fail-closed 测试（设计 §9 P1 验收：非法 schema / 未知字段 / 越界均拒）。
 */
public class RecordedSkeletonTest {

    private static final String VALID = "{"
            + "\"schemaVersion\":1,\"origin\":\"codegen\",\"pageRef\":\"https://example.com/login\","
            + "\"steps\":["
            + " {\"action\":\"NAVIGATE\",\"params\":{\"url\":\"https://example.com/login\"}},"
            + " {\"action\":\"FILL\",\"target\":\"role=textbox;name=username\",\"params\":{\"value\":\"alice\"}}"
            + "]}";

    @Test
    public void parsesValidSkeleton() {
        RecordedSkeleton s = RecordedSkeleton.parse(VALID);
        assertEquals(1, s.schemaVersion());
        assertEquals("codegen", s.origin());
        assertEquals(2, s.steps().size());
        assertEquals("NAVIGATE", s.steps().get(0).action());
        assertEquals("alice", s.steps().get(1).params().get("value"));
        assertEquals("skeleton-v1:codegen", s.originId());
    }

    @Test
    public void rejectsUnsupportedSchema() {
        assertReason(IntentTranslationException.Reason.UNSUPPORTED_SCHEMA,
                () -> RecordedSkeleton.parse(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":99")));
    }

    @Test
    public void rejectsNonIntegerSchema() {
        assertReason(IntentTranslationException.Reason.INVALID_INPUT,
                () -> RecordedSkeleton.parse(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")));
    }

    @Test
    public void rejectsUnknownRootField() {
        assertReason(IntentTranslationException.Reason.UNKNOWN_FIELD,
                () -> RecordedSkeleton.parse(VALID.replace("\"steps\":[", "\"note\":\"evil\",\"steps\":[")));
    }

    @Test
    public void rejectsUnknownStepField() {
        assertReason(IntentTranslationException.Reason.UNKNOWN_FIELD,
                () -> RecordedSkeleton.parse(VALID.replace("{\"action\":\"NAVIGATE\"",
                        "{\"action\":\"NAVIGATE\",\"screenshot\":\"base64...\"")));
    }

    @Test
    public void rejectsUnknownParamContainerShape() {
        assertReason(IntentTranslationException.Reason.INVALID_INPUT,
                () -> RecordedSkeleton.parse(VALID.replace("\"params\":{\"url\"", "\"params\":[\"url\"")));
    }

    @Test
    public void rejectsInvalidOrigin() {
        assertReason(IntentTranslationException.Reason.INVALID_INPUT,
                () -> RecordedSkeleton.parse(VALID.replace("\"origin\":\"codegen\"", "\"origin\":\"../evil\"")));
    }

    @Test
    public void rejectsEmptySteps() {
        String json = "{\"schemaVersion\":1,\"origin\":\"codegen\",\"pageRef\":\"p\",\"steps\":[]}";
        assertReason(IntentTranslationException.Reason.BOUNDS_VIOLATION,
                () -> RecordedSkeleton.parse(json));
    }

    @Test
    public void rejectsOversizedSteps() {
        StringBuilder sb = new StringBuilder(
                "{\"schemaVersion\":1,\"origin\":\"codegen\",\"pageRef\":\"p\",\"steps\":[");
        for (int i = 0; i <= RecordedSkeleton.MAX_STEPS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"action\":\"CLICK\",\"target\":\"role=button;name=b\"}");
        }
        sb.append("]}");
        assertReason(IntentTranslationException.Reason.BOUNDS_VIOLATION,
                () -> RecordedSkeleton.parse(sb.toString()));
    }

    @Test
    public void rejectsOversizedField() {
        String huge = "x".repeat(RecordedSkeleton.MAX_FIELD_LENGTH + 1);
        assertReason(IntentTranslationException.Reason.INVALID_INPUT,
                () -> RecordedSkeleton.parse("{\"schemaVersion\":1,\"origin\":\"codegen\","
                        + "\"pageRef\":\"" + huge + "\",\"steps\":[{\"action\":\"CLICK\"}]}"));
    }

    @Test
    public void normalizedIsDeterministicAcrossWhitespaceCaseAndParamOrder() {
        RecordedSkeleton a = new RecordedSkeleton(1, " CodeGen ", " https://example.com/login ",
                List.of(RecordedStep.of(" navigate ", null, "url", " https://example.com/login ")));
        RecordedSkeleton b = new RecordedSkeleton(1, "codegen", "https://example.com/login",
                List.of(new RecordedStep("NAVIGATE", null, Map.of("url", "https://example.com/login"))));
        assertEquals(a.normalized(), b.normalized());
        assertEquals(a.normalized().normalized(), a.normalized());
    }

    @Test
    public void normalizationPreservesSemanticDivergence() {
        RecordedSkeleton a = new RecordedSkeleton(1, "codegen", "p",
                List.of(RecordedStep.of("CLICK", "role=button;name=A")));
        RecordedSkeleton b = new RecordedSkeleton(1, "codegen", "p",
                List.of(RecordedStep.of("CLICK", "role=button;name=B")));
        assertNotEquals(a.normalized(), b.normalized());
    }

    @Test
    public void nullJsonRejected() {
        try {
            RecordedSkeleton.parse(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertTrue(true);
        }
    }

    private static void assertReason(IntentTranslationException.Reason expected, Runnable action) {
        try {
            action.run();
            fail("expected IntentTranslationException with reason " + expected);
        } catch (IntentTranslationException e) {
            assertEquals(expected, e.reason());
        }
    }
}

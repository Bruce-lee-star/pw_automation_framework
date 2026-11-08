package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        org.junit.Assert.assertThrows(NullPointerException.class, () -> RecordedSkeleton.parse(null));
    }

    // ------------------------------------------------------------------
    // JSON 输出（toJson 与 parse 严格对称）
    // ------------------------------------------------------------------

    @Test
    public void toJsonRoundTripsThroughParse() {
        RecordedSkeleton original = new RecordedSkeleton(1, "codegen", "https://example.com/login",
                List.of(RecordedStep.of("NAVIGATE", null, "url", "https://example.com/login"),
                        new RecordedStep("FILL", "role=textbox;name=username", Map.of("value", "alice"))));

        RecordedSkeleton reparsed = RecordedSkeleton.parse(original.toJson());

        assertEquals(original.schemaVersion(), reparsed.schemaVersion());
        assertEquals(original.origin(), reparsed.origin());
        assertEquals(original.pageRef(), reparsed.pageRef());
        assertEquals(original.steps(), reparsed.steps());
    }

    @Test
    public void toJsonIsDeterministicAndSortsParams() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("alpha", "1");
        ordered.put("beta", "2");
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("beta", "2");
        reversed.put("alpha", "1");
        RecordedSkeleton a = new RecordedSkeleton(1, "codegen", "p",
                List.of(new RecordedStep("FILL", "role=textbox;name=u", ordered)));
        RecordedSkeleton b = new RecordedSkeleton(1, "codegen", "p",
                List.of(new RecordedStep("FILL", "role=textbox;name=u", reversed)));

        // 参数插入顺序不同 ⇒ 同一 JSON（逐字节可比对，可安全入 git）
        assertEquals(a.toJson(), b.toJson());
        assertTrue(a.toJson().indexOf("\"alpha\"") < a.toJson().indexOf("\"beta\""));
    }

    @Test
    public void toJsonOmitsNullTarget() {
        RecordedSkeleton skeleton = new RecordedSkeleton(1, "codegen", "p",
                List.of(RecordedStep.of("NAVIGATE", null, "url", "p")));

        String json = skeleton.toJson();

        assertFalse("target=null 时不应输出该字段: " + json, json.contains("\"target\""));
        assertEquals(1, RecordedSkeleton.parse(json).steps().size());
    }

    @Test
    public void toJsonRejectsBlankAction() {
        RecordedSkeleton skeleton = new RecordedSkeleton(1, "codegen", "p",
                List.of(RecordedStep.of("  ", null)));
        assertReason(IntentTranslationException.Reason.INVALID_INPUT, skeleton::toJson);
    }

    @Test
    public void toJsonRejectsOversizedParam() {
        String huge = "x".repeat(RecordedSkeleton.MAX_FIELD_LENGTH + 1);
        RecordedSkeleton skeleton = new RecordedSkeleton(1, "codegen", "p",
                List.of(RecordedStep.of("FILL", "role=textbox;name=u", "value", huge)));
        assertReason(IntentTranslationException.Reason.INVALID_INPUT, skeleton::toJson);
    }

    @Test
    public void toJsonEmitsOnlyWhitelistedFields() throws Exception {
        RecordedSkeleton skeleton = new RecordedSkeleton(1, "codegen", "p",
                List.of(new RecordedStep("FILL", "role=textbox;name=u", Map.of("value", "alice"))));

        JsonNode root = new ObjectMapper().readTree(skeleton.toJson());
        Set<String> rootFields = new TreeSet<>();
        root.fieldNames().forEachRemaining(rootFields::add);
        assertEquals(Set.of("schemaVersion", "origin", "pageRef", "steps"), rootFields);

        Set<String> stepFields = new TreeSet<>();
        root.get("steps").get(0).fieldNames().forEachRemaining(stepFields::add);
        assertEquals(Set.of("action", "target", "params"), stepFields);
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

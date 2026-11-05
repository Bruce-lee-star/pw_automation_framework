package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 确定性规则引擎测试（设计 §9 P1 验收：非法 schema / 越权 Action / 未知字段 / 不批准 origin 全拒，
 * 相同规范化输入产出等价计划）。
 */
public class RuleBasedIntentTranslatorTest {

    private static final String PAGE = "https://example.com/login";

    private final RuleBasedIntentTranslator translator =
            RuleBasedIntentTranslator.approving("codegen");

    private static RecordedSkeleton skeleton(String origin, RecordedStep... steps) {
        return new RecordedSkeleton(1, origin, PAGE, List.of(steps));
    }

    private static RecordedStep navigate() {
        return RecordedStep.of("NAVIGATE", null, "url", PAGE);
    }

    @Test
    public void translatesRecordedSkeletonToExecutablePlan() {
        StepPlan plan = translator.translate(skeleton("codegen",
                navigate(),
                RecordedStep.of("FILL", "role=textbox;name=username", "value", "alice"),
                RecordedStep.of("CLICK", "role=button;name=Submit"),
                RecordedStep.of("ASSERT_VISIBLE", "role=heading;name=Welcome")));

        assertEquals(1, plan.schemaVersion());
        assertTrue(plan.planId().startsWith("intent-"));
        assertEquals(4, plan.steps().size());
        assertEquals(Command.ActionType.NAVIGATE, plan.steps().get(0).action());
        assertEquals(PAGE, plan.steps().get(0).params().get("url"));
        assertEquals(Command.ActionType.FILL, plan.steps().get(1).action());
        assertEquals("role=textbox;name=username", plan.steps().get(1).targetRef());
        assertEquals("alice", plan.steps().get(1).params().get("value"));
        assertEquals(Command.ActionType.CLICK, plan.steps().get(2).action());
        assertNotNull("planDigest must be computable", plan.planDigest());
        assertTrue("scope must declare url for side-effecting NAVIGATE",
                plan.scope().constraintFor("url").isPresent());
        assertTrue("FILL value must declare a step-level value domain",
                plan.scopeForStep(1).constraintFor("value").isPresent());
        // 生成即校验：产出必须已通过策略门禁
        ParamScopePolicy.validatePlan(plan);
        assertTrue(IntentPlanValidator.inspect(plan).accepted());
    }

    @Test
    public void translationIsDeterministicForSameNormalizedInput() {
        RecordedSkeleton a = skeleton("codegen",
                RecordedStep.of(" navigate ", null, "url", " " + PAGE + " "),
                RecordedStep.of(" fill ", " role=textbox;name=username ", "value", " alice "));
        RecordedSkeleton b = skeleton("codegen", navigate(),
                RecordedStep.of("FILL", "role=textbox;name=username", "value", "alice"));

        StepPlan pa = translator.translate(a);
        StepPlan pb = translator.translate(b);
        assertEquals(pa.planDigest(), pb.planDigest());
        assertEquals(pa.planId(), pb.planId());
        assertEquals(translator.translate(a).planDigest(), pa.planDigest());
    }

    @Test
    public void rejectsUnapprovedOrigin() {
        assertReason(IntentTranslationException.Reason.UNAPPROVED_ORIGIN,
                () -> translator.translate(skeleton("unknown-recorder", navigate())));
    }

    @Test
    public void emptyApprovalSetIsFailClosed() {
        RuleBasedIntentTranslator none = new RuleBasedIntentTranslator(Set.of());
        assertReason(IntentTranslationException.Reason.UNAPPROVED_ORIGIN,
                () -> none.translate(skeleton("codegen", navigate())));
    }

    @Test
    public void rejectsUnknownActionType() {
        assertReason(IntentTranslationException.Reason.UNKNOWN_ACTION,
                () -> translator.translate(skeleton("codegen",
                        RecordedStep.of("EXECUTE_SCRIPT", null))));
    }

    @Test
    public void rejectsPrivilegedActionFromInput() {
        assertReason(IntentTranslationException.Reason.ACTION_NOT_ALLOWED,
                () -> translator.translate(skeleton("codegen",
                        RecordedStep.of("SWITCH_TO_POPUP", null))));
    }

    @Test
    public void rejectsUnknownParamKey() {
        assertReason(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                () -> translator.translate(skeleton("codegen",
                        new RecordedStep("FILL", "role=textbox;name=u",
                                Map.of("value", "alice", "executeJs", "alert(1)")))));
    }

    @Test
    public void rejectsMissingRequiredParam() {
        assertReason(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION,
                () -> translator.translate(skeleton("codegen",
                        RecordedStep.of("FILL", "role=textbox;name=u"))));
    }

    @Test
    public void rejectsNavigateToUnapprovedUrl() {
        assertReason(IntentTranslationException.Reason.TARGET_VIOLATION,
                () -> translator.translate(skeleton("codegen",
                        RecordedStep.of("NAVIGATE", null, "url", "https://evil.example.com"))));
    }

    @Test
    public void rejectsNavigateWithTarget() {
        assertReason(IntentTranslationException.Reason.TARGET_VIOLATION,
                () -> translator.translate(skeleton("codegen",
                        RecordedStep.of("NAVIGATE", "role=button;name=x", "url", PAGE))));
    }

    @Test
    public void rejectsMissingTarget() {
        assertReason(IntentTranslationException.Reason.TARGET_VIOLATION,
                () -> translator.translate(skeleton("codegen",
                        RecordedStep.of("CLICK", null))));
    }

    @Test
    public void rejectsInvalidLocatorCanonical() {
        assertReason(IntentTranslationException.Reason.TARGET_VIOLATION,
                () -> translator.translate(skeleton("codegen",
                        RecordedStep.of("CLICK", "not-a-canonical-locator"))));
    }

    @Test
    public void rejectsNullSkeleton() {
        assertReason(IntentTranslationException.Reason.INVALID_INPUT, () -> translator.translate(null));
    }

    @Test
    public void idIsStableAndDescriptive() {
        assertEquals("RuleBasedIntentTranslator", translator.id());
    }

    private static void assertReason(IntentTranslationException.Reason expected, Runnable action) {
        try {
            action.run();
            fail("expected IntentTranslationException with reason " + expected);
        } catch (IntentTranslationException e) {
            assertEquals("unexpected reason: " + e.getMessage(), expected, e.reason());
        }
    }
}

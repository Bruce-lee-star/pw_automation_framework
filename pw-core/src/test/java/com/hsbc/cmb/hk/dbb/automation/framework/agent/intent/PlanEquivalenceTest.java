package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 等价性判定测试（设计 §9 P1：「对同一规范化输入比较录制骨架与模型建议，
 * 不以自然语言输出作为唯一验收真值」）。
 *
 * <p>用两个独立 {@link IntentTranslator} 实现对同一份规范化骨架产出计划，模拟
 * 「确定性录制骨架路径」与「模型建议路径」的对账；等价判据是结构化指纹而非文本。</p>
 */
public class PlanEquivalenceTest {

    private static final String PAGE = "https://example.com/login";

    private static RecordedSkeleton skeleton() {
        return new RecordedSkeleton(1, "codegen", PAGE, List.of(
                RecordedStep.of("NAVIGATE", null, "url", PAGE),
                RecordedStep.of("FILL", "role=textbox;name=username", "value", "alice"),
                RecordedStep.of("CLICK", "role=button;name=Submit"),
                RecordedStep.of("ASSERT_VISIBLE", "role=heading;name=Welcome")));
    }

    /** 模拟「模型建议」路径：独立构造计划，planId 不同但内容刻意保持一致。 */
    private static final class ModelSuggestionTranslator implements IntentTranslator {
        private final boolean hallucinate;

        private ModelSuggestionTranslator(boolean hallucinate) {
            this.hallucinate = hallucinate;
        }

        @Override
        public StepPlan translate(RecordedSkeleton s) {
            List<Command> steps = new ArrayList<>();
            List<ParamScope> scopes = new ArrayList<>();
            for (RecordedStep step : s.steps()) {
                Command.ActionType action = Command.ActionType.valueOf(step.action());
                LocatorSpec target = step.target() == null ? null : LocatorSpec.parse(step.target());
                steps.add(new Command(action, target, Map.copyOf(step.params())));
                scopes.add(ParamScope.forAssetWriteValue(action, step.params().get("value")));
            }
            if (hallucinate) {
                steps.add(Command.click(LocatorSpec.parse("role=button;name=DoesNotExist")));
                scopes.add(ParamScope.EMPTY);
            }
            ParamScope planScope = new ParamScope(Map.of("url", ParamConstraint.allowedValues(s.pageRef())));
            return new StepPlan(1, "llm-suggestion-draft", steps, planScope, scopes);
        }

        @Override
        public String id() {
            return "ModelSuggestionTranslator";
        }
    }

    @Test
    public void sameNormalizedInputProducesEquivalentPlansAcrossTwoPaths() {
        IntentTranslator baseline = RuleBasedIntentTranslator.approving("codegen");
        IntentTranslator suggested = new ModelSuggestionTranslator(false);

        PlanEquivalence.Report report = PlanEquivalence.forInput(skeleton(), baseline, suggested);
        assertTrue("divergences=" + report.divergences(), report.equivalent());
        assertTrue(report.leftValid());
        assertTrue(report.rightValid());
        assertEquals(report.leftDigest(), report.rightDigest());
        assertTrue(report.divergences().isEmpty());
        // planId 不同但内容相同 → 仍等价（digest 刻意排除 planId）
        assertTrue(PlanEquivalence.equivalent(
                baseline.translate(skeleton()), suggested.translate(skeleton())));
    }

    @Test
    public void hallucinatedExtraStepBreaksEquivalenceWithDiagnostics() {
        IntentTranslator baseline = RuleBasedIntentTranslator.approving("codegen");
        IntentTranslator suggested = new ModelSuggestionTranslator(true);

        PlanEquivalence.Report report = PlanEquivalence.forInput(skeleton(), baseline, suggested);
        assertFalse(report.equivalent());
        assertFalse(report.leftDigest().equals(report.rightDigest()));
        assertTrue(report.divergences().stream().anyMatch(d -> d.contains("step count")));
        assertTrue(report.divergences().stream().anyMatch(d -> d.contains("planDigest")));
    }

    @Test
    public void differingParamValueBreaksEquivalenceWithStepLevelDiagnostics() {
        StepPlan left = RuleBasedIntentTranslator.approving("codegen").translate(skeleton());
        RecordedSkeleton changed = new RecordedSkeleton(1, "codegen", PAGE, List.of(
                RecordedStep.of("NAVIGATE", null, "url", PAGE),
                RecordedStep.of("FILL", "role=textbox;name=username", "value", "bob"),
                RecordedStep.of("CLICK", "role=button;name=Submit"),
                RecordedStep.of("ASSERT_VISIBLE", "role=heading;name=Welcome")));
        StepPlan right = RuleBasedIntentTranslator.approving("codegen").translate(changed);

        PlanEquivalence.Report report = PlanEquivalence.compare(left, right);
        assertFalse(report.equivalent());
        assertTrue(report.divergences().stream().anyMatch(d -> d.contains("step[1]")));
    }

    @Test
    public void rejectedSuggestionIsNeverEquivalentEvenIfDigestMatchesContentShape() {
        // 建议计划缺 stepScopes → planDigest 不可算、门禁拒绝
        StepPlan left = RuleBasedIntentTranslator.approving("codegen").translate(skeleton());
        StepPlan rejected = new StepPlan(1, "llm", List.of(
                Command.click(LocatorSpec.parse("role=button;name=Submit"))),
                ParamScope.EMPTY, List.of());

        PlanEquivalence.Report report = PlanEquivalence.compare(left, rejected);
        assertFalse(report.equivalent());
        assertFalse(report.rightValid());
        assertTrue("rightViolations=" + report.rightViolations(),
                !report.rightViolations().isEmpty());
        assertTrue("divergences=" + report.divergences(), report.divergences().stream()
                .anyMatch(d -> d.contains("IntentPlanValidator")));
    }

    @Test
    public void nullInputsAreHandledSafely() {
        assertFalse(PlanEquivalence.equivalent(null, null));
        assertFalse(PlanEquivalence.compare(null, null).equivalent());
        assertFalse(PlanEquivalence.forInput(null, null, null).equivalent());
        assertFalse(PlanEquivalence.forInput(skeleton(), null, null).equivalent());
    }

    @Test
    public void naturalLanguageDifferenceIsNotATruthSource() {
        // 两条路径 planId / translator id 完全不同，但结构化内容一致 → 等价
        StepPlan a = RuleBasedIntentTranslator.approving("codegen").translate(skeleton());
        StepPlan b = new ModelSuggestionTranslator(false).translate(skeleton());
        assertFalse(a.planId().equals(b.planId()));
        assertEquals("RuleBasedIntentTranslator",
                RuleBasedIntentTranslator.approving("codegen").id());
        assertEquals("ModelSuggestionTranslator", new ModelSuggestionTranslator(false).id());
        assertTrue(PlanEquivalence.compare(a, b).equivalent());
    }
}

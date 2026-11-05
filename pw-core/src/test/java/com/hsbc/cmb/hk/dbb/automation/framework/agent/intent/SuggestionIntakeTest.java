package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SuggestionIntake} 契约测试（IDE 助手场景的「收回来」一侧）。
 *
 * <p>锁定：围栏/说明文字容错、结构化门禁、与基线等价性对账、
 * 越权动作与垃圾文本的 fail-closed 行为。</p>
 */
public class SuggestionIntakeTest {

    private static final String BASELINE = "{"
            + "\"schemaVersion\":1,\"planId\":\"plan-baseline\","
            // 副作用参数必须声明取值域（治理规则），缺 scope 会被统一门禁拒
            + "\"scope\":{\"value\":{\"allow\":[\"alice\",\"bob\"]}},"
            + "\"steps\":["
            + " {\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"},"
            + " {\"action\":\"FILL\",\"target\":\"role=textbox;name=User\",\"params\":{\"value\":\"alice\"}}"
            + "]}";

    @Test
    public void parsesSuggestionWrappedInFenceAndProse() {
        String output = "下面是翻译结果：\n```json\n" + BASELINE + "\n```\n如有疑问请告知。";

        StepPlan plan = SuggestionIntake.parseSuggestion(output);

        assertEquals(2, plan.steps().size());
        assertEquals(Command.ActionType.CLICK, plan.steps().get(0).action());
    }

    @Test
    public void validEquivalentSuggestionIsOk() {
        // planId 不同、内容一致 ⇒ 等价（planDigest 刻意排除 planId）
        String suggestion = BASELINE.replace("plan-baseline", "plan-from-copilot");

        SuggestionIntake.Result result = SuggestionIntake.check(
                StepPlan.parse(BASELINE), SuggestionIntake.parseSuggestion(suggestion));

        assertTrue(result.accepted());
        assertTrue(result.equivalent());
        assertTrue(result.ok());
    }

    @Test
    public void divergentStepIsNotEquivalent() {
        String suggestion = BASELINE.replace("\"alice\"", "\"bob\"");

        SuggestionIntake.Result result = SuggestionIntake.check(
                StepPlan.parse(BASELINE), SuggestionIntake.parseSuggestion(suggestion));

        assertTrue("结构本身合法", result.accepted());
        assertFalse("内容不同必须判不等价", result.equivalent());
        assertFalse(result.ok());
        assertTrue(result.summary().contains("divergence"));
    }

    @Test
    public void unauthorizedActionIsRejectedByUnifiedGate() {
        // SWITCH_TO_POPUP 只能由框架内部产生，绕过 JSON 直接构造也应被门禁拒绝
        StepPlan suggested = new StepPlan(1, "plan-evil", List.of(
                new Command(Command.ActionType.SWITCH_TO_POPUP, null, Map.of())),
                null, null);

        SuggestionIntake.Result result = SuggestionIntake.check(StepPlan.parse(BASELINE), suggested);

        assertFalse(result.accepted());
        assertFalse(result.ok());
        assertTrue(result.planReport().reasons()
                .contains(IntentTranslationException.Reason.ACTION_NOT_ALLOWED));
    }

    @Test
    public void garbageTextFailsClosed() {
        try {
            SuggestionIntake.parseSuggestion("抱歉，我无法完成这个任务。");
            fail("expected IntentTranslationException");
        } catch (IntentTranslationException e) {
            assertEquals(IntentTranslationException.Reason.INVALID_INPUT, e.reason());
        }
    }

    @Test
    public void suggestionMissingPlanScopeIsNotEquivalent() {
        // 基线翻译器会为 pageRef 声明 plan 级 url 取值域；建议若漏掉 scope，
        // 即使步骤内容一致也判不等价（fail-closed：模型不能靠「看起来一样」绕过治理声明）。
        RecordedSkeleton skeleton = new RecordedSkeleton(1, "codegen", "https://www.example.com/logon",
                List.of(new RecordedStep("CLICK", "role=button;name=Submit", Map.of())));

        SuggestionIntake.Result result = SuggestionIntake.check(skeleton,
                new RuleBasedIntentTranslator(Set.of("codegen")),
                "{\"schemaVersion\":1,\"planId\":\"plan-copilot\",\"steps\":["
                        + "{\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"}]}");

        assertTrue(result.accepted());
        assertFalse("漏掉 plan scope 必须判不等价", result.equivalent());
        assertFalse(result.summary(), result.ok());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.IntentPlanValidator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.IntentTranslationException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedSkeleton;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedStep;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RuleBasedIntentTranslator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RecordedSkeletonConverter} 契约测试（录制产物 → 结构化骨架）。
 *
 * <p>锁定四类口径：动作原语与 codegen/意图两侧同源、fail-closed 不静默丢步、
 * 数据最小化（R1/R7）、确定性（seq 排序 + 固定定位梯队）。</p>
 */
public class RecordedSkeletonConverterTest {

    private static final String PAGE_URL = "https://www.example.com:8443/logon";
    private static final String ORIGIN = "www.example.com:8443";

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    public void clickOnButtonProducesClickStep() {
        CodegenEntry button = entry("role=button;name=Logon", "button", "Logon", null, "#logon");

        RecordedSkeleton skeleton = convert(actions(click("role=button;name=Logon")), entries(button));

        assertEquals(RecordedSkeleton.SUPPORTED_SCHEMA_VERSION, skeleton.schemaVersion());
        assertEquals(ORIGIN, skeleton.origin());
        assertEquals(PAGE_URL, skeleton.pageRef());
        assertEquals(1, skeleton.steps().size());
        RecordedStep step = skeleton.steps().get(0);
        assertEquals("CLICK", step.action());
        assertEquals("role=button;name=Logon", step.target());
        assertTrue("CLICK 不应携带参数", step.params().isEmpty());
    }

    @Test
    public void fillOnTextboxProducesFillWithSanitizedValue() {
        CodegenEntry textbox = entry("role=textbox;name=Password", "textbox", "Password", null, "#pwd");

        RecordedSkeleton skeleton = convert(
                actions(fill("role=textbox;name=Password", "password=Sup3rSecret")), entries(textbox));

        RecordedStep step = skeleton.steps().get(0);
        assertEquals("FILL", step.action());
        String value = step.params().get("value");
        assertTrue("FILL 必须带 value", value != null && !value.isBlank());
        // R1/R7：凭据形态的录入值不得原样出域
        assertFalse("凭据原文泄漏进骨架: " + value, value.contains("Sup3rSecret"));
    }

    @Test
    public void clickOnCheckboxBecomesCheckByRole() {
        CodegenEntry checkbox = entry("role=checkbox;name=I agree", "checkbox", "I agree", null, "#agree");

        RecordedSkeleton skeleton = convert(actions(click("role=checkbox;name=I agree")), entries(checkbox));

        // 与 CodegenFieldPlanner.verbFor 同口径：checkbox 上的一次 click 语义是 check
        assertEquals("CHECK", skeleton.steps().get(0).action());
    }

    @Test
    public void nlsKeyPreferredOverLiteralName() {
        CodegenEntry button = entry("role=button;name=Logon", "button", "Logon", "login.submit", "#logon");

        RecordedSkeleton skeleton = convert(actions(click("role=button;name=Logon")), entries(button));

        String target = skeleton.steps().get(0).target();
        assertTrue("多语言首选 nameKey: " + target, target.contains("nameKey=login.submit"));
        assertFalse("不应同时携带字面名: " + target, target.contains(";name="));
    }

    @Test
    public void nonLocatableRoleFallsBackToCss() {
        CodegenEntry generic = entry("generic#0", "generic", "plain text", null, "#submit");

        RecordedSkeleton skeleton = convert(actions(click("generic#0")), entries(generic));

        // generic 按 role 定位恒不命中（AriaRoles 判定），必须回退 CSS 而非臆造角色
        assertEquals("css=#submit", skeleton.steps().get(0).target());
    }

    @Test
    public void stepsFollowRecordingSeq() {
        CodegenEntry first = entry("a", "button", "First", null, "#a");
        CodegenEntry second = entry("b", "button", "Second", null, "#b");
        List<CodegenRecorder.Action> unordered = new ArrayList<>();
        unordered.add(new CodegenRecorder.Action("b", "click", null, 2));
        unordered.add(new CodegenRecorder.Action("a", "click", null, 1));

        RecordedSkeleton skeleton = convert(unordered, entries(first, second));

        assertEquals("role=button;name=First", skeleton.steps().get(0).target());
        assertEquals("role=button;name=Second", skeleton.steps().get(1).target());
    }

    @Test
    public void sameInputProducesIdenticalSkeleton() {
        CodegenEntry button = entry("a", "button", "Logon", null, "#a");
        CodegenEntry textbox = entry("b", "textbox", "User", null, "#b");
        List<CodegenRecorder.Action> actions = new ArrayList<>();
        actions.add(click("a"));
        actions.add(fill("b", "alice"));

        RecordedSkeleton first = convert(actions, entries(button, textbox));
        RecordedSkeleton second = convert(actions, entries(button, textbox));

        assertEquals(first.steps(), second.steps());
        assertEquals(first.origin(), second.origin());
    }

    // ------------------------------------------------------------------
    // fail-closed
    // ------------------------------------------------------------------

    @Test
    public void missingPickedEntryFailsClosed() {
        IntentTranslationException e = expectReject(
                () -> convert(actions(click("not-picked")), entries()));
        assertEquals(IntentTranslationException.Reason.TARGET_VIOLATION, e.reason());
    }

    @Test
    public void unknownRecordedTypeFailsClosed() {
        CodegenEntry button = entry("a", "button", "Logon", null, "#a");
        List<CodegenRecorder.Action> actions =
                List.of(new CodegenRecorder.Action("a", "hover", null, 1));

        IntentTranslationException e = expectReject(() -> convert(actions, entries(button)));
        assertEquals(IntentTranslationException.Reason.UNKNOWN_ACTION, e.reason());
    }

    @Test
    public void blankFillValueFailsClosed() {
        CodegenEntry textbox = entry("b", "textbox", "User", null, "#b");

        IntentTranslationException e = expectReject(
                () -> convert(actions(fill("b", "   ")), entries(textbox)));
        assertEquals(IntentTranslationException.Reason.PARAM_SCHEMA_VIOLATION, e.reason());
    }

    @Test
    public void emptyRecordingFailsClosed() {
        IntentTranslationException e = expectReject(() -> convert(actions(), entries()));
        assertEquals(IntentTranslationException.Reason.BOUNDS_VIOLATION, e.reason());
    }

    @Test
    public void oversizedRecordingFailsClosed() {
        CodegenEntry button = entry("a", "button", "Logon", null, "#a");
        List<CodegenRecorder.Action> tooMany = new ArrayList<>();
        for (int i = 1; i <= RecordedSkeleton.MAX_STEPS + 1; i++) {
            tooMany.add(new CodegenRecorder.Action("a", "click", null, i));
        }

        IntentTranslationException e = expectReject(() -> convert(tooMany, entries(button)));
        assertEquals(IntentTranslationException.Reason.BOUNDS_VIOLATION, e.reason());
    }

    // ------------------------------------------------------------------
    // origin 归一化
    // ------------------------------------------------------------------

    @Test
    public void originFromUrlKeepsHostAndNonDefaultPort() {
        assertEquals("www.example.com:8443",
                RecordedSkeletonConverter.originFromUrl("https://www.example.com:8443/logon?x=1"));
        assertEquals("www.example.com",
                RecordedSkeletonConverter.originFromUrl("https://WWW.Example.COM/logon"));
    }

    @Test
    public void originFromUrlRejectsHostlessUrl() {
        IntentTranslationException e = expectReject(
                () -> RecordedSkeletonConverter.originFromUrl("about:blank"));
        assertEquals(IntentTranslationException.Reason.INVALID_INPUT, e.reason());
    }

    // ------------------------------------------------------------------
    // 跨模块闭环：转换产物可被 pw-core 意图翻译直接消费
    // ------------------------------------------------------------------

    @Test
    public void convertedSkeletonIsTranslatableToStepPlan() {
        CodegenEntry user = entry("a", "textbox", "User", null, "#u");
        CodegenEntry submit = entry("b", "button", "Logon", null, "#s");
        List<CodegenRecorder.Action> actions = new ArrayList<>();
        actions.add(fill("a", "alice"));
        actions.add(click("b"));

        RecordedSkeleton skeleton = convert(actions, entries(user, submit));
        StepPlan plan = new RuleBasedIntentTranslator(java.util.Set.of(ORIGIN)).translate(skeleton);

        assertEquals(2, plan.steps().size());
        assertEquals("FILL", plan.steps().get(0).action().name());
        assertEquals("CLICK", plan.steps().get(1).action().name());
        assertTrue("产出的计划必须过统一门禁", IntentPlanValidator.inspect(plan).accepted());
    }

    @Test
    public void convertedSkeletonRoundTripsThroughJson() {
        CodegenEntry button = entry("a", "button", "Logon", null, "#a");

        RecordedSkeleton skeleton = convert(actions(click("a")), entries(button));
        RecordedSkeleton reparsed = RecordedSkeleton.parse(skeleton.toJson());

        // 录制 → 骨架 → 落盘 → 重读，语义不丢（管线交接的最小闭环）
        assertEquals(skeleton.steps(), reparsed.steps());
        assertEquals(ORIGIN, reparsed.origin());
        assertEquals(PAGE_URL, reparsed.pageRef());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static RecordedSkeleton convert(List<CodegenRecorder.Action> actions,
                                            Map<String, CodegenEntry> entries) {
        return RecordedSkeletonConverter.convert(
                new RecordedSkeletonConverter.Source(ORIGIN, PAGE_URL, actions, entries));
    }

    private static List<CodegenRecorder.Action> actions(CodegenRecorder.Action... items) {
        return new ArrayList<>(List.of(items));
    }

    private static CodegenRecorder.Action click(String fieldKey) {
        return new CodegenRecorder.Action(fieldKey, "click", null, 1);
    }

    private static CodegenRecorder.Action fill(String fieldKey, String value) {
        return new CodegenRecorder.Action(fieldKey, "fill", value, 1);
    }

    private static Map<String, CodegenEntry> entries(CodegenEntry... items) {
        Map<String, CodegenEntry> map = new LinkedHashMap<>();
        for (CodegenEntry item : items) {
            map.put(item.sigKey, item);
        }
        return map;
    }

    private static CodegenEntry entry(String sigKey, String role, String name, String nlsKey, String css) {
        return new CodegenEntry(sigKey, role, name, "role", 90, css, css, nlsKey, -1);
    }

    private static IntentTranslationException expectReject(Runnable action) {
        try {
            action.run();
        } catch (IntentTranslationException e) {
            return e;
        }
        fail("expected IntentTranslationException");
        return null;
    }
}

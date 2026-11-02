package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldPlanner;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenTarget;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.StepClassGenerator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 验证「操作序列驱动重排」：{@link CodegenLauncher#orderBySequence} 把被操作过的字段排前，
 * 并联动 {@code StepClassGenerator} 使生成的 Step 方法体按真实操作先后排列。
 */
public class CodegenSequenceTest {

    private static CodegenEntry e(String sig, String role, String name) {
        return new CodegenEntry(sig, role, name, "ROLE", 105, "role=" + role, "#x", null, 0);
    }

    @Test
    public void orderBySequence_movesActedFieldsToFront() {
        List<CodegenEntry> entries = List.of(
                e("button|Submit#0", "button", "Submit"),
                e("textbox|User#0", "textbox", "User"),
                e("textbox|Pwd#0", "textbox", "Pwd"));
        // 用户真实顺序：先填 Pwd，再填 User，最后点 Submit
        List<String> actions = List.of("textbox|Pwd#0", "textbox|User#0", "button|Submit#0");
        List<CodegenEntry> ordered = CodegenLauncher.orderBySequence(entries, actions);
        Assert.assertEquals(List.of(
                "textbox|Pwd#0", "textbox|User#0", "button|Submit#0"),
                ordered.stream().map(x -> x.sigKey).toList());
    }

    @Test
    public void orderBySequence_appendsUnactedFieldsAfter() {
        List<CodegenEntry> entries = List.of(
                e("button|Submit#0", "button", "Submit"),
                e("textbox|User#0", "textbox", "User"),
                e("link|Home#0", "link", "Home"));
        // 只操作了 Submit
        List<CodegenEntry> ordered = CodegenLauncher.orderBySequence(entries, List.of("button|Submit#0"));
        Assert.assertEquals(List.of(
                "button|Submit#0", "textbox|User#0", "link|Home#0"),
                ordered.stream().map(x -> x.sigKey).toList());
    }

    @Test
    public void stepGeneration_followsActedOrder() {
        CodegenTarget target = CodegenTarget.of("com.example.pages", "https://host/portalserver/login");
        List<CodegenEntry> entries = List.of(
                e("button|Submit#0", "button", "Submit"),
                e("textbox|User#0", "textbox", "User"),
                e("textbox|Pwd#0", "textbox", "Pwd"));
        List<CodegenEntry> ordered = CodegenLauncher.orderBySequence(entries,
                List.of("textbox|Pwd#0", "textbox|User#0", "button|Submit#0"));

        // 一次 Start→Pause 会话 = 一个 step 方法；方法体内动作顺序应与操作序列一致。
        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(ordered, new String[]{}, Map.of());
        List<MultiPageCodegen.SessionAction> actions = new ArrayList<>(ordered.size());
        for (CodegenEntry entry : ordered) {
            CodegenFieldSpec f = null;
            for (CodegenFieldSpec s : plan.fields) {
                if (s.entry == entry) {
                    f = s;
                    break;
                }
            }
            Assert.assertNotNull("字段规划缺失: " + entry.sigKey, f);
            actions.add(MultiPageCodegen.SessionAction.element(target, f.fieldName, f.action, ""));
        }
        MultiPageCodegen.SessionUnit session = new MultiPageCodegen.SessionUnit(
                1, target, List.of(target), actions, List.of());
        String step = StepClassGenerator.generateSessions(target, List.of(session));

        int pwdIdx = step.indexOf("loginPage.pwd.fill");
        int userIdx = step.indexOf("loginPage.user.fill");
        int submitIdx = step.indexOf("loginPage.submit.click");
        Assert.assertTrue("Pwd 操作应存在", pwdIdx >= 0);
        Assert.assertTrue("User 操作应存在", userIdx >= 0);
        Assert.assertTrue("Submit 操作应存在", submitIdx >= 0);
        Assert.assertTrue("单方法体内操作顺序应跟随操作序列：Pwd < User < Submit",
                pwdIdx < userIdx && userIdx < submitIdx);
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 字段命名规范契约（用户 2026-11-15 定稿）：
 * 按钮 {@code xxxBtn}、输入 {@code xxxInput}、链接 {@code xxxLink}、label {@code xxxLabel}、
 * 单选 {@code xxxRadio}、复选 {@code xxxCheckbox}，标题按层级 {@code xxxTitle} / {@code xxxSubtitle}。
 *
 * <p>判定顺序是<b>角色优先、标签兜底</b>：同一个 {@code <input>} 可能是按钮 / 单选框 / 复选框 / 文本框，
 * 只看标签无法区分；AX 角色才是运行期 {@code getByRole} 用的同一套语义。
 *
 * <p>同时锁死三类「不要叠加后缀」的边界（否则产物会退化成 {@code buttonBtn} / {@code privacyLinkLink}）：
 * 名字本身就是角色名、名字已带该后缀、兜底名 {@code element}。
 */
public class CodegenFieldNamingTest {

    private static CodegenEntry entry(String role, String name, String tag, int index) {
        return new CodegenEntry(role + "|" + name + "#" + index, role, name, "ROLE", 100,
                "role=" + role, "#el" + index, null, index, List.of(), List.of(), 1, tag);
    }

    /** 规划后按输入顺序取字段名（plan 保序）。 */
    private static List<String> fieldNames(CodegenEntry... entries) {
        List<CodegenEntry> es = new ArrayList<>(List.of(entries));
        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(es, new String[]{}, Map.of());
        List<String> names = new ArrayList<>();
        for (CodegenFieldSpec f : plan.fields) names.add(f.fieldName);
        return names;
    }

    @Test
    public void kindSuffixFollowsRoleNotTag() {
        // 同为 <input>：按钮 / 单选框 / 复选框 / 文本框必须各自不同后缀
        List<String> names = fieldNames(
                entry("button", "Continue", "button", 0),
                entry("radio", "Physical Security Device", "input", 1),
                entry("checkbox", "Remember me", "input", 2),
                entry("textbox", "User name", "input", 3),
                entry("link", "Privacy and Security", "a", 4),
                entry("label", "Mobile number", "label", 5));

        Assert.assertEquals(List.of("continueBtn", "physicalSecurityDeviceRadio", "rememberMeCheckbox",
                "userNameInput", "privacyAndSecurityLink", "mobileNumberLabel"), names);
    }

    @Test
    public void headingSuffixReflectsLevel() {
        List<String> names = fieldNames(
                entry("heading", "Reset Password", "h1", 0),
                entry("heading", "Please select one of the methods", "h2", 1),
                entry("heading", "Terms and conditions", "h3", 2));

        Assert.assertEquals(List.of("resetPasswordTitle", "pleaseSelectOneOfTheMethodsTitle",
                "termsAndConditionsSubtitle"), names);
    }

    /** 角色兜底名本身已是角色词 ⇒ 不再叠加后缀（{@code button} 而不是 {@code buttonBtn}）。 */
    @Test
    public void roleWordBaseNameIsNotSuffixedTwice() {
        // 中文名转不出 ASCII ⇒ 顺延到角色名
        List<String> names = fieldNames(entry("button", "保存", "button", 0));

        Assert.assertEquals(List.of("button"), names);
    }

    /** 名字已带同后缀 ⇒ 不重复（{@code privacyLink} 而不是 {@code privacyLinkLink}）。 */
    @Test
    public void existingSuffixIsNotDuplicated() {
        List<String> names = fieldNames(
                entry("link", "Privacy link", "a", 0),
                entry("textbox", "User input", "input", 1));

        Assert.assertEquals(List.of("privacyLink", "userInput"), names);
    }

    /**
     * 同名不同元素（不是一组：sigKey 的 base 不同）⇒ 去重追加序号，且序号在后缀之后
     * （{@code continueBtn2}，而不是 {@code continue2Btn}）。
     *
     * <p>两个 {@code button|Continue#n} 会因「同 base-sig + 互异序号」被判成一组（合成一个
     * {@code PageElementList}），不是本用例要覆盖的命名去重路径，故用不同 base。
     */
    @Test
    public void duplicateNamesKeepSuffixBeforeCounter() {
        CodegenEntry byName = entry("button", "Continue", "button", 0);
        CodegenEntry byKey = new CodegenEntry("button|#1", "button", " ", "ROLE", 100,
                "role=button", "#continue2", "continue", 0, List.of(), List.of(), 1, "button");

        Assert.assertEquals(List.of("continueBtn", "continueBtn2"), fieldNames(byName, byKey));
    }

    /** 非交互元素（div / span / img）不加后缀：字段名本身来自业务文案，加 Div/Span 只是噪声。 */
    @Test
    public void nonInteractiveElementsHaveNoSuffix() {
        List<String> names = fieldNames(
                entry("generic", "Notification banner", "div", 0),
                entry("img", "Bank logo", "img", 1));

        Assert.assertEquals(List.of("notificationBanner", "bankLogo"), names);
    }
}

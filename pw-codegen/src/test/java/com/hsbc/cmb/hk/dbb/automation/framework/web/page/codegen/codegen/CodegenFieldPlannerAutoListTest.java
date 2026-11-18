package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * 「集合项自动成组」（{@link CodegenEntry#autoList}）的生成契约。
 *
 * <p>两种成组来源的序号语义完全不同，绝不能混用：
 * <ul>
 *   <li><b>多次拾取 / 面板标记</b>：{@code index} 只是组内成员序号 ⇒ 产物沿用 {@code get(0)}；</li>
 *   <li><b>自动成组</b>（目标是 ARIA 集合里的一项）：{@code index} 是<b>用户实际点的那一项</b>
 *       ⇒ 产物必须是 {@code get(index)}，否则回放会点错选项（密保问题点第 5 题却回放成第 1 题）。</li>
 * </ul>
 */
public class CodegenFieldPlannerAutoListTest {

    private static final String GROUP_CSS = "ul[aria-labelledby=\"label_sq1\"] > li[role=\"option\"]";

    /** 自动成组：PageElementList + get(4)（用户点的是第 5 个选项）。 */
    @Test
    public void autoListTargetsThePickedItem() {
        CodegenEntry e = new CodegenEntry("option|Name a memorable city or country#4", "option",
                "Name a memorable city or country", LocatorStrategy.CSS, 500, GROUP_CSS, GROUP_CSS,
                null, 4, List.of(), List.of(), 10, "li", false,
                CodegenEntry.ROLE_HITS_NOT_APPLICABLE, true);

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertEquals("PageElementList", f.fieldType);
        Assert.assertTrue(f.isList);
        Assert.assertEquals("组内序号必须原样带出", 4, f.listIndex);
        Assert.assertEquals("get(4).click()", "get(4).click()", f.action);
        Assert.assertEquals("get(4).isVisible()", "get(4).isVisible()", f.assertion);
        Assert.assertTrue("注解必须是命中整组的 CSS", f.annotation.contains("label_sq1"));
        Assert.assertFalse("自动成组不该出现「只作用 get(0)」的 TODO",
                f.comment != null && f.comment.contains("get(0) only"));
        Assert.assertTrue("产物注释应写明 get(n) 的来历: " + f.comment,
                f.comment != null && f.comment.contains("get(4)"));
    }

    /**
     * 自动成组优先于计数：整组定位器在拾取瞬间可能因懒渲染数到 0 个，
     * 但「目标是集合里的一项」这个语义本身就该出 {@code PageElementList}。
     */
    @Test
    public void autoListStaysAListEvenWhenCountIsNotPositive() {
        CodegenEntry e = new CodegenEntry("option|Country#2", "option", "Country",
                LocatorStrategy.CSS, 500, GROUP_CSS, GROUP_CSS, null, 2, List.of(), List.of(), 0,
                "li", false, CodegenEntry.ROLE_HITS_NOT_APPLICABLE, true);

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertEquals("PageElementList", f.fieldType);
        Assert.assertEquals("get(2).click()", f.action);
    }

    /**
     * 两个<b>不同集合</b>里名字相同的选项不得并成一个字段。
     *
     * <p>旧判据只看 {@code role|name} 基座：两个下拉里都有的 "Yes" 会被当成一组，共用第一个下拉的
     * 定位器，且第二个动作因「一组只出一行」被丢掉——用户点过的那一步静默消失。
     */
    @Test
    public void sameNamedItemsInDifferentCollectionsAreNotMerged() {
        CodegenEntry first = autoItem("Yes", 1, "#q1 > li");
        CodegenEntry second = autoItem("Yes", 3, "#q2 > li");

        CodegenFieldPlanner.Plan plan =
                CodegenFieldPlanner.plan(List.of(first, second), new String[]{}, Map.of());

        Assert.assertEquals("两个集合各自一组，不得合并", 2, plan.fields.size());
        Assert.assertEquals("get(1).click()", plan.fields.get(0).action);
        Assert.assertEquals("get(3).click()", plan.fields.get(1).action);
        Assert.assertTrue(plan.fields.get(0).annotation.contains("#q1"));
        Assert.assertTrue(plan.fields.get(1).annotation.contains("#q2"));
    }

    /**
     * 两个不同容器里的同名按钮：一个 role 命中多回退成 CSS、一个保留 role（导航兜底恢复）。
     * 定位器不同 ⇒ 不得被 base-sig 误并成一个 PageElementList，否则后一个字段被 emittedListGroups
     * 跳过——用户看到的就是「两个 Continue 只生成一个」。
     */
    @Test
    public void sameNameDifferentLocatorsKeepBothFields() {
        CodegenEntry cssFallback = new CodegenEntry("button|Continue#0", "button", "Continue",
                LocatorStrategy.CSS, 500,
                ".page:has([data-i18n=\"title_triage\"]) button.float-right",
                ".page:has([data-i18n=\"title_triage\"]) button.float-right",
                "button_continue", 0, List.of(), List.of(), 1, "button", false);
        CodegenEntry roleKept = new CodegenEntry("button|Continue#1", "button", "Continue",
                LocatorStrategy.ROLE, 105,
                "role=button[name=Nls.t(\"button_continue\")]", "button.float-right",
                "button_continue", 1, List.of(), List.of(), 1, "button", false, 1);

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(
                List.of(cssFallback, roleKept), new String[]{}, Map.of());

        Assert.assertEquals("两个独立元素 ⇒ 两个字段", 2, plan.fields.size());
        Assert.assertEquals("PageElement", plan.fields.get(0).fieldType);
        Assert.assertEquals("PageElement", plan.fields.get(1).fieldType);
    }

    /** 真·同组的成员（同定位器、index 互异）仍须合成一个 PageElementList，防止修复误伤正常成组。 */
    @Test
    public void sameNameSameLocatorStillGroupsIntoOneField() {
        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(
                List.of(member(0), member(1)), new String[]{}, Map.of());

        Assert.assertEquals("同定位器的两个成员 ⇒ 一个 List 字段", 1, plan.fields.size());
        Assert.assertEquals("PageElementList", plan.fields.get(0).fieldType);
    }

    /** 同一集合里点了两次：两个动作都必须保留（各自带自己的序号），绝不能因合并丢步。 */
    @Test
    public void twoPicksInOneCollectionKeepBothActions() {
        CodegenEntry first = autoItem("Yes", 1, "#q1 > li");
        CodegenEntry second = autoItem("Yes", 3, "#q1 > li");

        CodegenFieldPlanner.Plan plan =
                CodegenFieldPlanner.plan(List.of(first, second), new String[]{}, Map.of());

        Assert.assertEquals("两次点击 ⇒ 两个字段（宁可同定位器，也不能丢动作）", 2, plan.fields.size());
        Assert.assertEquals("get(1).click()", plan.fields.get(0).action);
        Assert.assertEquals("get(3).click()", plan.fields.get(1).action);
    }

    /** 自动成组的条目：整组 CSS + 拾取项序号。 */
    private static CodegenEntry autoItem(String name, int index, String groupCss) {
        return new CodegenEntry("option|" + name + "#" + index, "option", name, LocatorStrategy.CSS,
                500, groupCss, groupCss, null, index, List.of(), List.of(), 5, "li", false,
                CodegenEntry.ROLE_HITS_NOT_APPLICABLE, true);
    }

    /** 手动成组（多次拾取合成）不得被自动成组的序号语义污染：仍取首项。 */
    @Test
    public void manuallyGroupedPicksStillTargetTheFirstItem() {
        List<CodegenEntry> members = List.of(
                member(0), member(1));

        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(members, new String[]{}, Map.of());

        CodegenFieldSpec f = plan.fields.get(0);
        Assert.assertTrue(f.isList);
        Assert.assertEquals("组成员序号不是「用户点的那一项」", 0, f.listIndex);
        Assert.assertEquals("get(0).click()", f.action);
        Assert.assertTrue("手动组仍要提示只作用 get(0)",
                f.comment != null && f.comment.contains("get(0) only"));
    }

    /** 多次拾取合成的组成员（roleHits=1 ⇒ 复核通过，单条 role 定位器只命中自己）。 */
    private static CodegenEntry member(int index) {
        return new CodegenEntry("listitem|Item#" + index, "listitem", "Item", LocatorStrategy.ROLE,
                105, "role=listitem[name=\"Item\"]", "li.item", null, index, List.of(), List.of(),
                1, "li", false, 1);
    }

    /** 单元素（autoList=false、唯一命中）：行为与改造前完全一致。 */
    @Test
    public void singleElementIsUnaffected() {
        CodegenEntry e = new CodegenEntry("button|Save#0", "button", "Save", LocatorStrategy.CSS,
                500, "#save", "#save", null, 0, List.of(), List.of(), 1, "button", false);

        CodegenFieldSpec f = CodegenFieldPlanner.plan(List.of(e), new String[]{}, Map.of()).fields.get(0);

        Assert.assertEquals("PageElement", f.fieldType);
        Assert.assertEquals("", f.listTarget());
        Assert.assertEquals("click()", f.action);
    }
}

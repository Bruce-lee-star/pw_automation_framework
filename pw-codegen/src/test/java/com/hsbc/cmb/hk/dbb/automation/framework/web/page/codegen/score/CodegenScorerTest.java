package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenFacts;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java 验证 {@link CodegenScorer}：打分优先级、角色/CSS 选择、唯一性校验、父级嵌套降级、深度上限护栏。
 * 不依赖浏览器——计数经 {@link LocatorCounter} 桩注入。
 */
public class CodegenScorerTest {

    private static CodegenFacts.Fact fact(String role, String name, String id, String testid,
                                         String tag, String placeholder, String title, String css) {
        return new CodegenFacts.Fact(role, name, tag, id, testid, "", placeholder, "", title,
                "", "", "", -1, css);
    }

    /**
     * 真实场景（HSBC reset-password）：{@code [data-i18n="continue"]} 命中 3 个——隐藏 modal 一份、
     * 两个未激活分页各一份；只有当前页所在面板有唯一标题锚点 {@code data-i18n="title_triage"}。
     *
     * <p>期望产物：{@code div:has([data-i18n="title_triage"]) [data-testid="continue-all"]}——
     * 原生 CSS（Chromium 105+），不依赖可见性，也不引入 Playwright 专有伪类。
     */
    @Test
    public void duplicateWidgetIsScopedByAncestorHasAnchor() {
        CodegenFacts.Fact self = rich("generic", "", "", "continue-all", "span", "",
                "span:nth-of-type(1)", "", "", "");
        CodegenFacts.Fact panel = ancestor("div", "", "", List.of("[data-i18n=\"title_triage\"]"));
        LocatorCounter counter = css -> css.equals(
                "div:has([data-i18n=\"title_triage\"]) [data-testid=\"continue-all\"]") ? 1 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self, panel), counter);

        Assert.assertEquals("div:has([data-i18n=\"title_triage\"]) [data-testid=\"continue-all\"]",
                s.uniqueCss);
        Assert.assertEquals("必须按单元素处理", 1, s.matchCount);
        Assert.assertEquals(LocatorStrategy.CSS, s.strategy);
    }

    /** 锚点含引号等非法字符时不得拼进选择器（仍走原兜底，matchCount 如实为 3）。 */
    @Test
    public void invalidHasAnchorIsIgnored() {
        CodegenFacts.Fact self = rich("generic", "", "", "continue-all", "span", "",
                "span:nth-of-type(1)", "", "", "");
        CodegenFacts.Fact panel = ancestor("div", "", "", List.of("[data-i18n=\"a\\\"]\"]"));

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self, panel), css -> 3);

        Assert.assertFalse(s.uniqueCss.contains(":has("));
        Assert.assertEquals(3, s.matchCount);
    }

    // ------------------------------------------------------------------
    // 原生 CSS 伪类收敛：:has(> 锚点) 与 :not(...)
    // ------------------------------------------------------------------

    /**
     * 锚点位于深层时，html/body/每层包裹 div 都会匹配 {@code :has(锚点)}（计数全部 >1 被丢弃）；
     * {@code :has(> 锚点)} 只认真正的直接父级，能一次收敛到唯一。
     */
    @Test
    public void hasDirectChildAnchorWinsOverNestedAncestorMatch() {
        CodegenFacts.Fact self = selfFact("span", "span:nth-of-type(1)", Map.of());
        CodegenFacts.Fact panel = ancestor("div", "", "", List.of("[data-i18n=\"title_triage\"]"));
        String direct = "div:has(> [data-i18n=\"title_triage\"]) span:nth-of-type(1)";
        LocatorCounter counter = css -> css.equals(direct) ? 1 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self, panel), counter);

        Assert.assertEquals(direct, s.uniqueCss);
        Assert.assertEquals(1, s.matchCount);
        Assert.assertFalse("产物不得引入 Playwright 专有 :visible", s.uniqueCss.contains(":visible"));
        Assert.assertFalse("产物不得引入 Playwright 专有 :has-text", s.uniqueCss.contains(":has-text("));
    }

    /**
     * 同容器内的禁用副本：前面的「加法」（id / 祖先属性作用域 / {@code :has()} 锚点）都区分不了，
     * 用 {@code :not([disabled])} 一次性砍掉整族干扰项。
     */
    @Test
    public void notExcludesDisabledTwin() {
        CodegenFacts.Fact self = selfFact("button", "button:nth-of-type(2)", Map.of());
        String expected = "button:nth-of-type(2):not([disabled])";
        LocatorCounter counter = css -> css.equals(expected) ? 1 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertEquals(expected, s.uniqueCss);
        Assert.assertEquals(1, s.matchCount);
    }

    /**
     * 减法可能把目标自己也排除掉（目标本身就是 disabled）：该条件计数为 0 时必须<b>换下一个条件</b>，
     * 不能像「加法」阶段那样直接放弃整条链。
     */
    @Test
    public void notSkipsConditionThatExcludesTargetItself() {
        CodegenFacts.Fact self = selfFact("button", "button:nth-of-type(2)", Map.of());
        String disabledNot = "button:nth-of-type(2):not([disabled])";
        String ariaNot = "button:nth-of-type(2):not([aria-hidden=\"true\"])";
        LocatorCounter counter = css -> {
            if (css.equals(disabledNot)) return 0;   // 目标自身带 disabled
            if (css.equals(ariaNot)) return 1;
            return 3;
        };

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertEquals(ariaNot, s.uniqueCss);
    }

    /** {@code [disabled]} 只对可禁用标签产出：给 {@code div} 加该条件不改变匹配集，白付一次计数往返。 */
    @Test
    public void notConditionsAreTagAware() {
        CodegenFacts.Fact self = selfFact("div", "div:nth-of-type(3)", Map.of());
        String disabledNot = "div:nth-of-type(3):not([disabled])";
        String ariaNot = "div:nth-of-type(3):not([aria-hidden=\"true\"])";
        LocatorCounter counter = css -> {
            if (css.equals(disabledNot)) return 1;   // 若实现错误地对 div 也试，就会选中它
            if (css.equals(ariaNot)) return 1;
            return 3;
        };

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertEquals(ariaNot, s.uniqueCss);
        Assert.assertFalse("div 不应产出 :not([disabled])", s.uniqueCss.contains("[disabled]"));
    }

    /** 单条否定都不足以收敛时两两叠加（仍是原生 CSS，不引入专有伪类）。 */
    @Test
    public void notCombinesTwoConditionsWhenSingleIsNotEnough() {
        CodegenFacts.Fact self = selfFact("input", "input:nth-of-type(2)", Map.of());
        String both = "input:nth-of-type(2):not([disabled]):not([aria-hidden=\"true\"])";
        LocatorCounter counter = css -> css.equals(both) ? 1 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertEquals(both, s.uniqueCss);
    }

    /** 目标自身带 {@code aria-hidden="true"} 时不产出该否定条件（必然 0 命中，纯浪费一次往返）。 */
    @Test
    public void notSkipsAriaHiddenWhenTargetItselfIsAriaHidden() {
        CodegenFacts.Fact self = selfFact("button", "button:nth-of-type(2)",
                Map.of("aria-hidden", "true"));
        String ariaNot = "button:nth-of-type(2):not([aria-hidden=\"true\"])";
        LocatorCounter counter = css -> css.equals(ariaNot) ? 1 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertFalse("目标自身 aria-hidden=true 时不得产出该条件",
                s.uniqueCss.contains("[aria-hidden"));
    }

    /**
     * 祖先链存在但结构路径仍命中多个时，减法作为最后一档兜底接管。
     *
     * <p>顺序语义：结构路径是确定性证据（逐级 {@code :nth-of-type} 必然指向目标），必须优先于
     * 可能误排目标的减法；只有它失败后才允许用 {@code :not()}。
     */
    @Test
    public void notIsUsedOnlyAfterStructuralPathFails() {
        CodegenFacts.Fact self = selfFact("button", "button:nth-of-type(2)", Map.of());
        CodegenFacts.Fact deep = fact("generic", "", "", "", "div", "", "",
                "div:nth-of-type(1) > button:nth-of-type(2)");
        String expected = "button:nth-of-type(2):not([disabled])";
        LocatorCounter counter = css -> css.equals(expected) ? 1 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self, deep), counter);

        Assert.assertEquals(expected, s.uniqueCss);
    }

    /**
     * {@code :not()} 只能否掉「同时离开渲染树与可访问性树」的形态，不能否掉 class。
     *
     * <p>{@code class="accessibleHidden"} / {@code .sr-only} 只是视觉隐藏（clip 裁零 / 1px 尺寸），
     * <b>仍在 AX 树里</b>：屏幕阅读器与 Playwright {@code getByRole} 都能命中，且往往就是唯一的
     * 可访问名来源（图标按钮的隐藏文字标签）。把它当「垃圾副本」排除，等于丢弃无障碍语义。
     */
    @Test
    public void visualOnlyHiddenClassIsNeverUsedAsNegation() {
        // self 事实里根本没有 class 字段参与否定条件；产物只能是状态属性否定或结构路径
        CodegenFacts.Fact self = selfFact("button", "button:nth-of-type(2)", Map.of());
        LocatorCounter counter = css -> 3;   // 所有候选都不唯一 → 走兜底

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertFalse("不得产出 class 型否定（accessibleHidden 在 AX 树里可见）",
                s.uniqueCss.contains(".accessibleHidden"));
        Assert.assertFalse("不得产出 class 型否定", s.uniqueCss.contains(":not(."));
    }

    /** 只带 tag / cssSelector / 语义属性的最小事实（role 不可定位，保证走纯 CSS 链）。 */
    private static CodegenFacts.Fact selfFact(String tag, String css, Map<String, String> attrs) {
        return new CodegenFacts.Fact("generic", "", tag, "", "", "", "", "", "", "",
                "", "", -1, css, List.of(), "", "", "", attrs, "", false, List.of());
    }

    private static CodegenFacts.Fact selfWithSrc(String tag, String css, String src) {
        return new CodegenFacts.Fact("generic", "", tag, "", "", "", "", "", "", "",
                "", "", -1, css, List.of(), "", "", "", java.util.Map.of(), src, false, List.of());
    }

    private static CodegenFacts.Fact selfWithAnchors(String tag, String css, List<String> anchors) {
        return new CodegenFacts.Fact("generic", "", tag, "", "", "", "", "", "", "",
                "", "", -1, css, List.of(), "", "", "", java.util.Map.of(), "", false, anchors);
    }

    private static CodegenFacts.Fact ancestor(String tag, String id, String testid, List<String> anchors) {
        return new CodegenFacts.Fact("generic", "", tag, id, testid, "", "", "", "", "", "", "", -1,
                "", List.of(), "", "", "", java.util.Map.of(), "", false, anchors);
    }

    private static CodegenFacts facts(CodegenFacts.Fact self, CodegenFacts.Fact... ancestors) {
        return new CodegenFacts(self, List.of(ancestors), List.of());
    }

    /** 角色可定位（button + name）⇒ 角色策略，分数 105（优于 CSS 链 530）。 */
    @Test
    public void roleWithName_winsOverCssChain() {
        CodegenFacts f = facts(fact("button", "Sign in", "", "", "button", "", "", "button:nth-of-type(1)"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, css -> 1);
        Assert.assertTrue(s.roleLocatable);
        Assert.assertEquals(LocatorStrategy.ROLE_WITH_NAME, s.strategy);
        Assert.assertEquals(105, s.score);
    }

    /**
     * 可访问名是「大段文本」时，即便 role 可映射、name 非空，也不得走 role 定位（2026-11-12 "不定位大文本"）：
     * 用一整块正文当 {@code getByRole(role, name)} 的 name 既脆弱又过宽，必须降级给 CSS 候选。
     */
    @Test
    public void broadRoleName_degradesToCss() {
        String longName = "Your session has expired. Please log in again to continue using our services securely.";
        Assert.assertTrue("前置：本例 name 确为长文本", longName.length() > 60);
        CodegenFacts f = facts(fact("button", longName, "", "", "button", "", "", "button:nth-of-type(1)"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, css -> 1);
        Assert.assertFalse(s.roleLocatable);
        Assert.assertEquals(LocatorStrategy.CSS, s.strategy);
    }

    /** 角色可定位时，即便存在 id（CSS 500），角色仍优先（105 <= 500）。 */
    @Test
    public void roleWithName_beatsId() {
        CodegenFacts f = facts(fact("link", "Home", "nav-home", "", "a", "", "", "#nav-home"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, css -> 1);
        Assert.assertTrue(s.roleLocatable);
        Assert.assertEquals(LocatorStrategy.ROLE_WITH_NAME, s.strategy);
    }

    /** data-testid 存在 ⇒ CSS 策略，分数 1（最高优先级，优于角色 105）。 */
    @Test
    public void dataTestId_topPriority() {
        CodegenFacts f = facts(fact("button", "Sign in", "", "submit-btn", "button", "", "", "button:nth-of-type(1)"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, css -> 1);
        Assert.assertFalse(s.roleLocatable);
        Assert.assertEquals(LocatorStrategy.CSS, s.strategy);
        Assert.assertEquals(1, s.score);
        Assert.assertEquals("[data-testid=\"submit-btn\"]", s.uniqueCss);
    }

    /** id 存在且无角色名 ⇒ CSS 策略，分数 500。 */
    @Test
    public void id_fallsBackToCss() {
        CodegenFacts f = facts(fact("generic", "", "user", "", "div", "", "", "#user"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, css -> 1);
        Assert.assertFalse(s.roleLocatable);
        Assert.assertEquals(LocatorStrategy.CSS, s.strategy);
        Assert.assertEquals(500, s.score);
        Assert.assertEquals("#user", s.uniqueCss);
    }

    /**
     * role 在 {@code AriaRole} 里不存在（如 {@code label}）⇒ 不得判为角色可定位，必须回退 CSS。
     *
     * <p>历史缺陷：这类角色被放行后在生成器里静默降级成 {@code AriaRole.GENERIC}，产出
     * {@code @RoleElement(role = AriaRole.GENERIC, key = "password")} —— 能编译但运行期定位不到元素。
     */
    @Test
    public void unknownRoleWithName_fallsBackToCss() {
        CodegenFacts f = facts(fact("label", "Business Internet Banking password", "", "",
                "label", "", "", "label:nth-of-type(2)"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, css -> 1);
        Assert.assertFalse(s.roleLocatable);
        Assert.assertEquals(LocatorStrategy.CSS, s.strategy);
        Assert.assertEquals("label:nth-of-type(2)", s.uniqueCss);
    }

    /** placeholder 文本框 ⇒ CSS 策略，分数 120，生成属性选择器。 */
    @Test
    public void placeholder_textbox_usesAttrSelector() {
        CodegenFacts f = facts(fact("textbox", "", "", "", "input", "User name", "", "input:nth-of-type(1)"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, css -> 1);
        Assert.assertFalse(s.roleLocatable);
        Assert.assertEquals(120, s.score);
        Assert.assertEquals("[placeholder=\"User name\"]", s.uniqueCss);
    }

    /** 角色可定位但 CSS 不唯一 ⇒ 仍选角色（角色匹配多个属 List 语义），但 uniqueCss 仍做父级嵌套备用。 */
    @Test
    public void uniqueCss_nestsWithAncestorWhenNotUnique() {
        // 基础 CSS 命中 3 个；祖先给出"从 #section 到目标的完整路径"后唯一
        LocatorCounter c = css -> css.equals("button:nth-of-type(1)") ? 3 : 1;
        CodegenFacts f = facts(
                fact("generic", "", "", "", "button", "", "", "button:nth-of-type(1)"),
                fact("generic", "", "section", "", "div", "", "", "#section > button:nth-of-type(1)"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, c);
        // 祖先带稳定 id ⇒ 优先用最短形式「#id 后代组合子」，而不是把整条路径堆出来
        Assert.assertEquals("#section button:nth-of-type(1)", s.uniqueCss);
    }

    /**
     * 回归：祖先项是"从该祖先到目标的完整路径"，Java 侧必须<b>整条替换</b> base，而不是当 base 的前缀拼接。
     *
     * <p>历史 bug：页面侧 {@code cssSel} 是"上溯最多 6 层"的窗口，把它当 base 前缀拼会与 base 自身窗口重叠，
     * 产出 {@code …div:nth-of-type(7) body:nth-of-type(1) > …} 这类不可能的层级（{@code body} 不可能是
     * {@code div} 的子元素），生成的 CSS 永远定位不到元素。
     */
    @Test
    public void ancestorCandidateReplacesBaseInsteadOfBeingPrefixed() {
        String ancestorPath =
                "html:nth-of-type(1) > body:nth-of-type(1) > div:nth-of-type(7) > div:nth-of-type(2)";
        LocatorCounter c = css -> css.equals("[class*='title']") ? 2 : 1;
        CodegenFacts f = facts(
                fact("generic", "", "", "", "div", "", "", "[class*='title']"),
                fact("generic", "", "", "", "div", "", "", ancestorPath));

        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, c);

        Assert.assertEquals("必须整条使用祖先给出的完整路径", ancestorPath, s.uniqueCss);
        Assert.assertFalse("不得把完整路径再当前缀拼到 base 之前",
                s.uniqueCss.contains("body:nth-of-type(1) [class*='title']"));
    }

    /**
     * 单属性不唯一时，必须优先尝试「属性组合」，而不是退化成长结构路径。
     *
     * <p>用户要求：能靠属性唯一定位就用属性；{@code html > body > … > div:nth-of-type(7)} 这种是<b>最后手段</b>。
     */
    @Test
    public void attributeComboWinsOverStructuralNesting() {
        String structural = "html:nth-of-type(1) > body:nth-of-type(1) > div:nth-of-type(3) > input:nth-of-type(2)";
        LocatorCounter c = css -> {
            if (css.equals("input[name=\"user\"]")) return 4;
            if (css.equals("input[name=\"user\"][type=\"text\"]")) return 1;
            return 9;   // 结构路径（及其加长版）都不唯一
        };
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "input", "", "", "text",
                "", "", "", "", "", "", -1, structural, List.of(), "", "", "user");
        CodegenFacts f = facts(self, fact("generic", "", "", "", "div", "", "", "#wrap > input:nth-of-type(2)"));

        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, c);

        Assert.assertEquals("input[name=\"user\"][type=\"text\"]", s.uniqueCss);
        Assert.assertFalse("不得退化成长结构路径", s.uniqueCss.contains("nth-of-type"));
    }

    /** 单一 class token 不唯一时，用同一元素的两个语义类名组合（比结构路径短且稳定得多）。 */
    @Test
    public void classTokenComboWhenSingleClassIsNotUnique() {
        String combo = "div[class*=\"title\"][class*=\"modal\"]";
        LocatorCounter c = css -> css.equals(combo) ? 1 : 6;
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "div", "", "", "",
                "", "", "", "", "", "", -1, "html > body > div:nth-of-type(1)",
                List.of(), "title modal", "", "");

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals(combo, s.uniqueCss);
        Assert.assertFalse(s.uniqueCss.contains("nth-of-type"));
    }

    /**
     * 有稳定 id 祖先时，必须优先用 {@code #id <base>}（短、可读、语义清晰），
     * 而不是把整条结构路径堆出来——长路径只能是"既无属性、也无 id 可用"时的最后手段。
     */
    @Test
    public void idAncestorPrefixIsPreferredOverLongStructuralPath() {
        String longPath = "html:nth-of-type(1) > body:nth-of-type(1) > div:nth-of-type(3) > div:nth-of-type(1)";
        String expected = "#newPasswordForm [class*=\"title\"]";
        LocatorCounter c = css -> css.equals(expected) ? 1 : 7;
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "div", "", "", "",
                "", "", "", "", "", "", -1, longPath, List.of(), "title", "", "");
        CodegenFacts.Fact ancestor = new CodegenFacts.Fact("generic", "", "form", "newPasswordForm",
                "", "", "", "", "", "", "", "", -1, "newPasswordForm > form:nth-of-type(1)");

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self, ancestor), c);

        Assert.assertEquals(expected, s.uniqueCss);
        Assert.assertFalse(s.uniqueCss.contains("nth-of-type"));
    }

    /**
     * 重复面板里的同名按钮：自身 class 命中 2 个（隐藏面板 + 可见面板），
     * 必须用<b>祖先面板的业务锚点</b>（{@code data-i18n}）做作用域前缀收窄，
     * 而不是一路退化到绝对 XPath。
     *
     * <p>2026-11-11 用户反馈「重复面板里 Continue 按钮定位不准」——隐藏/显示面板 DOM 结构近似，
     * 仅靠 class/data-id 无法区分，父级业务锚点是唯一稳定证据。
     */
    @Test
    public void ancestorBusinessAnchorScopesDuplicatePanelButton() {
        String expected = "[data-i18n=\"panel_active\"] [class*=\"continue\"]";
        LocatorCounter c = css -> expected.equals(css) ? 1 : 2;
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "button", "", "", "",
                "", "", "", "", "Continue", "", -1,
                "html > body > div:nth-of-type(2) > button:nth-of-type(1)",
                List.of(), "continue", "", "", Map.of());
        CodegenFacts.Fact panel = new CodegenFacts.Fact("generic", "", "div", "", "", "",
                "", "", "", "", "", "", -1, "html > body > div:nth-of-type(2)",
                List.of(), "panel", "", "", Map.of("data-i18n", "panel_active"));

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self, panel), c);

        Assert.assertEquals("必须用祖先业务锚点做作用域绑定", expected, s.uniqueCss);
        Assert.assertFalse("不得退化成绝对 XPath", s.uniqueCss.startsWith("xpath="));
    }

    /**
     * 所有候选都多匹配 ⇒ 把最终命中数带出（{@code >1}），供生成侧产出 {@code PageElementList}，
     * 而不是丢弃这条拾取（2026-11-11 用户规则③）。
     */
    @Test
    public void allCandidatesAmbiguous_exposesMatchCountForListGeneration() {
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "button", "", "", "",
                "", "", "", "", "Delete", "", -1, "button[class*=\"row-action\"]",
                List.of(), "row-action", "", "", Map.of());

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), css -> 3);

        Assert.assertFalse(s.roleLocatable);
        Assert.assertEquals(3, s.matchCount);
    }

    /**
     * 业务自写的 {@code data-i18n} 值稳定、语义清晰，通常一个属性就能唯一定位——
     * 必须优先于 class token 与结构路径（用户明确要求"能靠属性就用属性"）。
     */
    @Test
    public void dataI18nAttributeIsUsedBeforeClassAndStructure() {
        String expected = "[data-i18n=\"title_username_page\"]";
        LocatorCounter c = css -> css.equals(expected) ? 1 : 9;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("data-i18n", "title_username_page");
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "div", "", "", "",
                "", "", "", "", "", "", -1, "html > body > div:nth-of-type(1)",
                List.of(), "title", "", "", attrs);

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals(expected, s.uniqueCss);
        Assert.assertFalse("data-i18n 应优先于 class/结构路径", s.uniqueCss.contains("nth-of-type"));
    }

    /** 单个 data 属性不唯一时，与 class token 组合（比退化成长结构路径短且稳定得多）。 */
    @Test
    public void dataAttrComboWhenSingleAttrNotUnique() {
        String combo = "div[data-i18n=\"title_username_page\"][class*=\"title\"]";
        LocatorCounter c = css -> css.equals(combo) ? 1 : 6;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("data-i18n", "title_username_page");
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "div", "", "", "",
                "", "", "", "", "", "", -1, "html > body > div:nth-of-type(1)",
                List.of(), "title", "", "", attrs);

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals(combo, s.uniqueCss);
        Assert.assertFalse(s.uniqueCss.contains("nth-of-type"));
    }

    /** 带随机 hash 后缀的 data 属性（如 {@code data-i18n="..._8f14e45f"}）⇒ 退到稳定前缀的部分匹配，仍优于结构路径。 */
    @Test
    public void volatileDataI18nFallsBackToPartialMatch() {
        String expected = "[data-i18n^=\"title_username_page\"]";
        LocatorCounter c = css -> css.equals(expected) ? 1 : 8;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("data-i18n", "title_username_page_8f14e45f");
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "div", "", "", "",
                "", "", "", "", "", "", -1, "html > body > div:nth-of-type(1)",
                List.of(), "title", "", "", attrs);

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals(expected, s.uniqueCss);
    }

    /** 身份类 ARIA（aria-labelledby 指向稳定 id）应独立成定位器，优于 class/结构路径。 */
    @Test
    public void ariaLabelledByUsedAsLocator() {
        String expected = "[aria-labelledby=\"title-id\"]";
        LocatorCounter c = css -> css.equals(expected) ? 1 : 9;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("aria-labelledby", "title-id");
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "div", "", "", "",
                "", "", "", "", "", "", -1, "html > body > div:nth-of-type(1)",
                List.of(), "title", "", "", attrs);

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals(expected, s.uniqueCss);
        Assert.assertFalse(s.uniqueCss.contains("nth-of-type"));
    }

    /** 状态类 ARIA（aria-modal="true"）单独从不唯一 ⇒ 只进组合（[aria-modal="true"][class*="dialog"]），不得作独立定位器。 */
    @Test
    public void ariaModalUsedOnlyInComboNotSolo() {
        String combo = "div[aria-modal=\"true\"][class*=\"dialog\"]";
        LocatorCounter c = css -> css.equals(combo) ? 1 : 6;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("aria-modal", "true");
        CodegenFacts.Fact self = new CodegenFacts.Fact("generic", "", "div", "", "", "",
                "", "", "", "", "", "", -1, "html > body > div:nth-of-type(1)",
                List.of(), "dialog", "", "", attrs);

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals(combo, s.uniqueCss);
        Assert.assertFalse(s.uniqueCss.contains("nth-of-type"));
    }

    /** 注入脚本回传的 {@code attrs} 必须被解析进 Fact（否则 data-i18n 等属性候选无从生成）。 */
    @Test
    public void factsFromJson_parsesDataAttrs() {
        String json = "{"
                + "\"self\":{\"role\":\"generic\",\"tag\":\"div\",\"cssSelector\":\"div:nth-of-type(1)\","
                + "\"attrs\":{\"data-i18n\":\"title_username_page\",\"aria-label\":\"Need help\"}}}";
        com.google.gson.JsonObject root =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();

        CodegenFacts f = CodegenFacts.fromJson(root);

        Assert.assertNotNull(f);
        Assert.assertEquals(2, f.self.attrs.size());
        Assert.assertEquals("title_username_page", f.self.attrs.get("data-i18n"));
        Assert.assertEquals("Need help", f.self.attrs.get("aria-label"));
    }

    /** 唯一性计数恒 >1（含嵌套后）⇒ 达尝试上限后停止，返回最后一条（最长的）尽力而为定位器。 */
    @Test
    public void nestingStopsAtMaxDepth() {
        LocatorCounter alwaysNonUnique = css -> 5;
        CodegenFacts.Fact[] ancestors = new CodegenFacts.Fact[12];
        for (int i = 0; i < ancestors.length; i++) {
            ancestors[i] = fact("generic", "", "s" + i, "", "div", "", "", "#s" + i + " > div:nth-of-type(1)");
        }
        CodegenFacts f = facts(fact("generic", "", "", "", "div", "", "", "div:nth-of-type(1)"), ancestors);

        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, alwaysNonUnique);

        // MAX_NEST_DEPTH + 1：最后一层留给"绝对路径兜底"
        int lastIndex = CodegenScorer.MAX_NEST_DEPTH;
        Assert.assertEquals("#s" + lastIndex + " > div:nth-of-type(1)", s.uniqueCss);
    }

    /** 属性值含双引号 ⇒ 转义，生成合法属性选择器。 */
    @Test
    public void attrSelector_escapesQuotes() {
        Assert.assertEquals("[title=\"a\\\"b\"]", CodegenScorer.attrSelector("title", "a\"b"));
    }

    // ------------------------------------------------------------------
    // 随机属性值 / 部分匹配 / 多语言 / XPath 兜底
    // ------------------------------------------------------------------

    /** 富事实（带 className / xpath / name 属性）。 */
    private static CodegenFacts.Fact rich(String role, String name, String id, String testid, String tag,
                                          String placeholder, String css, String className,
                                          String xpath, String nameAttr) {
        return new CodegenFacts.Fact(role, name, tag, id, testid, "", placeholder, "", "", "",
                "", "", 0, css, List.of(), className, xpath, nameAttr);
    }

    /**
     * 随机 id（{@code mat-input-3}，序号每次构建都变）⇒ 不用精确 {@code #id}，改用稳定前缀 {@code ^=}。
     */
    @Test
    public void volatileId_usesStablePrefixPartialMatch() {
        CodegenFacts.Fact self = rich("textbox", "", "mat-input-3", "", "input", "", "input:nth-of-type(1)",
                "", "", "userName");
        // 只有 [id^="mat-input"] 唯一；精确 id 命中 3 个（多语言表单常见）
        LocatorCounter c = css -> css.equals("[id^=\"mat-input\"]") ? 1 : 3;
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals("[id^=\"mat-input\"]", s.uniqueCss);
        Assert.assertEquals(CodegenScorer.CSS_ID_PARTIAL, s.score);
        Assert.assertFalse("绝不能生成随机值的精确 #id", s.uniqueCss.contains("mat-input-3"));
    }

    /** 随机 testid（{@code a3f9c1d2-submit-btn}）⇒ 用稳定后缀 {@code $=}。 */
    @Test
    public void volatileTestId_usesStableSuffixPartialMatch() {
        CodegenFacts.Fact self = rich("button", "Submit", "", "a3f9c1d2-submit-btn", "button", "",
                "button:nth-of-type(2)", "", "", "");
        LocatorCounter c = css -> css.equals("[data-testid$=\"submit-btn\"]") ? 1 : 4;
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals("[data-testid$=\"submit-btn\"]", s.uniqueCss);
        Assert.assertEquals(CodegenScorer.TESTID_VARIANT, s.score);
    }

    /** 随机值里没有任何语义片段（纯 hash）⇒ 只能用结构 CSS，不能凭空造部分匹配。 */
    @Test
    public void volatileValueWithoutSemanticFragments_fallsBackToStructure() {
        CodegenFacts.Fact self = rich("generic", "", "8f14e45f", "", "div", "", "div:nth-of-type(2)", "", "", "");
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), css -> 1);

        Assert.assertEquals("div:nth-of-type(2)", s.uniqueCss);
        Assert.assertEquals(CodegenScorer.CSS_TAG, s.score);
    }

    /**
     * 多语言环境（{@code textAttributesAllowed=false}）⇒ 禁用 placeholder 这类文案属性，
     * 否则换个语言产物就失效。
     */
    @Test
    public void multilanguage_disablesPlaceholderLocator() {
        CodegenFacts.Fact self = rich("textbox", "", "", "", "input", "User name",
                "input:nth-of-type(1)", "", "", "userName");

        CodegenScorer.ScoredLocator single = CodegenScorer.score(facts(self), css -> 1, true);
        Assert.assertEquals("[placeholder=\"User name\"]", single.uniqueCss);

        CodegenScorer.ScoredLocator multi = CodegenScorer.score(facts(self), css -> 1, false);
        Assert.assertEquals("多语言下必须改用与语言无关的属性",
                "[name=\"userName\"]", multi.uniqueCss);
    }

    /** 结构 CSS 不唯一（且无可用属性）⇒ 用绝对 XPath 兜底（{@code @Element("xpath=/…")} 可直接运行）。 */
    @Test
    public void xpathFallbackWhenStructureCssIsNotUnique() {
        CodegenFacts.Fact self = rich("generic", "", "", "", "div", "", "div:nth-of-type(1)",
                "", "/html/body/div[1]/form[1]/div[2]", "");
        LocatorCounter c = css -> css.startsWith("xpath=") ? 1 : 5;
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals("xpath=/html/body/div[1]/form[1]/div[2]", s.uniqueCss);
        Assert.assertEquals(CodegenScorer.XPATH, s.score);
    }

    /** 稳定 class token 比长结构 CSS 更可读：class 命中唯一时优先。 */
    @Test
    public void stableClassTokenBeatsStructuralCss() {
        CodegenFacts.Fact self = rich("generic", "", "", "", "div", "", "div:nth-of-type(1)",
                "login-form primary", "", "");
        LocatorCounter c = css -> css.contains("login-form") ? 1 : 5;
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals("[class*=\"login-form\"]", s.uniqueCss);
        Assert.assertEquals(CodegenScorer.CSS_CLASS, s.score);
    }

    /** 表单 name 属性（与语言无关）在无 id/testid 时应被采用。 */
    @Test
    public void formNameAttribute_isUsedWhenOtherAttributesAbsent() {
        CodegenFacts.Fact self = rich("textbox", "", "", "", "input", "User name",
                "input:nth-of-type(1)", "", "", "userName");
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), css -> 1, false);

        Assert.assertEquals("[name=\"userName\"]", s.uniqueCss);
    }

    // ------------------------------------------------------------------
    // 计数次数护栏（性能契约）
    // ------------------------------------------------------------------

    /**
     * 单个候选的唯一化只允许查一次。
     *
     * <p>历史实现返回裸 String，调用方只能用 {@code isUnique} 二次计数（fallback 一次 + uniqueCss 一次 +
     * isUnique 一次 = 3 次），在 2k+ 元素页面上每次 count 都要毫秒级，且对最终产物没有任何影响。
     */
    @Test
    public void singleCandidateIsCountedExactlyOnce() {
        CodegenFacts.Fact self = rich("generic", "", "note", "", "span", "", "span:nth-of-type(1)", "", "", "");
        int[] calls = {0};
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), css -> {
            calls[0]++;
            return 1;
        });

        Assert.assertEquals("#note", s.uniqueCss);
        Assert.assertEquals("同一选择器不得被重复计数", 1, calls[0]);
    }

    /** role 胜出时只需为「兜底 CSS」查一次（不得为整个候选链预跑）。 */
    @Test
    public void roleBranchCountsOnlyFallbackOnce() {
        CodegenFacts f = facts(fact("button", "Sign in", "", "", "button", "", "", "button:nth-of-type(1)"));
        int[] calls = {0};
        CodegenScorer.ScoredLocator s = CodegenScorer.score(f, css -> {
            calls[0]++;
            return 1;
        });

        Assert.assertTrue(s.roleLocatable);
        Assert.assertEquals(1, calls[0]);
    }

    /**
     * 候选链必须一次性预热（CDP 实现据此合并成一次协议往返），且预热内容不得重复。
     *
     * <p>预热只影响性能：未预热的选择器仍按需计数，结果语义不变（本用例同时断言产物未变）。
     */
    @Test
    public void candidateChainIsPrefetchedOnce() {
        CodegenFacts.Fact self = rich("generic", "", "", "", "div", "", "div:nth-of-type(1)",
                "login-form primary", "", "");
        List<String> prefetched = new java.util.ArrayList<>();
        LocatorCounter c = new LocatorCounter() {
            @Override
            public int count(String css) {
                return 1;
            }

            @Override
            public void prefetch(java.util.Collection<String> selectors) {
                prefetched.addAll(selectors);
            }
        };

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertFalse("必须下发预热", prefetched.isEmpty());
        Assert.assertEquals("预热不得重复同一选择器",
                prefetched.size(), new java.util.HashSet<>(prefetched).size());
        Assert.assertNotNull(s.uniqueCss);
    }

    /** 候选都不唯一时，同一选择器仍不得被重复计数（嵌套降级各自只查一次）。 */
    @Test
    public void ambiguousCandidatesAreNeverCountedTwice() {
        CodegenFacts.Fact self = rich("generic", "", "", "", "div", "", "div:nth-of-type(1)", "", "", "");
        List<String> counted = new java.util.ArrayList<>();
        CodegenScorer.score(facts(self), css -> {
            counted.add(css);
            return 5;
        });

        Assert.assertEquals("每个（可能嵌套后的）选择器只查一次",
                counted.size(), new java.util.HashSet<>(counted).size());
    }

    // ------------------------------------------------------------------
    // 唯一性判据：0 命中 / 不可判定都不得当作「唯一」
    // ------------------------------------------------------------------

    /**
     * 回归：更优候选 0 命中时必须继续检查后面的候选，不得直接胜出。
     *
     * <p>历史缺陷：判据是 {@code count <= 1}，于是 {@code count == 0} 与「唯一」等价。
     * data-testid 选择器当前不匹配（元素尚未渲染 / testid 写错）时，后面那个真正唯一的
     * {@code #id} 永远不会被采用——产物在回放时 {@code Timeout waiting for locator}。
     */
    @Test
    public void zeroMatchCandidateDoesNotShadowLaterUniqueCandidate() {
        CodegenFacts.Fact self = rich("generic", "", "user", "user-box", "div", "",
                "div:nth-of-type(1)", "", "", "");
        LocatorCounter c = css -> css.equals("[data-testid=\"user-box\"]") ? 0 : 1;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals("#user", s.uniqueCss);
        Assert.assertEquals("必须报出真实命中数，0 不得被当成唯一", 1, s.matchCount);
    }

    /** 回归：整条候选链 0 命中时，不得把 0 报成「唯一」；matchCount 必须如实为 0。 */
    @Test
    public void zeroMatchAcrossWholeChainIsNotReportedAsUnique() {
        CodegenFacts.Fact self = rich("generic", "", "", "", "div", "", "div:nth-of-type(1)", "", "", "");

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), css -> 0);

        Assert.assertFalse(s.roleLocatable);
        Assert.assertEquals(0, s.matchCount);
    }

    /** 回归：计数不可判定（-1）同样不得当作唯一；语义与 0 命中一致，只是原因不同。 */
    @Test
    public void unavailableCountIsNotTreatedAsUnique() {
        CodegenFacts.Fact self = rich("generic", "", "user", "", "div", "", "div:nth-of-type(1)", "", "", "");

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), css -> -1);

        Assert.assertEquals(-1, s.matchCount);
        Assert.assertEquals("#user", s.uniqueCss);   // 降级为兜底候选，但如实报告不可判定
    }

    /** 0 命中不得遮蔽「多命中」候选：兜底应选中真正有命中的那一个。 */
    @Test
    public void zeroMatchCandidateFallsBackToCandidateWithMatches() {
        CodegenFacts.Fact self = rich("generic", "", "user", "user-box", "div", "",
                "div:nth-of-type(1)", "", "", "");
        LocatorCounter c = css -> css.equals("[data-testid=\"user-box\"]") ? 0 : 5;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), c);

        Assert.assertEquals("#user", s.uniqueCss);
        Assert.assertEquals(5, s.matchCount);
    }

    // ------------------------------------------------------------------
    // 评审 P1-5 / 一致性 b：:has() 证据分级 + 候选顺序确定性
    // ------------------------------------------------------------------

    /**
     * 能标识祖先的头（稳定 id）必须先于裸 tag 头尝试：后者是一次全页后代搜索。
     * 两者都能收敛时，产物必须由强证据胜出，而不是"JSON 里排在前面的那个"。
     */
    @Test
    public void identifyingScopeHeadIsTriedBeforeBareTagHead() {
        CodegenFacts.Fact self = selfFact("span", "span:nth-of-type(1)", Map.of());
        CodegenFacts.Fact weak = ancestor("div", "", "", List.of("[data-i18n=\"weak\"]"));
        CodegenFacts.Fact strong = ancestor("section", "panel2", "",
                List.of("[data-i18n=\"strong\"]"));
        String strongCss = "#panel2:has(> [data-i18n=\"strong\"]) span:nth-of-type(1)";
        LocatorCounter counter = css -> css.equals(strongCss) ? 1 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self, weak, strong), counter);

        Assert.assertEquals(strongCss, s.uniqueCss);
        Assert.assertEquals(1, s.matchCount);
    }

    /**
     * 评审一致性 a：{@code role="dialog"} 虽非 {@code aria-*}，但作为组合条件与状态类同构，
     * <b>有意允许</b>进组合。防止后人因 {@code ARIA_STATE_ONLY} 的名字误把它排除掉。
     */
    @Test
    public void roleAttributeIsAllowedAsACombinationCondition() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("role", "dialog");
        attrs.put("data-qa", "confirm");
        CodegenFacts.Fact self = selfFact("div", "div:nth-of-type(1)", attrs);
        LocatorCounter counter = css -> css.contains("[role=\"dialog\"]")
                && css.contains("[data-qa=\"confirm\"]") ? 1 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertTrue("role 必须留在组合里", s.uniqueCss.contains("[role=\"dialog\"]"));
        Assert.assertTrue(s.uniqueCss.contains("[data-qa=\"confirm\"]"));
        Assert.assertEquals(1, s.matchCount);
    }

    /**
     * 等价语义属性同分时，胜出者不得随注入脚本 JSON 的键序漂移。
     * 不排序时下面两种插入顺序会分别胜出 {@code data-z} 与 {@code data-a}。
     */
    @Test
    public void equalScoreAttributesAreDeterministicAcrossJsonKeyOrder() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("data-z", "9");
        first.put("data-a", "1");
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("data-a", "1");
        reversed.put("data-z", "9");

        CodegenScorer.ScoredLocator s1 = CodegenScorer.score(
                facts(selfFact("span", "span:nth-of-type(1)", first)), css -> 1);
        CodegenScorer.ScoredLocator s2 = CodegenScorer.score(
                facts(selfFact("span", "span:nth-of-type(1)", reversed)), css -> 1);

        Assert.assertEquals("产物不得随 JSON 键序变化", s1.uniqueCss, s2.uniqueCss);
        Assert.assertEquals("按属性名排序 ⇒ data-a 胜出", "[data-a=\"1\"]", s1.uniqueCss);
    }

    // ------------------------------------------------------------------
    // 非法 CSS 护栏（与浏览器侧 codegen-inject.js#validCssSel 同源）
    // ------------------------------------------------------------------

    /**
     * 回归护栏：把两个属性塞进同一个方括号（缺 {@code ][}，非法 CSS）必须被识别为畸形，
     * 不得在打分产物里出现。典型即 {@code [data-i18n="x" data-i18n="x"]}——运行期
     * {@code querySelectorAll} 抛 {@code SyntaxError} 或零匹配，表现为"未定位到"。
     */
    @Test
    public void malformedDoubleAttrInSingleBracket_isDetected() {
        Assert.assertTrue("单括号双属性必须判为畸形",
                CodegenScorer.isMalformedCssSelector(
                        "[data-i18n=\"forgot_username_button\" data-i18n=\"forgot_username_button\"]"));
        Assert.assertTrue("不同属性同括号也必须判为畸形",
                CodegenScorer.isMalformedCssSelector("[data-x=\"a\" data-y=\"b\"]"));
    }

    /**
     * 合法选择器不得被护栏误伤：{@code ][} 复合、引号内含空格、后代组合、{@code #id}、{@code :has()} 全部放行。
     */
    @Test
    public void validSelectorsAreNotFlagged() {
        Assert.assertFalse(CodegenScorer.isMalformedCssSelector("button:has(span[data-i18n=\"x\"])"));
        Assert.assertFalse(CodegenScorer.isMalformedCssSelector("[data-i18n=\"x\"][data-i18n=\"x\"]"));
        Assert.assertFalse(CodegenScorer.isMalformedCssSelector("[class*=\"btn primary\"]"));
        Assert.assertFalse(CodegenScorer.isMalformedCssSelector("div[class*=\"a\"] span[class*=\"b\"]"));
        Assert.assertFalse(CodegenScorer.isMalformedCssSelector("#loginBtn"));
        Assert.assertFalse(CodegenScorer.isMalformedCssSelector(""));
        Assert.assertFalse(CodegenScorer.isMalformedCssSelector(null));
    }

    /**
     * 含畸形 {@code cssSelector} 的祖先不得参与选优（阶段 2 直接跳过），更不会成为最终产物。
     */
    @Test
    public void malformedAncestorCssSelector_isSkipped() {
        CodegenFacts.Fact self = rich("generic", "", "", "user-box", "div", "", "div:nth-of-type(1)", "", "", "");
        CodegenFacts.Fact badAncestor = ancestor("div", "", "",
                List.of("[data-i18n=\"x\" data-i18n=\"x\"]"));
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self, badAncestor), css -> 1);

        Assert.assertFalse("畸形祖先选择器不得进入产物", s.uniqueCss.contains("data-i18n=\"x\""));
    }

    /**
     * 含畸形 {@code cssSelector} 的目标自身不得成为 CSS 候选（阶段 6 直接不加入），
     * 退化到角色 / XPath 兜底而非产出畸形 {@code @Element(...)}。
     */
    @Test
    public void malformedSelfCssSelector_isExcludedFromCandidates() {
        CodegenFacts.Fact self = selfFact("div", "[data-i18n=\"x\" data-i18n=\"x\"]", new LinkedHashMap<>());
        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), css -> 1);

        Assert.assertFalse("畸形自身选择器不得成为产物",
                s.uniqueCss != null && s.uniqueCss.contains("data-i18n=\"x\""));
    }

    // ------------------------------------------------------------------
    // 子代锚点短形式 tag:has(后代锚点)（2026-11-14）
    // ------------------------------------------------------------------

    /**
     * 真实场景（HSBC logon 的 Forgot-username overlay）：目标 `<button>` 自身没有任何稳定属性，
     * 内部却有唯一的业务锚点 `<span data-i18n="forgot_username_button">`；而结构链按 div 序号拼出的
     * `… > div:nth-of-type(3) button` 在当前 DOM（`.button-panel` 的直接子元素是 button）命中 0。
     *
     * <p>期望产物：{@code button:has([data-i18n="forgot_username_button"])}——零结构层级、跨语言稳定，
     * 而不是那条回放必然等不到元素的死链。
     */
    @Test
    public void deadStructureChain_losesToSelfHasDescendantAnchor() {
        String deadChain = "div:nth-of-type(1) div[class*=\"button-panel\"] > div:nth-of-type(3) button";
        String shortForm = "button:has([data-i18n=\"forgot_username_button\"])";
        CodegenFacts.Fact self = selfWithAnchors("button", deadChain,
                List.of("[data-i18n=\"forgot_username_button\"]"));
        LocatorCounter counter = css -> {
            if (css.equals(shortForm)) return 1;
            if (css.contains("button-panel")) return 0;   // 死链：当前一个都匹配不到
            return 3;
        };

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertEquals(shortForm, s.uniqueCss);
        Assert.assertEquals("短形式按单元素处理", 1, s.matchCount);
        Assert.assertEquals(CodegenScorer.CSS_DESC_ANCHOR, s.score);
        Assert.assertEquals(LocatorStrategy.CSS, s.strategy);
        Assert.assertFalse("产物不得保留 0 命中的结构链", s.uniqueCss.contains("button-panel"));
    }

    /**
     * 短形式必须如实回报命中数：后代锚点并非唯一（页面上多个按钮都含该锚点）时，
     * 它仍可作为「比结构链更窄」的尽力而为候选胜出，但 {@code matchCount} 必须是真实值
     * （下游据此生成 {@code PageElementList}），绝不能冒充唯一。
     */
    @Test
    public void selfHasAnchor_notUnique_keepsRealCountInsteadOfClaimingUnique() {
        String shortForm = "button:has([data-i18n=\"close\"])";
        CodegenFacts.Fact self = selfWithAnchors("button", "button:nth-of-type(2)", List.of("[data-i18n=\"close\"]"));
        LocatorCounter counter = css -> css.equals(shortForm) ? 2 : 3;

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertEquals("2 命中比 3 命中的结构链更窄，可作为尽力而为候选胜出", shortForm, s.uniqueCss);
        Assert.assertEquals("必须如实回报 2 命中（下游生成 PageElementList）", 2, s.matchCount);
    }

    /**
     * 锚点含非法字符 / 标签名非法时一律不得拼进选择器（注入纵深防御，与祖先锚点同源白名单）。
     */
    @Test
    public void invalidSelfAnchorOrTag_isIgnored() {
        CodegenFacts.Fact badAnchor = selfWithAnchors("button", "button:nth-of-type(1)",
                List.of("[data-i18n=\"a\\\"]\"]"));
        Assert.assertFalse("非法锚点不得拼进产物",
                CodegenScorer.score(facts(badAnchor), css -> 2).uniqueCss.contains(":has("));

        CodegenFacts.Fact badTag = selfWithAnchors("but ton>", "button:nth-of-type(1)",
                List.of("[data-i18n=\"x\"]"));
        Assert.assertFalse("非法标签名不得拼进产物",
                CodegenScorer.score(facts(badTag), css -> 2).uniqueCss.contains(":has("));
    }

    // ------------------------------------------------------------------
    // 图片 src 的语言变体（2026-11-14）
    // ------------------------------------------------------------------

    /**
     * HSBC 图片按语言分文件（{@code img_username_EN.png} / {@code _ZH.png} / {@code _TC.png}）。
     * 用 {@code [src$="img_username_EN.png"]} 定位，用户一切语言整条就失效（回放等不到元素）。
     * 期望改为锚定语言段之前的前缀：{@code img[src*="img_username"]}。
     */
    @Test
    public void localizedImgSrc_dropsLanguageSuffix() {
        CodegenFacts.Fact self = selfWithSrc("img", "div:nth-of-type(1) img",
                "/portalserver/hsbc/content/commercial/online/gbb/pre-logon/img_username_EN.png");
        String expected = "img[src*=\"img_username\"]";
        LocatorCounter counter = css -> {
            if (css.equals(expected)) return 1;
            if (css.contains("img_username_EN.png")) return 1;   // 拾取当时 EN 确实唯一
            return 3;
        };

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), counter);

        Assert.assertEquals(expected, s.uniqueCss);
        Assert.assertEquals(CodegenScorer.SRC_TAIL, s.score);
        Assert.assertFalse("产物不得绑定语言后缀", s.uniqueCss.contains("_EN"));
    }

    /** 无语言尾标的文件名仍走精确 {@code $=}（不要为了"通用"把强定位降级成宽包含）。 */
    @Test
    public void plainImgSrc_keepsExactSuffix() {
        CodegenFacts.Fact self = selfWithSrc("img", "img", "/static/brand/hsbc_badge.png");

        CodegenScorer.ScoredLocator s = CodegenScorer.score(facts(self), css -> 1);

        Assert.assertEquals("img[src$=\"hsbc_badge.png\"]", s.uniqueCss);
    }

    /** 语言段识别：只吃<b>最后一个</b>语言段；前缀过短 / 无语言段时不动。 */
    @Test
    public void localeNeutralPrefix_detection() {
        Assert.assertEquals("img_username",
                CodegenScorer.localeNeutralSrcPrefix("img_username_EN.png"));
        Assert.assertEquals("banner",
                CodegenScorer.localeNeutralSrcPrefix("banner_zh-HK.png"));
        Assert.assertEquals("Search_HSBC_HK_Business_Express",
                CodegenScorer.localeNeutralSrcPrefix("Search_HSBC_HK_Business_Express_EN.png"));
        Assert.assertEquals("logo",
                CodegenScorer.localeNeutralSrcPrefix("logo.en_US.svg"));

        Assert.assertNull("无语言段：保持精确匹配",
                CodegenScorer.localeNeutralSrcPrefix("hsbc_badge.png"));
        Assert.assertNull("前缀过短不归一化（*= 过宽）",
                CodegenScorer.localeNeutralSrcPrefix("seo_EN.png"));
        Assert.assertNull(CodegenScorer.localeNeutralSrcPrefix(null));
        Assert.assertNull(CodegenScorer.localeNeutralSrcPrefix("  "));
    }
}

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

    private static CodegenFacts facts(CodegenFacts.Fact self, CodegenFacts.Fact... ancestors) {
        return new CodegenFacts(self, List.of(ancestors));
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
}

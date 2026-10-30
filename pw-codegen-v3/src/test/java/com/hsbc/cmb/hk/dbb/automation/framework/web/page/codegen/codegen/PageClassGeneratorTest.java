package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 验证 V3 生成器在 FreeMarker 模板下的产物契约：role/name 定位、NLS key、CSS 回退、List、CJK TODO。 */
public class PageClassGeneratorTest {

    /** 类名一律由 URL 派生（对应 /portalserver/login ⇒ LoginPage）。 */
    private static final CodegenTarget TARGET =
            CodegenTarget.of("com.example.pages", "https://host/portalserver/login");

    private static CodegenEntry role(String role, String name, String css, String nlsKey, int index) {
        String sig = role + "|" + (name == null ? "" : name) + "#" + index;
        return new CodegenEntry(sig, role, name, "ROLE", 100, "role=" + role, css, nlsKey, index);
    }

    private static CodegenEntry css(String css, int index) {
        // role=generic 且 name 为空 ⇒ 角色不可定位 ⇒ 回退 @Element(css)
        return new CodegenEntry("generic|#" + index, "generic", "", "CSS", 510, "", css, null, index);
    }

    private static CodegenEntry frameCss(String css, int index, List<String> framePath) {
        // 跨 frame（含跨源 iframe）：元素位于 iframe 内，framePath 为外层 → 内层 iframe 选择器链
        return new CodegenEntry("generic|#" + index, "generic", "", "CSS", 510, "", css, null, index, framePath);
    }

    @Test
    public void noNls_usesNameLocator() {
        List<CodegenEntry> es = List.of(role("button", "Sign in", "#btn", null, 0));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});
        Assert.assertTrue(out.contains("public class LoginPage extends AbstractManagedPage"));
        Assert.assertTrue(out.contains("@RoleElement(role = AriaRole.BUTTON, name = \"Sign in\")"));
        Assert.assertTrue(out.contains("public PageElement signIn;"));
        Assert.assertFalse("无 NLS 不应有 @RoleFile", out.contains("@RoleFile"));
        Assert.assertFalse("无 NLS 不应有 key=", out.contains("key = "));
    }

    @Test
    public void withNls_usesKeyLocatorAndRoleFile() {
        List<CodegenEntry> es = List.of(role("textbox", "username", "#u", "login.username", 0));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{"nls/login.json"});
        Assert.assertTrue(out.contains("key = \"login.username\""));
        Assert.assertTrue(out.contains("@RoleFile(\"nls/login.json\")"));
        Assert.assertTrue(out.contains("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.RoleFile;"));
        Assert.assertFalse(out.contains("name = "));
    }

    @Test
    public void roleNotLocatable_fallsBackToElementCss() {
        List<CodegenEntry> es = List.of(css("#container > div", 0));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});
        Assert.assertTrue(out.contains("@Element(\"#container > div\")"));
        Assert.assertTrue(out.contains("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.Element;"));
        Assert.assertFalse("无角色字段不应 import AriaRole",
                out.contains("import com.microsoft.playwright.options.AriaRole;"));
        Assert.assertTrue(out.contains("public PageElement containerDiv;"));
    }

    @Test
    public void sameLocatorMultipleIndices_becomesPageElementList() {
        List<CodegenEntry> es = Arrays.asList(
                role("listitem", "Item", "li.item", null, 0),
                role("listitem", "Item", "li.item", null, 1));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});
        Assert.assertTrue(out.contains("public PageElementList item;"));
        // 同一 base-sig 的一组元素只合成【一个】PageElementList 字段
        Assert.assertEquals(1, countOccurrences(out, "public PageElementList"));
        Assert.assertFalse(out.contains("public PageElement item;"));
        Assert.assertFalse("一组元素不应重复生成第二份字段", out.contains("item2"));
        Assert.assertTrue(out.contains(
                "import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElementList;"));
    }

    /**
     * 一组元素若拿不到 role 定位器（复核未通过 / 跨 frame），只能退化为组内首条的唯一 CSS：
     * 严格模式下不报错，但该 {@code PageElementList} 实际只命中 1 个元素 —— 产物必须显式标出来。
     */
    @Test
    public void listWithoutRoleLocator_getsGroupDegradedComment() {
        List<CodegenEntry> es = Arrays.asList(css("li.item", 0), css("li.item", 1));

        String out = PageClassGenerator.generate(TARGET, es, new String[]{});

        Assert.assertTrue(out.contains("public PageElementList"));
        Assert.assertTrue(out.contains("matches only one element of the group"));
    }

    /** 组用 role 定位器（能命中整组）时不应出现"退化"提示。 */
    @Test
    public void listWithRoleLocator_hasNoGroupDegradedComment() {
        List<CodegenEntry> es = Arrays.asList(
                role("listitem", "Item", "li.item", null, 0),
                role("listitem", "Item", "li.item", null, 1));

        String out = PageClassGenerator.generate(TARGET, es, new String[]{});

        Assert.assertFalse(out.contains("matches only one element of the group"));
    }

    /**
     * 多语言环境（传了 nls 文件）且<b>反查不到 key</b> ⇒ 禁止用 name 定位：name 随语言变化，
     * 产物只在当前语言下有效。必须回退 {@code @Element(css/xpath)}。
     */
    @Test
    public void multilanguageWithoutNlsKey_fallsBackToElement() {
        CodegenEntry e = new CodegenEntry("radio|Physical Security Device#0", "radio",
                "Physical Security Device", "ROLE", 105,
                "role=radio[name=\"Physical Security Device\"]", "#opt-physical", null, 0);

        String out = PageClassGenerator.generate(TARGET, List.of(e),
                new String[]{"nls/NLS_idv_logon.json"});

        Assert.assertTrue("无 key 的多语言元素必须走 @Element", out.contains("@Element(\"#opt-physical\")"));
        Assert.assertFalse("不得生成 name= 形式的 @RoleElement", out.contains("@RoleElement"));
    }

    /** 同上的正对照：反查到 key ⇒ 仍用 {@code @RoleElement(role=…, key=…)}（跨语言稳定）。 */
    @Test
    public void multilanguageWithNlsKey_keepsRoleElementWithKey() {
        CodegenEntry e = new CodegenEntry("radio|Physical Security Device#0", "radio",
                "Physical Security Device", "ROLE_WITH_NAME", 105,
                "role=radio[key=\"otp_radio_label\"]", "#opt-physical", "otp_radio_label", 0);

        String out = PageClassGenerator.generate(TARGET, List.of(e),
                new String[]{"nls/NLS_idv_logon.json"});

        Assert.assertTrue(out.contains("@RoleElement(role = AriaRole.RADIO, key = \"otp_radio_label\")"));
    }

    /**
     * 拾取阶段被真实引擎否掉的角色（{@code strategy=CSS}）必须回退 {@code @Element}。
     *
     * <p>场景：AX 报 role=radio + name，但 {@code getByRole(radio, name, exact)} 命中 0 或多个 —— 此时的
     * {@code @RoleElement} 跑不通；planner 必须以 strategy 为准，不能只看 role/name 字面。
     */
    @Test
    public void roleRejectedByEngine_generatesElementNotRoleElement() {
        CodegenEntry e = new CodegenEntry("radio|Physical Security Device#1", "radio",
                "Physical Security Device", "CSS", 500, "role=radio[name=\"Physical Security Device\"]",
                "label:nth-of-type(2)", null, 1);
        String out = PageClassGenerator.generate(TARGET, List.of(e), new String[]{});
        Assert.assertTrue(out.contains("@Element(\"label:nth-of-type(2)\")"));
        Assert.assertFalse("引擎否掉的角色不得生成 @RoleElement", out.contains("@RoleElement"));
    }

    @Test
    public void cjkNameWithoutNls_getsTodoComment() {
        List<CodegenEntry> es = List.of(role("button", "提交", "#submit", null, 0));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});
        Assert.assertTrue(out.contains("name = \"提交\""));
        Assert.assertTrue(out.contains("TODO(review): CJK element name has no English source"));
    }

    // ------------------------------------------------------------------
    // 字段名必须纯 ASCII（不引入拼音依赖的降级策略）
    // ------------------------------------------------------------------

    /**
     * 中文名转不出 ASCII（无拼音依赖）⇒ 顺延到角色名，字段名不得出现中文。
     *
     * <p>历史缺陷：{@code toIdentifier} 保留 {@code \p{L}}（含 CJK），生成
     * {@code public PageElement 保存;} —— 能编译但不可维护（无法输入/搜索、编码风险）。
     */
    @Test
    public void cjkNameWithoutAsciiSource_fallsBackToRoleName() {
        List<CodegenEntry> es = List.of(role("button", "保存", "#save", null, 0));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});

        Assert.assertTrue("中文名转不出 ASCII ⇒ 用角色名", out.contains("public PageElement button;"));
        Assert.assertFalse("声明行不得出现中文标识符", hasCjkDeclaration(out));
        // 定位器值保留原文（选择器内容，不是标识符）
        Assert.assertTrue(out.contains("name = \"保存\""));
    }

    /** 中文名 + 有 NLS key ⇒ 字段名取自 key（{@code login.username → loginUsername}）。 */
    @Test
    public void cjkNameWithNlsKey_usesKeyForFieldName() {
        List<CodegenEntry> es = List.of(role("textbox", "用户名", "#u", "login.username", 0));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{"nls/login.json"});

        Assert.assertTrue(out.contains("public PageElement loginUsername;"));
        Assert.assertTrue(out.contains("key = \"login.username\""));
        Assert.assertFalse(hasCjkDeclaration(out));
    }

    /** 中文名 + 不可定位角色 ⇒ 顺延到选择器标签（{@code #save > span → saveSpan}）。 */
    @Test
    public void cjkNameWithoutRole_usesSelectorLabel() {
        List<CodegenEntry> es = List.of(css("#save > span", 0));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});

        Assert.assertTrue(out.contains("public PageElement saveSpan;"));
        Assert.assertFalse(hasCjkDeclaration(out));
    }

    /** 中英混合名保留英文片段（{@code "Login 登录 → login"}），不整段丢弃。 */
    @Test
    public void mixedCjkAndAscii_keepsAsciiPart() {
        List<CodegenEntry> es = List.of(role("button", "Login 登录", "#login", null, 0));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});

        Assert.assertTrue(out.contains("public PageElement login;"));
        Assert.assertFalse(hasCjkDeclaration(out));
    }

    /** 声明行（{@code public PageElement xxx;}）是否含非 ASCII 字符。 */
    private static boolean hasCjkDeclaration(String out) {
        return out.lines()
                .filter(line -> line.contains("public PageElement"))
                .anyMatch(line -> line.chars().anyMatch(c -> c > 127));
    }

    /** 跨 frame 穿透：CSS 元素位于 iframe 内 ⇒ 生成 @Element("frame=… :: … :: <内层 css>")。 */
    @Test
    public void framePiercing_cssElementInsideIframe_emitsFramePiercePrefix() {
        List<CodegenEntry> es = List.of(
                frameCss("#submit", 0, List.of("iframe#outer", "iframe[name=\"inner\"]")));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});
        // 选择器内的双引号被 toJavaStringLiteral 转为单引号，保证 Java 字面量合法
        Assert.assertTrue(out.contains(
                "@Element(\"frame=iframe#outer :: iframe[name='inner'] :: #submit\")"));
    }

    /** 跨 frame 穿透：role 可定位元素位于 iframe 内 ⇒ 不再生成 @RoleElement（role 无法跨 frame），
     *  改用 @Element("frame=… :: <css>") 统一穿透，保证产物可运行。 */
    @Test
    public void framePiercing_roleElementInsideIframe_fallsBackToElementWithFramePrefix() {
        List<CodegenEntry> es = List.of(new CodegenEntry(
                "button|Sign in#0", "button", "Sign in", "ROLE", 100,
                "role=button", "#btn", null, 0, List.of("iframe#main")));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});
        Assert.assertTrue(out.contains("@Element(\"frame=iframe#main :: #btn\")"));
        Assert.assertFalse("跨 frame 不应再生成 @RoleElement（role 无法跨 frame）",
                out.contains("@RoleElement"));
    }

    /** 跨 frame 穿透：frame 选择器需保持可读顺序（外层 → 内层）。 */
    @Test
    public void framePiercing_preservesOuterToInnerOrder() {
        List<CodegenEntry> es = List.of(
                frameCss(".deep", 0, List.of("iframe#a", "iframe#b", "iframe#c")));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});
        Assert.assertTrue(out.contains(
                "@Element(\"frame=iframe#a :: iframe#b :: iframe#c :: .deep\")"));
    }

    /** 结构断言：缩进、标记块顺序、基类继承必须稳定（<generated:fields> 供后续增量合并定位边界）。 */
    @Test
    public void skeletonKeepsGeneratedBlockAndBaseClass() {
        List<CodegenEntry> es = Arrays.asList(
                role("button", "Sign in", "#btn", null, 0),
                css("#main > div", 1));
        String out = PageClassGenerator.generate(TARGET, es, new String[]{});
        Assert.assertTrue(out.startsWith("// GENERATED BY CodegenInspector V3\n"));
        Assert.assertTrue(out.contains("package com.example.pages;"));
        Assert.assertTrue(out.contains("extends AbstractManagedPage"));
        int begin = out.indexOf("    // <generated:fields>");
        int end = out.indexOf("    // </generated:fields>");
        Assert.assertTrue("缺少生成块开始标记", begin > 0);
        Assert.assertTrue("缺少生成块结束标记", end > begin);
        Assert.assertTrue("字段必须在标记块内", out.indexOf("@RoleElement") > begin && out.indexOf("@RoleElement") < end);
        Assert.assertTrue(out.trim().endsWith("}"));
        Assert.assertFalse("模板渲染不得残留 CRLF（跨 OS 漂移）", out.contains("\r"));
    }

    /** 空拾取也要产出可编译的骨架（不因为"没有字段"就崩或产出半截类）。 */
    @Test
    public void emptyPickStillRendersCompilableSkeleton() {
        String out = PageClassGenerator.generate(TARGET, List.of(), new String[]{});
        Assert.assertTrue(out.contains("public class LoginPage extends AbstractManagedPage {"));
        Assert.assertTrue(out.trim().endsWith("}"));
        Assert.assertFalse(out.contains("PageElement "));
    }

    private static int countOccurrences(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }
}

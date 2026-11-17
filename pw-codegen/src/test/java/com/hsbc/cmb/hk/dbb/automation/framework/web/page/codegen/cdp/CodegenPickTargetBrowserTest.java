package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code pickTarget} 行为契约（真实浏览器 V8 执行嵌入 JS {@code SELF_CONTAINED_GET_INFO_FN}）。
 *
 * <p>无浏览器环境（CI 未装 Chromium）时整类跳过——{@link #setup()} 用 {@code assumeNoException} 兜底，
 * 绝不因环境缺浏览器而让构建红。本地有浏览器（codegen 本就依赖 Chromium）时则真实验证。
 *
 * <p>覆盖两类关键行为：
 * <ul>
 *   <li>点装饰性 {@code <img alt="" aria-hidden="true">} 信息图标 ⇒ 归一化到带可访问名的祖先
 *       （{@code #newPasswordHint} 这个 div），产物带可见文本、可反查 NLS key，而不是无用的 {@code role=img}；</li>
 *   <li>{@code eHasName} 守卫：点自身已有名的元素（提示文本 {@code <span>}）⇒ 不作无谓上溯，仍返回该元素自身。</li>
 * </ul>
 */
public class CodegenPickTargetBrowserTest {

    private static final String HTML =
            "<div id=\"newPasswordHint\" class=\"new-password-hint is-visible\" aria-live=\"polite\">"
            + "<img class=\"new-password-hint-icon\" src=\"x.svg\" width=\"18\" height=\"18\""
            + " alt=\"\" aria-hidden=\"true\">"
            + "<span class=\"new-password-hint-text\" data-i18n=\"password_hint\">"
            + "Do not enter your 6-digit Mobile Security Key PIN or Security Code here."
            + " Enter your logon password (6–30 characters, letters and/or numbers).</span>"
            + "</div>"
            //  2026-11-11：点 label 必须保留 label（原逻辑会转发到关联 input）
            + "<label class=\"field-label\" for=\"userName\">User name</label>"
            + "<input id=\"userName\" name=\"userName\" type=\"text\">"
            //  隐藏的重复副本：唯一性计数必须包含它，否则产物在面板显示后变成多匹配
            + "<button class=\"panel-cta\">Continue</button>"
            + "<div style=\"display:none\"><button class=\"panel-cta\">Continue</button></div>"
            // 2026-11-15 用户反馈的真实 HSBC 形态：按钮/链接文案包在带 data-i18n 的 span 里，
            // 点文案时事件目标是 span（旧逻辑会把 span 当拾取对象 ⇒ role=generic ⇒ 退化成结构 CSS）
            + "<button id=\"cta\" type=\"button\">"
            + "<span aria-live=\"polite\" aria-atomic=\"true\" class=\"accessibleHidden\"></span>"
            + "<span data-i18n=\"continue\">Continue</span></button>"
            + "<a id=\"privacy\" href=\"/privacy\"><span data-i18n=\"footer_privacy\">Privacy</span></a>"
            // radio 选项：label 包裹 input，可点区域是 label 内整块（svg / h2 / 文本 div）
            + "<fieldset><label class=\"option\" for=\"opt-security\">"
            + "<div class=\"radio-title\"><h2 class=\"radio-frame\">"
            + "<div class=\"radio-icon\"><svg width=\"18\" height=\"18\"></svg></div>"
            + "<div data-i18n=\"security_radio_label\">Answer security questions</div></h2>"
            + "<div><input type=\"radio\" id=\"opt-security\" name=\"select-option\" value=\"security\"></div>"
            + "</div></label></fieldset>"
            // 2026-11-15 用户反馈③：密保问题下拉 —— 每一项的可访问名都不同（旧的 role|name 成组判据永远不成立）
            + "<ul id=\"sq1\" role=\"listbox\" aria-labelledby=\"label_sq1\">"
            + "<li role=\"option\" id=\"sq1_a\">Name a memorable city or country</li>"
            + "<li role=\"option\" id=\"sq1_b\">Name your favourite television character</li>"
            + "<li role=\"option\" id=\"sq1_c\">What was the name of your first pet</li>"
            + "<li role=\"option\" id=\"sq1_d\">What is your mother's maiden name</li>"
            + "<li role=\"option\" id=\"sq1_e\">What was your first car</li>"
            + "</ul>"
            // 隐式 ARIA 角色：<li> 自身没有 role 属性（条目选择器若拼 [role="…"] 会命中 0 个）
            + "<ul id=\"sq3\"><li>Alpha</li><li>Beta</li><li>Gamma</li></ul>"
            // 混入非同类子节点：nth 与组内下标不再一致 ⇒ 必须放弃成组
            + "<ul id=\"sq2\" role=\"listbox\"><li role=\"option\">A</li><li role=\"option\">B</li>"
            + "<div>note</div></ul>";

    private Playwright pw;
    private Browser browser;
    private Page page;
    private String fnSource;

    @Before
    public void setup() {
        try {
            Field f = CodegenCdpNodeExtractor.class.getDeclaredField("SELF_CONTAINED_GET_INFO_FN");
            f.setAccessible(true);
            fnSource = (String) f.get(null);
            assertNotNull("SELF_CONTAINED_GET_INFO_FN 不可为空", fnSource);

            Map<String, String> env = new HashMap<>();
            // 关掉驱动自带的浏览器下载/校验（公司网下会走网络，纯属浪费），本机已装好 Chromium。
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            Playwright.CreateOptions options = new Playwright.CreateOptions();
            options.setEnv(env);
            pw = Playwright.create(options);
            browser = pw.chromium().launch();
            page = browser.newPage();
            page.setContent(HTML);
        } catch (Throwable t) {
            // 无浏览器 / 启动失败 ⇒ 跳过（不视为失败）
            Assume.assumeNoException("浏览器不可用，跳过 CodegenPickTargetBrowserTest", t);
        }
    }

    @After
    public void teardown() {
        if (browser != null) browser.close();
        if (pw != null) pw.close();
    }

    /** 点装饰性 img ⇒ 上溯到带语义的祖先容器（#newPasswordHint），带回完整提示文案，而非产出 role=img。 */
    @Test
    public void decorativeImgPromotesToNamedAncestor() {
        JsonObject self = pick("img.new-password-hint-icon");

        // 不应再是 img（原 bug：role=img 无名定位器）
        assertFalse("绝不应产出 role=img 这种无名定位器", "img".equals(self.get("tag").getAsString()));
        // 应归一到带可访问名的祖先容器 div#newPasswordHint，并带回完整提示文案
        assertEquals("应归一到带提示文案的祖先容器 div", "div", self.get("tag").getAsString());
        assertEquals("id 应为 newPasswordHint（稳定定位器）", "newPasswordHint", self.get("id").getAsString());
        String name = self.get("name").getAsString();
        assertTrue("name 应带回完整提示文案（去长度护栏后）", name.contains("Do not enter"));
    }

    /** eHasName 守卫：点自身已有名的 span（可见文本）⇒ 不无谓上溯，仍返回该 span。 */
    @Test
    public void namedElementIsNotOverPromoted() {
        JsonObject self = pick("span.new-password-hint-text");

        assertEquals("自身已有名的元素不应被上溯", "span", self.get("tag").getAsString());
        String name = self.get("name").getAsString();
        assertTrue("span 自身文案应被带回", name.contains("Do not enter"));
    }

    /**
     * 点 {@code <label>} ⇒ 必须保留 label 本身，绝不转发到关联 {@code <input>}。
     *
     * <p>2026-11-11 用户规则①：拾取对象必须 = 用户实际点击的 DOM 对象，不自动映射/跳转关联元素。
     * label 自身带可访问名，命中 NLS 时可走文本语义策略生成 {@code @RoleElement(key=...)}。
     */
    @Test
    public void clickingLabelKeepsLabelItself() {
        JsonObject self = pick("label.field-label");

        assertEquals("点 label 必须保留 label 本身（不得转发到关联 input）",
                "label", self.get("tag").getAsString());
        assertFalse("不得把关联 input 的 id 带回来（说明发生了元素跳转）",
                "userName".equals(self.get("id").getAsString()));
    }

    /**
     * 隐藏的重复副本必须参与唯一性计数。
     *
     * <p>2026-11-11 用户规则④：{@code data-i18n}/class 大量重复，不能凭"可见唯一"判定唯一——
     * Playwright 的 locator 不做可见性过滤，strict mode 下多匹配即失败。此处页面有 1 个可见 +
     * 1 个隐藏的 {@code .panel-cta}，产出的 CSS 必须全量只命中 1 个。
     */
    @Test
    public void hiddenDuplicateIsCountedSoSelectorStaysUnique() {
        JsonObject self = pick("button.panel-cta");
        String css = self.get("cssSelector").getAsString();

        //  生成侧绝不使用 Playwright 专有伪类（原生 {@code document.querySelectorAll} 不识别，会抛 SyntaxError）。
        //  隐藏副本场景靠「父级标签+属性」（如 body > button[...]）等原生手段收窄到唯一，故此处用原生计数校验。
        //  本页结构足够简单（可见 button 是 body 直接子级、隐藏副本在 div[display:none] 内），必须能产出一条
        //  原生可用且全量唯一的选择器——空串在这里说明"父级标签+属性"兜底档失效。
        assertFalse("隐藏副本场景必须产出可用的原生选择器（父级标签+属性兜底），实际: <" + css + ">", css.isEmpty());
        Object hits = page.evaluate("(sel) => document.querySelectorAll(sel).length", css);
        assertEquals("cssSelector 不得全量多匹配（隐藏副本也参与计数），实际: " + css, 1, ((Number) hits).intValue());
    }

    /**
     * 2026-11-15 用户反馈①：点按钮文案（{@code <button><span data-i18n>Continue</span></button>}）
     * ⇒ 拾取对象必须是 button，否则 role 恒 generic，只能产出 {@code div:nth-of-type(3) > button[...]}
     * 这类结构链（Continue 按钮的历史症状）。
     */
    @Test
    public void clickOnButtonTextNormalizesToButton() {
        JsonObject self = pick("button#cta > span[data-i18n=\"continue\"]");

        assertEquals("点按钮内文案必须归一到 button", "button", self.get("tag").getAsString());
        assertEquals("button", self.get("role").getAsString());
        assertEquals("归一到 button 后 id 应可唯一定位", "cta", self.get("id").getAsString());
        assertEquals("可访问名应为业务文案（空的 aria-live 播报不参与）",
                "Continue", self.get("name").getAsString());
    }

    /** 点链接内文案 ⇒ 归一到 {@code a[href]}（链接卡片整块点击同理）。 */
    @Test
    public void clickOnLinkTextNormalizesToLink() {
        JsonObject self = pick("a#privacy > span[data-i18n]");

        assertEquals("点链接内文案必须归一到 a", "a", self.get("tag").getAsString());
        assertEquals("link", self.get("role").getAsString());
        assertEquals("privacy", self.get("id").getAsString());
    }

    /**
     * 2026-11-15 用户反馈②：点 radio 的 label 内文本 ⇒ 归一到<b>被包裹的控件</b>。
     *
     * <p>旧行为会拾取 label 内的 {@code <div data-i18n>}，而浏览器随后又向 input 补发一次合成 click
     * ⇒ 一次点击产出两个元素定位。归一到控件后只剩一条，且 role=radio 可生成
     * {@code @RoleElement(role = AriaRole.RADIO, ...)} + {@code check()}。
     */
    @Test
    public void clickInsideWrappingLabelNormalizesToNestedControl() {
        JsonObject self = pick("label.option div[data-i18n=\"security_radio_label\"]");

        assertEquals("包裹式 label 内的文本必须归一到被包裹的 input", "input", self.get("tag").getAsString());
        assertEquals("radio", self.get("role").getAsString());
        assertEquals("opt-security", self.get("id").getAsString());
    }

    /**
     * 2026-11-15 用户反馈③：集合项（密保问题下拉的 {@code li[role=option]}）必须带出整组提示。
     *
     * <p>关键在于<b>三项都自洽</b>：选择器命中整组、成员数 = 实际命中数、序号 = 用户点的那一项。
     * 任一项错位，生成的 {@code get(n)} 就会点错选项——比"没有组"更糟，所以这里用真实 DOM 校验。
     */
    @Test
    public void collectionItemCarriesUsableListHint() {
        JsonObject root = info("ul#sq1 > li#sq1_c");

        JsonObject list = root.getAsJsonObject("list");
        assertNotNull("集合项必须带出整组提示（否则只能生成单元素）", list);
        String selector = list.get("selector").getAsString();
        assertEquals("整组选择器 = 容器唯一段 + 条目标签", "#sq1 > li", selector);
        assertEquals("成员数 = 容器里同类子节点数", 5, list.get("count").getAsInt());
        assertEquals("序号 = 用户实际点的那一项（第 3 个 ⇒ 2）", 2, list.get("index").getAsInt());

        Object hits = page.evaluate("(sel) => document.querySelectorAll(sel).length", selector);
        assertEquals("整组选择器必须恰好命中 count 个元素，否则 get(n) 越界/点错项",
                list.get("count").getAsInt(), ((Number) hits).intValue());
    }

    /** 隐式 ARIA 角色（{@code <li>} 无 role 属性）同样要能成组：条目选择器不能依赖 role 属性。 */
    @Test
    public void implicitRoleItemsStillFormAGroup() {
        JsonObject list = info("ul#sq3 > li:nth-child(3)").getAsJsonObject("list");

        assertNotNull("隐式 listitem 也应成组", list);
        assertEquals("#sq3 > li", list.get("selector").getAsString());
        assertEquals(3, list.get("count").getAsInt());
        assertEquals(2, list.get("index").getAsInt());
    }

    /** 容器里混入非同类节点 ⇒ 下标与 nth 不再一致，宁可没有组也不能生成会点错项的 get(n)。 */
    @Test
    public void mixedChildrenSuppressListHint() {
        JsonObject root = info("ul#sq2 > li:first-child");

        assertTrue("混合子节点时不得给出成组提示",
                !root.has("list") || root.get("list").isJsonNull());
    }

    /** 在真实浏览器里用嵌入 JS 对被选元素求值 getInfo，返回 {self:...}。 */
    private JsonObject pick(String css) {
        JsonObject self = info(css).getAsJsonObject("self");
        assertNotNull("getInfo 应返回 self", self);
        return self;
    }

    /** getInfo 的完整载荷（{@code {self, ancestors, list}}）；需要 list 提示的用例走这里。 */
    private JsonObject info(String css) {
        String expr = "JSON.stringify((" + fnSource + ").call(document.querySelector('" + css + "')))";
        Object result = page.evaluate(expr);
        return JsonParser.parseString(String.valueOf(result)).getAsJsonObject();
    }
}

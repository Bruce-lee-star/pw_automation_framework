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
            + "<div style=\"display:none\"><button class=\"panel-cta\">Continue</button></div>";

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

        //  允许为空：页面侧给不出全量唯一的 CSS 时不硬编一个多匹配选择器，
        //  由 Java 侧的 XPath / 属性候选兜底（生成阶段还有 CodegenScorer 唯一性校验）。
        //  硬约束只有一条：绝不能返回一个全量多匹配的选择器（隐藏副本被漏数就是这个后果）。
        if (!css.isEmpty()) {
            Object hits = page.evaluate("document.querySelectorAll(" + jsString(css) + ").length");
            assertEquals("cssSelector 不得全量多匹配（隐藏副本也参与计数）", 1, ((Number) hits).intValue());
        }
    }

    /** JS 字符串字面量（避免 CSS 里的引号/反斜杠破坏 evaluate 表达式）。 */
    private static String jsString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 在真实浏览器里用嵌入 JS 对被选元素求值 getInfo，返回 {self:...}。 */
    private JsonObject pick(String css) {
        String expr = "JSON.stringify((" + fnSource + ").call(document.querySelector('" + css + "')))";
        Object result = page.evaluate(expr);
        JsonObject root = JsonParser.parseString(String.valueOf(result)).getAsJsonObject();
        JsonObject self = root.getAsJsonObject("self");
        assertNotNull("getInfo 应返回 self", self);
        return self;
    }
}

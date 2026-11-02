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
            + "</div>";

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

package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.LocatorCoreScript;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 录制器「一次点击一条记录」契约（真实 Chromium + 真实注入脚本）。
 *
 * <p><b>为什么必须锁住</b>：{@code <label>} 的默认激活行为会向被包裹的 radio/checkbox
 * <b>再补发一次合成 click</b>。若照常上报，用户点一次 radio 选项就会在 Elements 面板里出现两条定位
 * （label 内的文本元素 + input），产物里也会多出一个毫无意义的元素（2026-11-15 用户反馈）。
 *
 * <p>抑制逻辑依赖「归一后的控件」（见 {@code codegen-inject.js} 的 {@code forwardedControl}）：
 * 点 label 内任意子节点（文本 div / svg / h2）都会归一为被包裹的控件，随后那一次合成 click 被吞掉。
 *
 * <p>无浏览器环境整类跳过（{@code assumeNoException}），绝不因环境缺 Chromium 让构建红。
 */
public class CodegenRecorderLabelClickTest {

    /** HSBC 真实形态：label 包裹 radio，可点区域是 label 内整块（svg / h2 / 文本 div / 说明）。 */
    private static final String HTML =
            "<fieldset><label class=\"option\" for=\"opt-security\">"
            + "<div class=\"radio-title\"><h2 class=\"radio-frame\">"
            + "<div class=\"radio-icon\"><svg width=\"18\" height=\"18\"></svg></div>"
            + "<div data-i18n=\"security_radio_label\">Answer security questions</div></h2>"
            + "<div><input type=\"radio\" id=\"opt-security\" name=\"select-option\" value=\"security\"></div>"
            + "</div>"
            + "<div class=\"radio-detail\" data-i18n=\"security_radio_description\">"
            + "Answer security questions, then verify your identity with your Security Device.</div>"
            + "</label></fieldset>"
            // 按钮文案包在 data-i18n span 里（同一类「可点区域是容器」的形态）
            + "<button id=\"cta\" type=\"button\"><span aria-live=\"polite\"></span>"
            + "<span data-i18n=\"continue\">Continue</span></button>";

    private Playwright pw;
    private Browser browser;
    private Page page;

    @Before
    public void setup() {
        try {
            Map<String, String> env = new HashMap<>();
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            Playwright.CreateOptions options = new Playwright.CreateOptions();
            options.setEnv(env);
            pw = Playwright.create(options);
            browser = pw.chromium().launch();
            page = browser.newPage();
            page.setContent(HTML);
            // 先装事件收集器（注入脚本只调用、不定义 window.__codegenEvent）
            page.evaluate("() => { window.__evts = [];"
                    + " window.__codegenEvent = function (p) { window.__evts.push(p); }; }");
            page.evaluate(readScript());
            page.evaluate("() => window.__CODEGEN__.recorder.start()");
        } catch (Throwable t) {
            Assume.assumeNoException("浏览器不可用，跳过 CodegenRecorderLabelClickTest", t);
        }
    }

    @After
    public void tearDown() {
        if (browser != null) browser.close();
        if (pw != null) pw.close();
    }

    /** 点 radio 的 label（落在 label 内文本上）⇒ 只能有一条 click 记录，且记录的是被包裹的 radio。 */
    @Test
    public void radioLabelClick_recordsExactlyOnePickForTheControl() {
        page.click("label.option");
        page.waitForTimeout(200);

        List<Map<String, Object>> clicks = clicks();
        assertEquals("点一次 radio 的 label 只能产生一条元素定位（合成 click 必须被抑制），实际: " + clicks,
                1, clicks.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) clicks.get(0).get("info");
        assertEquals("归一后的拾取对象必须是 input", "input", info.get("tag"));
        assertEquals("opt-security", info.get("id"));
        assertEquals("radio", info.get("role"));
    }

    /** 点按钮文案 ⇒ 一条记录，且拾取对象是 button（不是内部 span）。 */
    @Test
    public void buttonTextClick_recordsTheButtonItself() {
        page.click("button#cta");
        page.waitForTimeout(200);

        List<Map<String, Object>> clicks = clicks();
        assertEquals("点一次按钮只能产生一条记录，实际: " + clicks, 1, clicks.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) clicks.get(0).get("info");
        assertEquals("button", info.get("tag"));
        assertEquals("cta", info.get("id"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> clicks() {
        Object raw = page.evaluate("() => window.__evts.filter(e => e.type === 'click')");
        assertTrue("事件收集器应返回数组", raw instanceof List);
        return (List<Map<String, Object>>) raw;
    }

    /**
     * 注入脚本（{@code locator-core.js} + 注入侧模板的拼装产物）——与被测运行期同一份内容。
     *
     * <p>不能直接读 {@code codegen/codegen-inject.js}：该文件已是模板，公共定位函数在
     * {@code codegen/locator-core.js} 里（见 {@link LocatorCoreScript}）。
     */
    private static String readScript() {
        return LocatorCoreScript.inject();
    }
}

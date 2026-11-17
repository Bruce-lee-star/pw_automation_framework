package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.cdp;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * {@link CdpLocatorCounter#countByRole(String, String)} 的匹配口径契约（真实 Chromium）。
 *
 * <p><b>为什么必须锁住</b>：该计数是 {@code @RoleElement} 的「能不能用」裁决，运行期
 * {@code @RoleElement(role=…, name/key=…)} 走的是 Playwright {@code getByRole}，其 {@code name}
 * <b>默认子串匹配</b>（{@code exact=false}）。历史实现复核时加了 {@code exact=true}，与运行期口径不一致，
 * 直接导致 HSBC 侧大量 role 定位器被误判为「0 命中」并改写成
 * {@code div:nth-of-type(3) > button[class*="float-right"]} 这类长结构链。
 *
 * <p>典型触发场景（本例复刻）：按钮里带一个 {@code aria-live} 播报 {@code <span>}
 * （{@code "Loading, please wait."}），它计入可访问名 ⇒ 真实名字是
 * {@code "Loading, please wait. Continue"}，精确匹配 {@code "Continue"} 恒 0 命中。
 *
 * <p>无浏览器环境整类跳过（{@code assumeNoException}），绝不因环境缺 Chromium 让构建红。
 */
public class CdpLocatorCounterRoleNameTest {

    private static final String HTML =
            // 可见按钮：aria-live 播报文本构成可访问名前缀（HSBC 真实形态）
            "<button id=\"visible-cta\">"
            + "<span aria-live=\"polite\" aria-atomic=\"true\" class=\"accessibleHidden\">"
            + "Loading, please wait.</span> "
            // 真实模板里两个 span 之间有换行/缩进，可访问名按 accname 规则折叠为单个空格
            + "<span data-i18n=\"button_continue\">Continue</span>"
            + "</button>"
            // SPA 隐藏页 / 隐藏弹窗里的同名按钮：不在 AX 树内，不得参与计数
            + "<div style=\"display:none\"><button>Continue</button></div>"
            + "<div aria-hidden=\"true\"><button>Continue</button></div>";

    private Playwright pw;
    private Browser browser;
    private Page page;
    private CdpLocatorCounter counter;

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
            counter = new CdpLocatorCounter(page, new CodegenCdpSession(page, "en-US"));
        } catch (Throwable t) {
            Assume.assumeNoException("浏览器不可用，跳过 CdpLocatorCounterRoleNameTest", t);
        }
    }

    @After
    public void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (pw != null) {
            pw.close();
        }
    }

    /** 核心回归：含 aria-live 播报前缀的按钮，用业务文案子串必须命中 1（旧 {@code exact=true} 恒 0）。 */
    @Test
    public void substringName_matchesButtonWithAriaLivePrefix() {
        assertEquals("业务文案必须是可访问名的子串 ⇒ 命中唯一可见按钮", 1,
                counter.countByRole("button", "Continue"));
    }

    /** 隐藏页 / 隐藏弹窗里的同名按钮不在 AX 树内，不参与计数（否则会误判为多命中）。 */
    @Test
    public void hiddenDuplicates_areNotCounted() {
        assertEquals(1, counter.countByRole("button", "Continue"));
        // 与运行期 getByRole 同源：Playwright 角色引擎默认不含隐藏元素
        assertEquals(1, page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Continue")).count());
    }

    /** 用完整可访问名（含播报前缀）同样命中 1——两种写法都不应被判 0。 */
    @Test
    public void fullAccessibleName_alsoMatches() {
        assertEquals(1, counter.countByRole("button", "Loading, please wait. Continue"));
    }

    /**
     * 固化「为什么不能用 exact」：精确匹配在同一页面上恒 0 命中。
     * 若有人把 {@code countByRole} 改回 {@code setExact(true)}，本用例会失败并指向真实原因。
     */
    @Test
    public void exactMatching_wouldMiss_whichIsWhyCounterMustNotUseIt() {
        assertEquals("精确匹配在 aria-live 播报场景下必然失配（历史 bug 的根因）", 0,
                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Continue").setExact(true)).count());
    }
}

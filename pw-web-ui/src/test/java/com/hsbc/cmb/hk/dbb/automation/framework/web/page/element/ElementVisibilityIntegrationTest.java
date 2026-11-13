package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 严格可见性算法集成测试（真实 headless chromium）：验证 {@link ElementVisibility} 在各类隐藏场景下的判定，
 * 以及宽松 / 严格 / 严格+遮挡 三档语义的分野。
 *
 * <p>对照覆盖：正常可见、display:none、visibility:hidden、opacity:0、零尺寸、祖先 display:none、
 * position:fixed（offsetParent 陷阱）、视口外、被遮挡。属功能回归（硬断言），但依赖本地 chromium 二进制。</p>
 */
public class ElementVisibilityIntegrationTest {

    private static Playwright pw;
    private static Browser browser;
    private static Page page;

    @BeforeClass
    public static void initAll() {
        pw = Playwright.create();
        browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        page = browser.newPage();
    }

    @AfterClass
    public static void tearDownAll() {
        // best-effort 清理：关闭接口只可能抛 PlaywrightException（运行时），收窄类型既满足门禁又避免吞掉其它异常
        if (page != null) { try { page.close(); } catch (PlaywrightException ignored) { } }
        if (browser != null) { try { browser.close(); } catch (PlaywrightException ignored) { } }
        if (pw != null) { try { pw.close(); } catch (PlaywrightException ignored) { } }
    }

    private void given(String html) {
        page.setContent("<!doctype html><html><body>" + html + "</body></html>");
    }

    @Test
    public void normalVisible_bothTiersTrue() {
        given("<button id='t' style='width:80px;height:30px'>ok</button>");
        Assert.assertTrue(ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertTrue(ElementVisibility.isVisibleStrict(page.locator("#t")));
    }

    @Test
    public void displayNone_bothTiersFalse() {
        given("<button id='t' style='display:none'>x</button>");
        Assert.assertFalse(ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertFalse(ElementVisibility.isVisibleStrict(page.locator("#t")));
    }

    @Test
    public void visibilityHidden_bothTiersFalse() {
        given("<button id='t' style='visibility:hidden;width:80px;height:30px'>x</button>");
        Assert.assertFalse(ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertFalse(ElementVisibility.isVisibleStrict(page.locator("#t")));
    }

    @Test
    public void opacityZero_looseTrue_strictFalse() {
        given("<button id='t' style='opacity:0;width:80px;height:30px'>x</button>");
        Assert.assertTrue("loose must ignore opacity:0 (aligns with Playwright :visible)",
                ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertFalse("strict must exclude opacity:0",
                ElementVisibility.isVisibleStrict(page.locator("#t")));
    }

    @Test
    public void zeroSize_bothTiersFalse() {
        given("<div id='t' style='width:0;height:0'></div>");
        Assert.assertFalse(ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertFalse(ElementVisibility.isVisibleStrict(page.locator("#t")));
    }

    @Test
    public void ancestorDisplayNone_bothTiersFalse() {
        given("<div style='display:none'><button id='t' style='width:80px;height:30px'>x</button></div>");
        Assert.assertFalse(ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertFalse(ElementVisibility.isVisibleStrict(page.locator("#t")));
    }

    @Test
    public void positionFixedVisible_looseTrue_offsetParentTrapAvoided() {
        given("<button id='t' style='position:fixed;left:10px;top:10px;width:80px;height:30px'>f</button>");
        // 传统 offsetParent===null 写法会把它误判为隐藏；本算法用 parentElement 递归，正确判可见
        Assert.assertTrue(ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertTrue(ElementVisibility.isVisibleStrict(page.locator("#t")));
    }

    @Test
    public void viewportOut_looseTrue_strictFalse() {
        given("<button id='t' style='position:absolute;left:10000px;top:0;width:80px;height:30px'>x</button>");
        Assert.assertTrue("loose must ignore off-viewport (aligns with Playwright :visible)",
                ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertFalse("strict must exclude off-viewport",
                ElementVisibility.isVisibleStrict(page.locator("#t")));
    }

    @Test
    public void occluded_looseTrue_strictWithoutOcclusionTrue_strictWithOcclusionFalse() {
        given("<div style='position:relative;width:200px;height:60px'>"
                + "<button id='t' style='position:absolute;left:0;top:0;width:100px;height:40px'>x</button>"
                + "<div style='position:absolute;left:0;top:0;width:100px;height:40px;background:red;z-index:10'>y</div>"
                + "</div>");
        Assert.assertTrue("loose ignores occlusion", ElementVisibility.isVisible(page.locator("#t")));
        Assert.assertTrue("strict-without-occlusion ignores occlusion",
                ElementVisibility.isVisibleStrict(page.locator("#t")));
        Assert.assertFalse("strict-with-occlusion excludes occluded",
                ElementVisibility.isVisibleStrict(page.locator("#t"), true));
    }
}

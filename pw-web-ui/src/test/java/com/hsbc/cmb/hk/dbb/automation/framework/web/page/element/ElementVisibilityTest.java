package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ElementVisibility} 包装层单测（无浏览器，Mockito 隔离底层 Locator）。
 *
 * <p>聚焦框架侧契约：空参降级、底层布尔映射、计算异常降级为"不可见"——
 * 算法本身的 DOM 覆盖由 {@link ElementVisibilityIntegrationTest}（真实 chromium）保证。</p>
 */
public class ElementVisibilityTest {

    @Test
    public void nullLocator_alwaysFalse() {
        Assert.assertFalse(ElementVisibility.isVisible(null));
        Assert.assertFalse(ElementVisibility.isVisibleStrict(null));
        Assert.assertFalse(ElementVisibility.isVisibleStrict(null, true));
    }

    @Test
    public void evaluateReturnsTrue_mapsToVisible() {
        Locator loc = mock(Locator.class);
        when(loc.evaluate(anyString(), any())).thenReturn(true);
        Assert.assertTrue(ElementVisibility.isVisible(loc));
        Assert.assertTrue(ElementVisibility.isVisibleStrict(loc));
        Assert.assertTrue(ElementVisibility.isVisibleStrict(loc, true));
    }

    @Test
    public void evaluateReturnsFalse_mapsToNotVisible() {
        Locator loc = mock(Locator.class);
        when(loc.evaluate(anyString(), any())).thenReturn(false);
        Assert.assertFalse(ElementVisibility.isVisible(loc));
        Assert.assertFalse(ElementVisibility.isVisibleStrict(loc));
    }

    @Test
    public void evaluateThrows_playwrightException_mapsToNotVisible() {
        Locator loc = mock(Locator.class);
        when(loc.evaluate(anyString(), any())).thenThrow(new PlaywrightException("element not found"));
        Assert.assertFalse("probe failure must degrade to not-visible, never leak", ElementVisibility.isVisible(loc));
        Assert.assertFalse(ElementVisibility.isVisibleStrict(loc, true));
    }
}

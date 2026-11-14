package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PageElementList#visibleSize()} 接线测试（mock，无浏览器）。
 *
 * <p>钉住契约：可见数量走 <b>Playwright 原生</b>可见性过滤
 * {@code locator.filter(setVisible(true)).count()}（单次往返、浏览器层判定、零 JS 注入），
 * 不再使用任何 JS 探针（原 {@code ElementVisibility} 已下线）。</p>
 */
public class PageElementListVisibleSizeTest {

    private static PageElementList listOver(Locator base) {
        return new PageElementList((Supplier<Locator>) () -> base, "continue-list", mock(BasePage.class));
    }

    @Test
    public void visibleSize_usesNativeVisibleFilter_thenCounts() {
        Locator base = mock(Locator.class);
        Locator filtered = mock(Locator.class);
        when(base.filter(any(Locator.FilterOptions.class))).thenReturn(filtered);
        when(filtered.count()).thenReturn(2);

        assertEquals(2, listOver(base).visibleSize());

        ArgumentCaptor<Locator.FilterOptions> captor = ArgumentCaptor.forClass(Locator.FilterOptions.class);
        verify(base).filter(captor.capture());
        assertEquals("必须开启原生 visible 过滤", Boolean.TRUE, captor.getValue().visible);

        // 不得回退到 JS 探针 / 逐元素遍历
        verify(base, never()).evaluate(anyString(), any());
        verify(base, never()).evaluateAll(anyString(), any());
        verify(base, never()).count();
    }

    @Test
    public void visibleSize_probeFailure_degradesToZero_withoutThrowing() {
        Locator base = mock(Locator.class);
        when(base.filter(any(Locator.FilterOptions.class)))
                .thenThrow(new PlaywrightException("Target page, context or browser has been closed"));

        assertEquals("查询失败降级为 0，不向调用方抛异常", 0, listOver(base).visibleSize());
    }

    @Test
    public void sizeStillCountsDomTotal_includingHidden() {
        Locator base = mock(Locator.class);
        Locator first = mock(Locator.class);
        when(base.first()).thenReturn(first);
        when(base.count()).thenReturn(5);

        PageElementList list = listOver(base);

        assertEquals("size() 仍是 DOM 匹配总数，不受可见性影响", 5, list.size());
        verify(base, never()).filter(any(Locator.FilterOptions.class));
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.PageRefs;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DelayGuardPageProxy} 运行期 guard 验证（录制关闭路径，§8.4 零容忍）。
 *
 * <p>本测试同时端到端验证 {@code RouteLifecycleComposite} 对 {@code hasActiveDelayRoutes} 的委托：
 * web 侧经 {@code RouteLifecycleRegistry.get()} 取得组合代理，若组合代理未转发该 default 方法，
 * guard 永远为 false → 本测试会失败（曾经确为生产 no-op，已被修复）。</p>
 */
public class DelayGuardPageProxyTest {

    @After
    public void tearDown() {
        // 复位 primary，避免 mock 实现泄漏到其它测试套件（additions 中的真实实现由 static 块加载，primary 优先不影响）
        RouteLifecycleRegistry.register(null);
    }

    @Test
    public void waitForFunction_withActiveDelayRoutes_throwsBrowserException_andDoesNotDelegate() {
        Page real = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(real.context()).thenReturn(ctx);

        RouteLifecycle lc = mock(RouteLifecycle.class);
        when(lc.hasActiveDelayRoutes(any())).thenReturn(true);
        RouteLifecycleRegistry.register(lc);

        Page proxy = DelayGuardPageProxy.wrap(real);

        BrowserException ex = assertThrows(BrowserException.class,
                () -> proxy.waitForFunction("true"));
        assertTrue("异常必须指向 DELAY 约束", ex.getMessage().contains("DELAY"));
        verify(real, never()).waitForFunction(any());
    }

    @Test
    public void waitForFunction_withoutActiveDelayRoutes_delegatesToRealPage() {
        Page real = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(real.context()).thenReturn(ctx);

        RouteLifecycle lc = mock(RouteLifecycle.class);
        when(lc.hasActiveDelayRoutes(any())).thenReturn(false);
        RouteLifecycleRegistry.register(lc);

        Page proxy = DelayGuardPageProxy.wrap(real);

        proxy.waitForFunction("true");
        verify(real).waitForFunction("true");
    }

    @Test
    public void nonWaitForFunctionMethod_delegatesDirectly() {
        Page real = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(real.context()).thenReturn(ctx);

        RouteLifecycle lc = mock(RouteLifecycle.class);
        when(lc.hasActiveDelayRoutes(any())).thenReturn(true); // 即便有 DELAY，非 waitForFunction 不受影响
        RouteLifecycleRegistry.register(lc);

        Page proxy = DelayGuardPageProxy.wrap(real);
        proxy.click("button#submit");

        verify(real).click("button#submit");
    }

    @Test
    public void nullPage_returnsNull() {
        assertSame("null 入参必须原样返回", null, DelayGuardPageProxy.wrap(null));
    }

    @Test
    public void pageRefs_unwrap_resolvesToRealPage() {
        Page real = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(real.context()).thenReturn(ctx);

        Page proxy = DelayGuardPageProxy.wrap(real);
        assertSame("PageRefs.unwrap 必须解开薄代理回到真实 Page（路由 PAGE 级规则身份归一）",
                real, PageRefs.unwrap(proxy));
    }
}

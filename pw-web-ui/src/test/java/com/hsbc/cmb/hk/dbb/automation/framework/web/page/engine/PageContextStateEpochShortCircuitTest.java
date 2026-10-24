package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-18 回归：验证 {@link PageContextState#ensurePageValid()} 在「本线程当前页未发生切换」时
 * 跳过昂贵的 {@link PlaywrightManager#getPage()} 全链路比对（epoch 短路），并在 epoch 变化（发生切换）时
 * 重新同步 {@code owner.page}。
 *
 * <p>经 {@link PlaywrightRuntime#setInstance} 注入一个以 mock {@link PageRegistry} 为唯一替身的运行时组合根：
 * {@code PlaywrightManager.getPage()/currentPageEpoch()} 均经 {@code PlaywrightRuntime.instance().pageRegistry} 触达，
 * 故可精确统计 {@code getPage()} 调用次数。</p>
 */
public class PageContextStateEpochShortCircuitTest {

    private PlaywrightRuntime savedRuntime;
    private PageRegistry pageRegistry;
    private BasePage basePage;
    private PageContextState state;

    @Before
    public void setUp() throws Exception {
        savedRuntime = PlaywrightRuntime.instance();

        pageRegistry = mock(PageRegistry.class);
        // 注入含 mock PageRegistry 的运行时组合根（其余协作者用生产单例，本测试不触发其逻辑）。
        PlaywrightRuntime custom = new PlaywrightRuntime(
                BrowserRegistryImpl.INSTANCE,
                ContextRegistryImpl.INSTANCE,
                pageRegistry,
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE,
                PlaywrightRuntimeState.INSTANCE);
        PlaywrightRuntime.setInstance(custom);

        basePage = new BasePage() {};
        // owner.page 为 protected 字段，同包可直接赋值；mock Page 的 isClosed() 默认 false ⇒ 走 else 有效分支。
        basePage.page = mock(Page.class);

        Field f = BasePage.class.getDeclaredField("pageContextState");
        f.setAccessible(true);
        state = (PageContextState) f.get(basePage);
    }

    @After
    public void tearDown() {
        PlaywrightRuntime.setInstance(savedRuntime);
    }

    /**
     * 核心断言：page 已有效且 epoch 未变时，连续多次 ensurePageValid 只触发一次 getPage()，
     * 后续 element-op 调用被 epoch 短路，不再付出静态调用链开销。
     */
    @Test
    public void shortCircuitsGetPageWhenEpochUnchanged() {
        Page page = basePage.page;
        when(pageRegistry.currentPageEpoch()).thenReturn(0L);
        when(pageRegistry.getPage()).thenReturn(page);

        state.ensurePageValid();   // 首次同步：epoch 0 != -1 ⇒ 调 getPage()
        state.ensurePageValid();   // epoch 0 == 0 ⇒ 短路，不调 getPage()
        state.ensurePageValid();   // epoch 0 == 0 ⇒ 短路，不调 getPage()

        // P2-18 回归点：3 次 ensurePageValid 仅 1 次真正走到 getPage() 全链路。
        verify(pageRegistry, times(1)).getPage();
    }

    /**
     * 开关行为不变性：epoch 变化（框架经 setPage 切换当前页）后，ensurePageValid 必须重新调 getPage()
     * 并把 owner.page 同步到新页面，不能因短路而漏掉真实的页面切换。
     */
    @Test
    public void reSyncsOwnerPageWhenEpochChanges() throws Exception {
        Page page1 = basePage.page;
        Page page2 = mock(Page.class);

        when(pageRegistry.currentPageEpoch()).thenReturn(0L);
        when(pageRegistry.getPage()).thenReturn(page1);

        state.ensurePageValid();   // 首次同步：getPage()→page1，owner.page 保持 page1
        state.ensurePageValid();   // epoch 0 == 0 ⇒ 短路，不调 getPage()
        verify(pageRegistry, times(1)).getPage();

        // 模拟框架把线程当前页切换为 page2（PlaywrightManager.setPage 会自增 epoch）。
        when(pageRegistry.currentPageEpoch()).thenReturn(1L);
        when(pageRegistry.getPage()).thenReturn(page2);

        state.ensurePageValid();   // epoch 1 != 0 ⇒ 重新调 getPage()→page2，并同步 owner.page
        verify(pageRegistry, times(2)).getPage();

        Field pf = BasePage.class.getDeclaredField("page");
        pf.setAccessible(true);
        assertSame("owner.page 应随 epoch 变化重新同步到新 Page", page2, pf.get(basePage));
    }
}

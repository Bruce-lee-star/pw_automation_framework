package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;

import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.LifecycleState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code BrowserCleanupImpl.forceReapPlaywrightForBrowser} 契约（2026-10-11 browser-close 根治）：
 * <ul>
 *   <li>补偿成功（driver 有界关闭完成）→ 摘除 driver + 返回 {@code true} + <b>不</b>记失败（WARN 语义）；</li>
 *   <li>补偿也超时 → 返回 {@code false} + 记 ERROR（且只记一次，不得双重记账）；</li>
 *   <li>找不到 driver 映射 / 无 driver 实例 → 返回 {@code false} + 不记失败（交 JVM 退出兜底）。</li>
 * </ul>
 */
public class BrowserCleanupImplForceReapTest {

    private LifecycleState state;

    @Before
    public void setUp() {
        state = mock(LifecycleState.class);
        PlaywrightRuntime.setInstance(new PlaywrightRuntime(
                BrowserRegistryImpl.INSTANCE,
                ContextRegistryImpl.INSTANCE,
                PageRegistryImpl.INSTANCE,
                BrowserStartupImpl.INSTANCE,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE,
                state));
    }

    @After
    public void tearDown() {
        PlaywrightRuntime.resetInstance();
    }

    @Test
    // @DisplayName: "强收成功 = 补偿完成：摘除 driver、关闭被调用、不记失败（WARN 语义）"
    public void driverReapCompensatesTimedOutCloseWithoutFailure() {
        Browser browser = mock(Browser.class);
        Playwright playwright = mock(Playwright.class);
        String key = "1:cfg";
        when(state.browserEntries()).thenReturn(Set.of(Map.entry(key, browser)));
        when(state.getPlaywright(key)).thenReturn(playwright);
        long before = ShutdownCoordinator.getFailureCount();

        boolean compensated = BrowserCleanupImpl.forceReapPlaywrightForBrowser(browser, 3_000);

        assertTrue("强收成功必须判定补偿完成", compensated);
        verify(state).removePlaywright(key);
        verify(playwright, timeout(3_000)).close();
        assertEquals("补偿成功不得记失败", before, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "强收也超时 = 补偿失败：记 ERROR 且只记一次（不得双重记账）"
    public void driverReapTimeoutRecordsSingleFailure() {
        Browser browser = mock(Browser.class);
        Playwright playwright = mock(Playwright.class);
        String key = "1:cfg";
        when(state.browserEntries()).thenReturn(Set.of(Map.entry(key, browser)));
        when(state.getPlaywright(key)).thenReturn(playwright);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            awaitQuietly(release);
            return null;
        }).when(playwright).close();
        long before = ShutdownCoordinator.getFailureCount();

        boolean compensated = BrowserCleanupImpl.forceReapPlaywrightForBrowser(browser, 3_000);

        assertFalse("强收也超时必须判定补偿失败", compensated);
        assertEquals("补偿失败记 ERROR，且只记一次（4 参重载已关自动记账）",
                before + 1, ShutdownCoordinator.getFailureCount());
        release.countDown();
    }

    @Test
    // @DisplayName: "找不到 driver 映射 = 不记失败（交 JVM 退出进程树回收兜底）"
    public void noDriverMappingReturnsFalseWithoutFailure() {
        Browser browser = mock(Browser.class);
        when(state.browserEntries()).thenReturn(Set.of());
        long before = ShutdownCoordinator.getFailureCount();

        boolean compensated = BrowserCleanupImpl.forceReapPlaywrightForBrowser(browser, 3_000);

        assertFalse(compensated);
        assertEquals("找不到 driver 映射不得记失败", before, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "有映射但无 driver 实例 = 不记失败（交 JVM 退出进程树回收兜底）"
    public void missingPlaywrightForKeyReturnsFalseWithoutFailure() {
        Browser browser = mock(Browser.class);
        String key = "1:cfg";
        when(state.browserEntries()).thenReturn(Set.of(Map.entry(key, browser)));
        when(state.getPlaywright(key)).thenReturn(null);
        long before = ShutdownCoordinator.getFailureCount();

        boolean compensated = BrowserCleanupImpl.forceReapPlaywrightForBrowser(browser, 3_000);

        assertFalse(compensated);
        assertEquals("无 driver 实例不得记失败", before, ShutdownCoordinator.getFailureCount());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

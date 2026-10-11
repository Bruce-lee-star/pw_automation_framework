package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「放弃即隔离」（2026-10-10）契约：收尾 {@code close()} 超时放弃后登记 DIRTY 连接，
 * 下一场景 {@code getBrowser()} 检测到 DIRTY 必须丢弃现有 Playwright+Browser 并走重建，
 * 而不是在可能仍被挂起 close 占用的脏连接上复用。
 *
 * <p>零浏览器：全程 Mockito 替身 + {@link PlaywrightRuntime} 组合根替换（同 WEB-P1-6 seam）。</p>
 */
public class ConnectionDirtyQuarantineTest {

    private final BrowserStartup browserStartup = mock(BrowserStartup.class);
    private final BrowserRestart browserRestart = mock(BrowserRestart.class);
    private final BrowserCleanup browserCleanup = mock(BrowserCleanup.class);

    @After
    public void tearDown() {
        // 复位组合根与线程绑定，避免污染其它测试
        PlaywrightRuntime.resetInstance();
        PlaywrightManager.resetProvider();
        TestContextHolder.get().remove(PlaywrightManager.CURRENT_CONFIG_ID_KEY);
        // 清理全局状态根上的 dirty 标记（幂等：不存在时 remove 为 no-op）
        PlaywrightRuntimeState.INSTANCE.clearConnectionDirty("t:cfg");
        PlaywrightRuntimeState.INSTANCE.clearConnectionDirty("a");
        PlaywrightRuntimeState.INSTANCE.clearConnectionDirty("b");
    }

    private void installRuntime() {
        PlaywrightRuntime.setInstance(new PlaywrightRuntime(
                mock(BrowserRegistry.class),
                mock(ContextRegistry.class),
                mock(PageRegistry.class),
                browserStartup,
                browserRestart,
                browserCleanup,
                PlaywrightRuntimeState.INSTANCE));
    }

    /** 状态层契约：mark / is / clear 的独立性与幂等性。 */
    @Test
    public void dirtyMark_contract_isIndependentPerKey() {
        assertFalse(PlaywrightRuntimeState.INSTANCE.isConnectionDirty("t:cfg"));

        PlaywrightRuntimeState.INSTANCE.markConnectionDirty("t:cfg");
        assertTrue(PlaywrightRuntimeState.INSTANCE.isConnectionDirty("t:cfg"));

        // 多 key 独立，clear 只影响目标 key
        PlaywrightRuntimeState.INSTANCE.markConnectionDirty("a");
        PlaywrightRuntimeState.INSTANCE.markConnectionDirty("b");
        assertTrue(PlaywrightRuntimeState.INSTANCE.isConnectionDirty("a"));
        assertTrue(PlaywrightRuntimeState.INSTANCE.isConnectionDirty("b"));
        PlaywrightRuntimeState.INSTANCE.clearConnectionDirty("a");
        assertFalse(PlaywrightRuntimeState.INSTANCE.isConnectionDirty("a"));
        assertTrue(PlaywrightRuntimeState.INSTANCE.isConnectionDirty("b"));

        // 重复 clear 幂等
        PlaywrightRuntimeState.INSTANCE.clearConnectionDirty("b");
        PlaywrightRuntimeState.INSTANCE.clearConnectionDirty("b");
        assertFalse(PlaywrightRuntimeState.INSTANCE.isConnectionDirty("b"));
    }

    /**
     * 放弃即隔离主路径：DIRTY 连接上已有旧 Playwright+Browser 时，
     * getBrowser() 必须清除 dirty、摘除旧实例并走重建（initializeBrowser），绝不复用。
     */
    @Test
    public void dirtyConnection_isClearedAndStaleInstancesAreDiscarded() {
        installRuntime();
        when(browserStartup.generateConfigId()).thenReturn("chromium_headless_");

        // 预置旧实例 + dirty（模拟上次收尾 close 超时放弃）
        String key = BrowserRegistryImpl.INSTANCE.keyFor("chromium_headless_");
        Playwright stalePw = mock(Playwright.class);
        Browser staleBrowser = mock(Browser.class);
        PlaywrightRuntimeState.INSTANCE.putPlaywright(key, stalePw);
        PlaywrightRuntimeState.INSTANCE.putBrowser(key, staleBrowser);
        PlaywrightRuntimeState.INSTANCE.markConnectionDirty(key);

        Browser result = BrowserRegistryImpl.INSTANCE.getBrowser();

        // dirty 已清除，旧 Playwright/Browser 已从状态根摘除（纯摘除：对脏连接零协议调用）
        assertFalse(PlaywrightRuntimeState.INSTANCE.isConnectionDirty(key));
        assertNull("stale Playwright must be removed from state root", PlaywrightRuntimeState.INSTANCE.getPlaywright(key));
        assertNull("stale Browser must be removed from state root", PlaywrightRuntimeState.INSTANCE.getBrowser(key));
        // 走慢路径重建（mock 环境无真实浏览器，getBrowser 返回 null，但重建入口必须被调用）
        verify(browserStartup).initializeBrowser(anyString());
        assertNull(result);
    }

    /** 无 DIRTY 标记时快速路径行为不受影响（回归护栏）：已连接 Browser 直接复用。 */
    @Test
    public void cleanConnection_reusesExistingConnectedBrowser() {
        installRuntime();
        when(browserStartup.generateConfigId()).thenReturn("chromium_headless_");

        String key = BrowserRegistryImpl.INSTANCE.keyFor("chromium_headless_");
        Browser liveBrowser = mock(Browser.class);
        when(liveBrowser.isConnected()).thenReturn(true);
        PlaywrightRuntimeState.INSTANCE.putBrowser(key, liveBrowser);

        Browser result = BrowserRegistryImpl.INSTANCE.getBrowser();

        // 快速路径：无 dirty 且 connected → 直接返回同一实例，不触发重建
        org.junit.Assert.assertSame(liveBrowser, result);
        verify(browserStartup, org.mockito.Mockito.never()).initializeBrowser(anyString());
    }
}

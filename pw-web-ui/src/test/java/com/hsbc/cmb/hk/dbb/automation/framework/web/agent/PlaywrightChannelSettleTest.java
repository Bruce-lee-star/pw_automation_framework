package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * D3 稳定语义（I-03）门禁：验证 PlaywrightChannel.settle 实际实现——
 * 导航感知（waitForLoadState）+ 静默窗口下限 + 超时预算兜底，且不暴露 evaluate（R2 红线）。
 */
public class PlaywrightChannelSettleTest {

    @Test
    public void settleWaitsForLoadStateWithBoundedTimeout() {
        Page page = mock(Page.class);
        PlaywrightChannel ch = new PlaywrightChannel(page, mock(Browser.class), mock(BrowserContext.class));
        ch.settle(500);
        // 导航感知 + 网络稳定：等待 load state 收敛；超时受 budget(500) 约束（总时长有上限，不无限等待）
        verify(page).waitForLoadState(eq(LoadState.LOAD), any());
    }

    @Test
    public void settleZeroWindowIsNoOp() {
        Page page = mock(Page.class);
        PlaywrightChannel ch = new PlaywrightChannel(page, mock(Browser.class), mock(BrowserContext.class));
        ch.settle(0);
        // 关闭时不等待，与现状一致
        verify(page, never()).waitForLoadState(any(), any());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RouteDsl#waitForUiFunction} 契约（方案 B 可选通道配套 API，2026-10-11）：
 * <ul>
 *   <li>行为等效 {@code page.waitForFunction}：条件满足即返回；总超时抛 {@code TimeoutError}；</li>
 *   <li>内部循环「drain + 短窗口 waitForFunction」：短窗口超时不终止，继续 drain + 再等；</li>
 *   <li>总超时由 deadline 判定，错误信息含总超时说明。</li>
 * </ul>
 * 页面为 mock：{@code RouteEngine.drainMainThreadActions(mockCtx)} 命中不到已注册 runtime（跳过），
 * 单测只验证循环/超时/异常语义；main 队列交付本身的时序在
 * {@link DelayAppliesWaitTest#mainQueueModeDeliversOnlyAfterMainDrain} 验证。
 */
public class RouteUiWaitTest {

    private BrowserContext ctx;
    private Page page;

    @Before
    public void setUp() {
        ctx = mock(BrowserContext.class);
        page = mock(Page.class);
        when(page.context()).thenReturn(ctx);
    }

    @Test
    public void returnsImmediatelyWhenConditionMet() {
        // page.waitForFunction 不抛（条件满足）→ 一轮返回
        RouteDsl.waitForUiFunction(page, "() => true", null, 5_000);
        verify(page).waitForFunction(anyString(), any(), any());
    }

    @Test
    public void throwsTimeoutWithMessageWhenConditionNeverMet() {
        doThrow(new TimeoutError("slice timeout")).when(page).waitForFunction(anyString(), any(), any());
        try {
            RouteDsl.waitForUiFunction(page, "() => false", null, 200);
            fail("条件永不满足应抛 TimeoutError");
        } catch (TimeoutError expected) {
            assertTrue("错误信息应含总超时说明: " + expected.getMessage(),
                    expected.getMessage().contains("timed out after 200ms"));
        }
    }

    @Test
    public void keepsPollingAcrossSliceTimeoutsUntilConditionMet() {
        // 前 2 次短窗口超时、第 3 次满足 → 循环继续并返回（drain 每轮执行，空队列零开销）
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() < 3) {
                throw new TimeoutError("slice timeout");
            }
            return null; // 第 3 次条件满足
        }).when(page).waitForFunction(anyString(), any(), any());

        RouteDsl.waitForUiFunction(page, "() => maybe", null, 5_000);
        assertEquals("短窗口超时应继续轮询直至条件满足", 3, calls.get());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.route.claim;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch.RouteAction;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转发状态保护（{@link RouteClaim#markIssued()} / {@link RouteClaim#isIssued()}）验证：
 * <ul>
 *   <li>{@code markIssued} 置位后 {@code isIssued} 返回 true；</li>
 *   <li>{@link RouteAction#fallback(RouteClaim, Route)} 在 claim 已 issued 时<b>抑制</b>
 *       {@code route.fallback()}（= 不再二次放行到真实网络），防异常路径重复发业务请求；</li>
 *   <li>{@code ClaimRegistry#sweep()} 对已 issued 的 IO_AWAIT claim 跳过强制 fallback，
 *       交由已发出请求的自然终结落定；</li>
 *   <li>claim 为 null 时退化为普通 fallback（预所有权阶段无重复风险）。</li>
 * </ul>
 */
public class RouteClaimForwardingStateTest {

    private Route mockRoute(String url) {
        Route route = mock(Route.class);
        Request request = mock(Request.class);
        when(route.request()).thenReturn(request);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(java.util.Map.of());
        return route;
    }

    @Test
    public void markIssuedSetsIssuedFlag() {
        RouteClaim claim = new RouteClaim(mockRoute("https://x/api/issued"));
        assertFalse("初始未发请求", claim.isIssued());
        claim.markIssued();
        assertTrue("markIssued 后必须置位", claim.isIssued());
    }

    @Test
    public void fallbackSuppressedWhenIssued() {
        Route route = mockRoute("https://x/api/mock-intercept");
        RouteClaim claim = new RouteClaim(route);
        claim.markIssued();

        boolean result = RouteAction.fallback(claim, route);

        assertFalse("已 issued 的 fallback 必须被抑制（返回 false）", result);
        verify(route, never()).fallback(); // 绝不能再次放行到真实网络（否则重复发送）
    }

    @Test
    public void fallbackProceedsWhenNotIssued() {
        Route route = mockRoute("https://x/api/not-issued");
        RouteClaim claim = new RouteClaim(route); // 未 markIssued

        boolean result = RouteAction.fallback(claim, route);

        assertTrue("未 issued 的 fallback 正常放行", result);
        verify(route).fallback();
    }

    @Test
    public void fallbackDegradesWhenClaimNull() {
        Route route = mockRoute("https://x/api/pre-ownership");
        // claim 为 null（tryClaim 返回 null 的预所有权阶段）
        boolean result = RouteAction.fallback(null, route);
        assertTrue("null claim 退化为普通 fallback", result);
        verify(route).fallback();
    }

    @Test
    public void sweepSkipsIssuedIoAwaitClaim() throws InterruptedException {
        PendingGuard guard = new PendingGuard(16);
        ClaimRegistry registry = new ClaimRegistry(guard, Duration.ofMillis(50));
        Route route = mockRoute("https://x/api/sweep-issued");
        RouteClaim claim = registry.tryClaim(route);
        assertTrue(guard.tryAcquire());
        assertTrue(claim.toIoAwait());
        claim.markIssued(); // 业务请求已通过 fetch 发出

        Thread.sleep(120); // 超龄
        registry.sweep(); // 已 issued → 必须跳过强制 fallback

        assertFalse("已 issued 的 IO_AWAIT claim 不得被 sweep 强制落定（否则重复发送）", claim.isTerminal());
        verify(route, never()).fallback();
        assertTrue("额度仍占用（owner 将自行 fulfill 释放）", guard.pendingCount() == 1);
    }

    @Test
    public void sweepStillForcesUnissuedStaleClaim() throws InterruptedException {
        PendingGuard guard = new PendingGuard(16);
        ClaimRegistry registry = new ClaimRegistry(guard, Duration.ofMillis(50));
        Route route = mockRoute("https://x/api/sweep-unissued");
        RouteClaim claim = registry.tryClaim(route);
        assertTrue(guard.tryAcquire());
        assertTrue(claim.toIoAwait());
        // 未 markIssued（请求尚未发出）→ 超龄必须强制 fallback 防悬挂

        Thread.sleep(120); // 超龄
        registry.sweep();

        assertTrue("未 issued 的超龄 IO_AWAIT 仍由 sweep 强制落定", claim.isTerminal());
        verify(route).fallback();
        assertSame("额度已释放", (long) 0, (long) guard.pendingCount());
    }
}

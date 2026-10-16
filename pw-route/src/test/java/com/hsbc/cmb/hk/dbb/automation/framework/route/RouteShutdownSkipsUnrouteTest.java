package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.CapturingGuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCallRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「teardown 跳过逐条 unroute」契约（2026-10-15 定稿）。
 *
 * <p><b>背景</b>：本框架每 case 重建 Context（不变式 I-1），Context 关闭时驱动侧 route handler 由
 * Playwright 随 context 一并释放，逐条 unroute 纯属浪费的同步协议往返（客户端无超时、实测 10s 未确证），
 * 且是「收尾未确证 ⇒ 状态分叉」的来源；句柄已失效时 unroute 还会抛 {@code Object doesn't exist}。
 * 故 {@link RouteRuntimeImpl#close()} 与 {@link RouteRuntimeImpl#closeAfterContextClosed()} 无论
 * Context 是否仍存活都<b>不再发起 unroute</b>，只做内存收尾。</p>
 *
 * <p>本用例用 {@link CapturingGuardedDriverCall}（只记录、不执行 action）断言<b>协议调用面</b>：
 * 传 {@code true}（context 正在关闭）与不传 / 传 {@code false}（context 仍存活）均不得出现任何
 * {@code unroute:} —— teardown 统一不 unroute，与 Context 生命周期状态无关。</p>
 */
public class RouteShutdownSkipsUnrouteTest {

    private BrowserContext ctx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        return ctx;
    }

    private CapturingGuardedDriverCall installCapturingGuard() {
        CapturingGuardedDriverCall guard = new CapturingGuardedDriverCall();
        GuardedDriverCallRegistry.setInstance(guard);
        return guard;
    }

    /** MONITOR 能力：bind 未确证时走降级（WARN_AND_ABANDON），不会因替身不执行 action 而抛错。 */
    private ApiSpec monitorSpec() {
        return ApiSpec.builder("notifications/streams", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
        GuardedDriverCallRegistry.reset();
    }

    @Test
    public void contextBeingClosed_skipsUnrouteEntirely() {
        CapturingGuardedDriverCall guard = installCapturingGuard();
        BrowserContext context = mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(context);
        runtime.register(monitorSpec());

        RouteEngine.shutdown(context, true);

        assertTrue("bind 仍须经 GuardedDriverCall 收口", guard.hasCallWithOpPrefix("bind:"));
        assertFalse("Context 正被关闭 ⇒ 不得再发起任何 unroute（规则随 close 原生释放）",
                guard.hasCallWithOpPrefix("unroute"));
    }

    @Test
    public void withoutIntent_unrouteIsAlsoSkipped() {
        CapturingGuardedDriverCall guard = installCapturingGuard();
        BrowserContext context = mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(context);
        runtime.register(monitorSpec());

        RouteEngine.shutdown(context, false);

        assertFalse("teardown 统一不 unroute（handler 随 context.close() 原生释放），与 intent 无关",
                guard.hasCallWithOpPrefix("unroute"));
    }
}

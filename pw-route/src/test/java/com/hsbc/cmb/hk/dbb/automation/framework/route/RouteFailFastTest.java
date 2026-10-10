package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.DriverFailureSignal;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RoutePatterns;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1 RouteFailFast（route 失败即收敛）回归 —— 对照问题清单 C6「失败后 route 继续拦截/观测」。
 *
 * <ul>
 *   <li><b>断言① 失败即直通</b>：{@link DriverFailureSignal#report()} 之后，dispatch 不再拦截/观测，
 *       直接 {@code fallback()}（真实网络放行），绝不 resume / fulfill / abort；</li>
 *   <li><b>断言② 挂起收敛</b>：失败信号后第一个 dispatch 触发一次幂等的挂起收敛
 *       （{@code claims.closeAll()}）—— 之前已进入 DELAY 挂起（IO_AWAIT）的请求立即 fallback，
 *       不等 sweep(35s)，也不等场景收尾；</li>
 *   <li><b>断言③ 幂等</b>：后续 dispatch 不再重复收敛（不重复终结，route 不重复 fallback 命令）。
 *       终结动作均为 sendMessageAsync（不泵连接），与 O1 单泵原则一致。</li>
 * </ul>
 */
public class RouteFailFastTest {

    private BrowserContext ctx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        doAnswer(inv -> null).when(ctx).onClose(any());
        RouteEngine.runtimeOf(ctx);
        return ctx;
    }

    @After
    public void cleanup() {
        DriverFailureSignal.reset(); // 静态信号，必须清（防污染其它测试）
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
    }

    private Consumer<Route> handlerFor(String urlPattern) {
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> handlerCaptor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx, org.mockito.Mockito.atLeastOnce()).route(urlCaptor.capture(), handlerCaptor.capture(), any());
        List<String> urls = urlCaptor.getAllValues();
        List<Consumer<Route>> handlers = handlerCaptor.getAllValues();
        for (int i = urls.size() - 1; i >= 0; i--) {
            if (urls.get(i).equals(RoutePatterns.normalize(urlPattern))) {
                return handlers.get(i);
            }
        }
        throw new AssertionError("no handler bound for pattern: " + urlPattern);
    }

    private Request mockRequest(String url, String method) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn(method);
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        return request;
    }

    private Route mockRoute(String url) {
        Request request = mockRequest(url, "GET");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        return route;
    }

    /** 断言①：失败信号后 dispatch 直接 fallback 放行，不拦截、不观测、不终结其它路径。 */
    @Test
    public void failFastDispatchFallsThroughWithoutIntercepting() {
        mockContext();
        RouteDsl.on(ctx)
                .api("/api/error/**")
                .monitor()
                .expectStatus(200)
                .timeout(30)
                .done()
                .start();
        Consumer<Route> handler = handlerFor("/api/error/**");
        DriverFailureSignal.report();

        Route route = mockRoute("http://h/api/error/boom");
        handler.accept(route); // 事件线程 dispatch

        verify(route).fallback();
        verify(route, never()).resume();
        verify(route, never()).fulfill(any());
        verify(route, never()).abort(anyString());
    }

    /** 断言②+③：失败信号触发一次幂等挂起收敛——DELAY 挂起的请求立即 fallback，且只收敛一次。 */
    @Test
    public void failFastSettlesPendingDelayedRequestsOnce() throws Exception {
        mockContext();
        RouteDsl.on(ctx)
                .api("/api/slow/**")
                .delay(30) // 30s 延迟 → 进入 IO_AWAIT，正常要等 30s / sweep 35s
                .done()
                .start();
        Consumer<Route> handler = handlerFor("/api/slow/**");

        Route pending = mockRoute("http://h/api/slow/1");
        handler.accept(pending); // 事件线程返回 pending，请求被驱动挂起

        Thread.sleep(80);
        verify(pending, never()).resume(); // 确实在延迟窗口内（未被立即放行）

        // 驱动失败 → 后续任意 dispatch 触发收敛（幂等，一次）
        DriverFailureSignal.report();
        Route second = mockRoute("http://h/api/slow/2");
        handler.accept(second); // fail-fast 直通
        verify(second).fallback();

        // 之前的挂起被强制落定（不等 sweep）：closeAll → fallback
        verify(pending, org.mockito.Mockito.timeout(2000)).fallback();

        // 收敛只做一次：再触发一次 dispatch，挂起集合已空，不会重复 fallback 任何 route
        Route third = mockRoute("http://h/api/slow/3");
        handler.accept(third);
        verify(third).fallback();
        verify(pending, org.mockito.Mockito.timeout(500).times(1)).fallback();
    }

    /** 无失败信号时行为不变：正常 dispatch（monitor 不终结，resume 放行）——防误伤正常路径。 */
    @Test
    public void normalDispatchStillWorksWithoutFailSignal() {
        mockContext();
        RouteDsl.on(ctx)
                .api("/api/ok/**")
                .monitor()
                .expectStatus(200)
                .timeout(30)
                .done()
                .start();
        Consumer<Route> handler = handlerFor("/api/ok/**");

        Route route = mockRoute("http://h/api/ok/1");
        handler.accept(route);

        verify(route, never()).fallback(); // 未失败 → 走正常调度链（轮询观测），不直通
        verify(route, never()).fulfill(any());
    }
}

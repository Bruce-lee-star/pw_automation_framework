package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RoutePatterns;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F5 DELAY+intercept 预取物化（P1 修复回归）—— 对照问题清单 C7「DELAY+intercept 组合竞态」。
 *
 * <p><b>背景</b>：DELAY 与 intercept MOCK 组合时，老实现把 fetch（同步驱动调用）留在<b>调度器线程</b>
 * 的到点回调里 → 与主线程并发驱动同一条连接（O1/O1b 已消除的竞态面重新出现）。</p>
 *
 * <p><b>修法断言</b>（{@code RouteDispatcher.dispatchDelayIntercept}）：
 * <ul>
 *   <li><b>① 物化在调用线程</b>：{@code handler.accept(route)} 返回前，fetch 已发生（同步完成）；
 *       且响应体已被读走（{@code dispose()} 立即释放句柄）——交付段不再触碰任何驱动句柄；</li>
 *   <li><b>② 延迟真实生效</b>：dispatch 返回后延迟窗口内 <b>绝不能</b> 发生 fulfill（历史缺陷=立即放行）；</li>
 *   <li><b>③ 到点交付</b>：延迟窗口结束后 fulfill 必然发生，真实时延 ≥ delay 的 5/6；
 *       <b>交付走 fulfill 而非 resume</b>（intercept 语义：真实响应返回前端）；</li>
 *   <li><b>④ 零二次 fetch</b>：交付段只做 fulfillWithBody —— fetch 恰好发生一次（物化那次），
 *       到点回调绝不再次 fetch。</li>
 * </ul>
 */
public class DelayInterceptMaterializeTest {

    private BrowserContext ctx;
    private APIRequestContext apiCtx;
    private APIResponse apiResponse;
    private RouteRuntime rt;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        doAnswer(inv -> null).when(ctx).onClose(any());
        apiCtx = mock(APIRequestContext.class);
        apiResponse = mock(APIResponse.class);
        when(apiResponse.status()).thenReturn(200);
        when(apiResponse.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(apiResponse.body()).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        when(apiCtx.fetch(anyString(), any())).thenReturn(apiResponse);
        when(ctx.request()).thenReturn(apiCtx);
        rt = RouteEngine.runtimeOf(ctx);
        return ctx;
    }

    @After
    public void cleanup() {
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

    @Test
    public void delayInterceptMaterializesOnCallingThreadAndDeliversAfterDelay() throws Exception {
        long delayMs = 1000; // delay(1) = 1000ms
        mockContext();
        // 同一 pattern 注册两个能力位（DELAY + MOCK intercept）→ dispatch 合并 spec 同时命中
        RouteDsl.on(ctx)
                .api("/api/pay/**")
                .mock()
                .interceptResponse()
                .done()
                .api("/api/pay/**")
                .delay(1)
                .done()
                .start();
        Consumer<Route> handler = handlerFor("/api/pay/**");

        Route route = mockRoute("http://h/api/pay/1");
        AtomicLong fulfillAtNanos = new AtomicLong(-1);
        doAnswer(inv -> {
            fulfillAtNanos.set(System.nanoTime());
            return null;
        }).when(route).fulfill(any());

        long t0 = System.nanoTime();
        handler.accept(route); // 事件线程：物化（fetch）应在调用线程同步完成

        // 断言①：物化发生在调用线程 —— accept 返回时 fetch 已发生且响应已释放
        verify(apiCtx, times(1)).fetch(anyString(), any());
        verify(apiResponse, times(1)).dispose();

        // 断言②：延迟窗口内绝不能 fulfill（历史缺陷=立即放行）
        Thread.sleep(120);
        assertEquals("DELAY+intercept 期间请求被立即交付——物化后未等待延迟（F5 未生效或回归）",
                (long) -1, (long) fulfillAtNanos.get());

        // 断言③：延迟窗口结束后 fulfill 必然发生（交付走 fulfill，非 resume），真实时延 ≥ delay 的 5/6
        // 方案 B（2026-10-11）：交付由 main 业务线程 drain 执行——本测试线程模拟 main：轮询 drain。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fulfillAtNanos.get() < 0 && System.nanoTime() < deadline) {
            rt.drainMainThreadActions(); // main 业务线程 seam（方案 B：交付在 main 串行执行）
            Thread.sleep(20);
        }
        rt.drainMainThreadActions();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(fulfillAtNanos.get() - t0);
        assertTrue("DELAY+intercept 交付从未发生（fulfill 缺失）", fulfillAtNanos.get() > 0);
        assertTrue("真实时延不足: " + elapsedMs + "ms（要求 delay=" + delayMs + "ms）",
                elapsedMs >= delayMs * 5 / 6);

        verify(route, never()).resume(); // intercept 语义：交付 = fulfill 真实响应，不是 resume
        verify(apiCtx, times(1)).fetch(anyString(), any()); // 断言④：零二次 fetch（交付段无驱动调用）
    }
}

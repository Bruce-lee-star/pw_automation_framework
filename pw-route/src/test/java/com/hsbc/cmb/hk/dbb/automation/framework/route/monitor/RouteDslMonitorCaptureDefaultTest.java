package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code .monitor()} 的<b>采集默认</b>契约：业务层只写 {@code .monitor()}（不写 {@code .capture()}、
 * 不写 {@code .captureBody(true)}）就默认采集<b>记录 + 响应体</b>；{@code .capture(false)} /
 * {@code .captureBody(false)} 可显式关闭。
 *
 * <p>之所以是"默认即采"而不是"显式开"：采集是横切观测，业务层的意图就是"盯住这个接口"，
 * 而 {@code .capture()} 此前还兼任"让纯观测规则成为活跃能力位"的职责（{@code RouteDispatcher}
 * 的 {@code hasCapture}）—— 默认开启同时消除这两个隐性负担。
 *
 * <p>夹具沿用 {@code RouteDslFromCapturedTest}：mock {@link BrowserContext} + 捕获
 * {@code ctx.route(...)} 的驱动 handler；响应侧由运行时按 {@code Request#existingResponse()}
 * 有界轮询取得（真实链路同款），故本用例为"驱动 + 有界等待"而非直接调用 sink。
 */
public class RouteDslMonitorCaptureDefaultTest {

    private static final String CAP_BODY = "{\"code\":0,\"name\":\"by-default\"}";
    private static final String OFF_BODY = "{\"code\":0,\"name\":\"switched-off\"}";

    private BrowserContext ctx;

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
    }

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        org.mockito.Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        when(ctx.request()).thenReturn(mock(APIRequestContext.class));
        return ctx;
    }

    private static Request mockRequest(String url, Response response) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        when(request.existingResponse()).thenReturn(response);
        return request;
    }

    private static Response mockResponse(String body) {
        Response response = mock(Response.class);
        when(response.status()).thenReturn(200);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private Consumer<Route> handler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx).route(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    /** 驱动一次 route 事件，并在给定窗口内等待采集落库（响应侧为有界轮询，故不立即断言）。 */
    private List<CapturedApiCall> driveAndCollect(String url, String body, long waitMs) {
        Request request = mockRequest(url, mockResponse(body));
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        handler().accept(route);

        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        List<CapturedApiCall> calls = RouteEngine.allCapturedApis(ctx);
        while (calls.isEmpty() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(20_000_000L);
            calls = RouteEngine.allCapturedApis(ctx);
        }
        return calls;
    }

    @Test
    public void monitorCapturesRecordAndBodyWithoutExplicitCaptureCalls() {
        mockContext();
        RouteEngine.runtimeOf(ctx);

        // 业务层写法：只有 .monitor()，没有任何 .capture() / .captureBody(true)
        RouteDsl.on(ctx).api("/api/cap").monitor().done().start();

        List<CapturedApiCall> calls = driveAndCollect("https://host/api/cap", CAP_BODY, 5_000L);

        assertEquals("默认即采集（无需 .capture()）", 1, calls.size());
        assertEquals("默认连响应体一起采（无需 .captureBody(true)）", CAP_BODY, calls.get(0).responseBody());
        assertEquals("状态码来自真实响应", 200L, (long) calls.get(0).responseStatus());
    }

    @Test
    public void explicitFalseTurnsTheDefaultOff() {
        mockContext();
        RouteEngine.runtimeOf(ctx);

        RouteDsl.on(ctx).api("/api/off").monitor().capture(false).captureBody(false).done().start();

        List<CapturedApiCall> calls = driveAndCollect("https://host/api/off", OFF_BODY, 1_000L);

        assertTrue("显式 .capture(false) 后不得产生采集记录", calls.isEmpty());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code MockDsl.fromCaptured} 的契约：<b>注册期即时快照</b> —— 采集体被关闭（等价于
 * {@code CaptureSink.reset()} 删除临时文件）之后，已注册的 Mock 规则仍能原样回放。
 *
 * <p>同时钉住 <b>字节保真</b>：{@code mockBody(byte[])} 必须经 {@code FulfillOptions.setBodyBytes}
 * 逐字节交付（含非法 UTF-8 序列），不得再经受字符串往返而损坏。
 *
 * <p>夹具口径沿用 {@code RouteMockModifyTest}：mock {@link BrowserContext} + 捕获
 * {@code ctx.route(...)} 的驱动 handler + {@link Route.FulfillOptions} 的公有字段断言。
 */
public class RouteDslFromCapturedTest {

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

    private static Request mockRequest(String url) {
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
        return request;
    }

    private Consumer<Route> captureHandler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx).route(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    public void replaysTheCapturedResponseEvenAfterTheCarrierIsClosed() {
        mockContext();
        RouteEngine.runtimeOf(ctx);

        byte[] raw = "{\"code\":0,\"name\":\"captured\"}".getBytes(StandardCharsets.UTF_8);
        CapturedBody body = BodyStore.of(raw, "application/json", 1024, 1024);
        CapturedApiCall captured = new CapturedApiCall("/api/cap", "GET", "https://host/api/cap",
                null, null, 200, Map.of("x-trace", "t-1"),
                null, false, 12L, false, 1L, null, "MONITOR", "application/json", null, body);

        RouteDsl.on(ctx).api("/api/cap").mock().fromCaptured(captured).register();

        // 采集体由 sink 拥有：此处显式关闭（等价 reset() / 驱逐时删除临时文件），
        // 若 fromCaptured 只留引用，回放将失败或读到脏数据 —— 这正是本用例要钉住的契约。
        body.close();

        Consumer<Route> handler = captureHandler();
        Request request = mockRequest("https://host/api/cap");   // 先建请求：避免 when(...) 内嵌套 stubbing
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        handler.accept(route);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor =
                ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        assertEquals("回放体必须是采集到的原文", "{\"code\":0,\"name\":\"captured\"}",
                captor.getValue().body);
        assertEquals("状态码必须来自采集记录", 200L, (long) captor.getValue().status);
    }

    @Test
    public void binaryMockBodyIsDeliveredByteExact() {
        mockContext();
        RouteEngine.runtimeOf(ctx);

        // 含非法 UTF-8 序列（0xFF / 0x80）与 0x00：任何字符串往返都会损坏
        byte[] binary = new byte[] {0x00, (byte) 0xFF, 0x1F, (byte) 0x8B, (byte) 0x80, 0x7F};
        RouteDsl.on(ctx).api("/api/bin").mock()
                .status(200).contentType("application/octet-stream").mockBody(binary)
                .register();

        Consumer<Route> handler = captureHandler();
        Request request = mockRequest("https://host/api/bin");
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        handler.accept(route);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Route.FulfillOptions> captor =
                ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        verify(route).fulfill(captor.capture());
        org.junit.Assert.assertArrayEquals("字节体必须逐字节保真（不得经字符串往返）",
                binary, captor.getValue().bodyBytes);
        assertEquals("字节通道下不得再设字符串体", null, captor.getValue().body);
    }
}

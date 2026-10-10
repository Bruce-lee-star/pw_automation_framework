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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F8 Mock 保真回归 —— 对照问题清单「mock 不应破坏真实响应语义」。
 *
 * <p><b>背景</b>（2026-10-10 实测）：mock 响应体一律是<b>已被 Playwright 解码</b>的明文（或重编码后的
 * 字节），与服务器原始 wire 头不再一致。若把 {@code content-encoding: gzip} / {@code content-length} /
 * {@code transfer-encoding} 原样照抄给 fulfill，浏览器会按 gzip 去解一个已经不匹配的体
 * （实测：gzip 响应改字段后照抄 gzip 头 ⇒ 页面取不到数据 ⇒ 顶栏下拉/首页局部空白）。</p>
 *
 * <p><b>断言域</b>：
 * <ul>
 *   <li><b>① 体编码/长度头重算</b>：静态体与 intercept 真实响应两条路径，fulfill 交付的头
 *       一律不得含 {@code content-encoding} / {@code content-length} / {@code transfer-encoding}
 *       （{@code RouteAction.sanitizeBodyHeaders} 剥离；大小写不敏感），其余业务头（content-type 等）保留；</li>
 *   <li><b>② 非文本体字节保真</b>：二进制 Mock 体 + 字段替换 → 替换对二进制"不适用"
 *       （{@code FieldReplacer} 契约：明确跳过、保留原体）→ <b>原字节</b>经
 *       {@link Route.FulfillOptions#setBodyBytes} 交付，绝不丢失响应体；</li>
 *   <li><b>③ intercept 替换生效且头重算</b>：真实响应 + 字段替换 → JSON 替换成功 + 三头剥离；</li>
 *   <li><b>④ 采集 → 回放保真</b>：{@code fromCaptured} 采集记录回放为静态 Mock，交付体与采集体一致。</li>
 * </ul>
 */
public class MockFidelityTest {

    private BrowserContext ctx;
    private APIRequestContext apiCtx;
    private APIResponse apiResponse;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        doAnswer(inv -> null).when(ctx).onClose(any());
        apiCtx = mock(APIRequestContext.class);
        apiResponse = mock(APIResponse.class);
        when(ctx.request()).thenReturn(apiCtx);
        RouteEngine.runtimeOf(ctx);
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

    /** dispatch 并捕获 fulfill 交付选项（mockTask 就地执行，accept 返回即已交付）。 */
    private Route.FulfillOptions dispatchAndCaptureFulfill(String urlPattern, String requestUrl) {
        Consumer<Route> handler = handlerFor(urlPattern);
        Route route = mockRoute(requestUrl);
        AtomicReference<Route.FulfillOptions> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return null;
        }).when(route).fulfill(any());
        handler.accept(route);
        assertNotNull("route.fulfill 未被调用（mock 未交付）——url=" + requestUrl, captured.get());
        return captured.get();
    }

    private static void assertNoBodyEncodingHeaders(Map<String, String> headers) {
        Set<String> forbidden = Set.of("content-encoding", "content-length", "transfer-encoding");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String lower = e.getKey().toLowerCase(java.util.Locale.ROOT);
            assertFalse("fulfill 交付头不得含 body-encoding 头（应由 Playwright 依最终体重算）: " + e.getKey(),
                    forbidden.contains(lower));
        }
    }

    /** 断言①（静态路径）：静态 JSON 体 + 字段替换 + 声明 gzip/长度/分块头 → 三头剥离、替换生效、业务头保留。 */
    @Test
    public void staticJsonReplacementStripsBodyEncodingHeaders() {
        mockContext();
        RouteDsl.on(ctx)
                .api("/api/gzip/**")
                .mock()
                .mockStatus(200)
                .mockHeader("content-encoding", "gzip")
                .mockHeader("content-length", "999")
                .mockHeader("transfer-encoding", "chunked")
                .mockHeader("content-type", "application/json; charset=utf-8")
                .contentType("application/json")   // 替换语义按响应 Content-Type 路由（不把 JSON 当默认）
                .mockBody("{\"data\":{\"flag\":true,\"name\":\"orig\"}}")
                .mockReplaceField("$.data.flag", false)
                .done()
                .start();

        Route.FulfillOptions opts = dispatchAndCaptureFulfill("/api/gzip/**", "http://h/api/gzip/data");

        assertNoBodyEncodingHeaders(opts.headers);
        // contentType(...) DSL 是 content-type 的语义权威（覆盖 mockHeader 的完整串）——满足"业务头保留"
        assertEquals("业务头 content-type 必须保留（以 contentType DSL 为准）",
                "application/json",
                opts.headers.get("content-type"));
        assertEquals(Integer.valueOf(200), opts.status);
        assertNotNull("替换后 body 不得为空", opts.body);
        assertTrue("JSON 字段替换未生效: body=" + opts.body, opts.body.contains("\"flag\":false"));
        assertTrue("原字段不应残留", !opts.body.contains("\"flag\":true"));
    }

    /** 断言②（字节保真通道）：二进制 Mock 体 + 字段替换 → 替换跳过、原字节原样交付、三头剥离。 */
    @Test
    public void staticBinaryBodyWithReplacementDeliversOriginalBytes() {
        mockContext();
        byte[] bin = {0x00, 0x01, 0x7F, (byte) 0xFF, 0x10, 0x42, 0x00};
        RouteDsl.on(ctx)
                .api("/api/bin/**")
                .mock()
                .mockStatus(200)
                .mockHeader("content-encoding", "gzip")
                .mockHeader("content-type", "application/octet-stream")
                .mockBody(bin)
                .mockReplaceField("$.nope", 1)
                .done()
                .start();

        Route.FulfillOptions opts = dispatchAndCaptureFulfill("/api/bin/**", "http://h/api/bin/blob");

        assertNoBodyEncodingHeaders(opts.headers);
        assertArrayEquals("二进制 Mock 体必须逐字节原样交付（replaceField 对二进制不适用，不得丢体）",
                bin, opts.bodyBytes);
    }

    /** 断言①（intercept 路径）：真实 gzip 响应 + 字段替换 → JSON 替换生效 + 三头剥离（2026-10-10 实测修复）。 */
    @Test
    public void interceptGzipResponseStripsEncodingHeadersAndReplacesField() {
        mockContext();
        when(apiResponse.status()).thenReturn(200);
        when(apiResponse.headers()).thenReturn(Map.of(
                "content-type", "application/json; charset=utf-8",
                "content-encoding", "gzip",      // 服务器真实 wire 头（照抄会致页面空白）
                "content-length", "123"));
        when(apiResponse.body()).thenReturn("{\"a\":1,\"b\":\"orig\"}".getBytes(StandardCharsets.UTF_8));
        when(apiCtx.fetch(anyString(), any())).thenReturn(apiResponse);

        RouteDsl.on(ctx)
                .api("/api/intercept/**")
                .mock()
                .interceptResponse()
                .mockReplaceField("$.a", 2)
                .done()
                .start();

        Route.FulfillOptions opts = dispatchAndCaptureFulfill("/api/intercept/**", "http://h/api/intercept/x");

        assertNoBodyEncodingHeaders(opts.headers);
        assertEquals("content-type 保留", "application/json; charset=utf-8", opts.headers.get("content-type"));
        assertNotNull(opts.body);
        assertTrue("intercept 字段替换未生效: body=" + opts.body, opts.body.contains("\"a\":2"));
        verify(apiCtx, times(1)).fetch(anyString(), any());
    }

    /** 断言④（采集 → 回放保真）：fromCaptured 文本记录回放为静态 Mock，交付体与采集体一致。 */
    @Test
    public void fromCapturedRoundTripPreservesBody() {
        mockContext();
        // CapturedApiCallView 为 final 类不可 mock；用真实采集记录（文本通道，无载体）走 fromCaptured。
        com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall captured =
                com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall.of(
                        "/api/replay/**", "GET", "http://h/api/replay/x",
                        Map.of(), null, 200, Map.of("content-type", "application/json"),
                        "{\"orig\":true,\"n\":1}", false, 42L, false);

        RouteDsl.on(ctx)
                .api("/api/replay/**")
                .mock()
                .fromCaptured(captured)
                .done()
                .start();

        Route.FulfillOptions opts = dispatchAndCaptureFulfill("/api/replay/**", "http://h/api/replay/x");

        assertEquals("采集→回放保真：交付体必须与采集体一致", "{\"orig\":true,\"n\":1}", opts.body);
        assertEquals("采集的 content-type 应随回放交付", "application/json",
                opts.headers.get("content-type"));
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.ApiCaptureQuery;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiCaptureQueryImpl} 契约（此前 0 覆盖）：SPI 的 {@code Object context} → {@code BrowserContext}
 * 的 <b>null-safe 转型</b>、6 个查询方法到 per-context sink 的映射、<b>按 Context 隔离</b>、
 * {@code resetCapturedApis} 清空、以及只读视图（{@code CapturedApiCallView}）的字段映射。
 *
 * <p>这是 {@code ApiCaptureContext}（pw-web-ui 业务门面）依赖的<b>唯一实现</b>：门面自身的转发/降级
 * 由 pw-web-ui 侧 {@code ApiCaptureContextTest} 覆盖，本类覆盖它背后的真实读写语义。
 *
 * <p>夹具沿用框架既有做法：mock {@link BrowserContext} + 捕获 {@code ctx.route(...)} 的 handler 驱动
 * 一次真实往返（响应侧由运行时按 {@code Request#existingResponse()} 有界轮询取得）。
 */
public class ApiCaptureQueryImplTest {

    private static final String CAP_URL = "https://host/api/cap";
    private static final String CAP_PATTERN = "/api/cap";
    private static final String CAP_BODY = "{\"code\":0,\"name\":\"cap\"}";

    private final ApiCaptureQuery spi = new ApiCaptureQueryImpl();

    private BrowserContext ctx;
    private BrowserContext other;

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
        if (other != null) {
            RouteEngine.shutdown(other);
        }
    }

    @Test
    public void queriesMapThePerContextSinkThroughTheSpi() {
        ctx = mockContext();
        RouteEngine.runtimeOf(ctx);
        // 无断言 .monitor() 规则（默认即采，含响应体）
        RouteDsl.on(ctx).api(CAP_PATTERN).monitor().done().start();
        driveAndWait(ctx, CAP_URL);

        List<CapturedApiCallView> all = spi.getAllApiCalls(ctx);
        assertEquals("per-context sink 的采集记录必须经 SPI 可见", 1, all.size());
        CapturedApiCallView view = all.get(0);
        assertEquals(CAP_URL, view.url());
        assertEquals(CAP_PATTERN, view.pattern());
        assertEquals(200L, (long) view.responseStatus());
        assertEquals("只读视图必须携带解码后的响应体（门面断言依赖它）", CAP_BODY, view.responseBody());
        assertEquals("application/json", view.contentType());
        assertNotNull("渠道信息必须可见（MONITOR 主渠道）", view.channels());

        assertEquals("每个 pattern 的最近一次记录", 1, spi.getAllLastApiCalls(ctx).size());

        // lastOf(endpoint)：pattern 精确匹配优先，其次 URL 前缀
        CapturedApiCallView last = spi.getLastApiCall(ctx, CAP_PATTERN);
        assertNotNull("按 pattern 必须能取到最近一次记录", last);
        assertEquals(CAP_URL, last.url());

        assertEquals("按 URL 查询", 1, spi.getCallsByUrl(ctx, CAP_URL).size());
        assertEquals("裸 .monitor() 规则按主渠道可查（MONITOR 大小写不敏感）",
                1, spi.getCallsByChannel(ctx, "MONITOR").size());
        assertEquals("渠道名大小写不敏感", 1, spi.getCallsByChannel(ctx, "monitor").size());

        assertNotNull("已存在记录必须立即可得",
                spi.waitForApi(ctx, v -> CAP_URL.equals(v.url()), 2_000L));
        assertNull("无匹配记录应超时返回 null", spi.waitForApi(ctx, v -> false, 50L));
        assertNull("predicate 为 null 必须安全返回 null（不得 NPE）", spi.waitForApi(ctx, null, 50L));
    }

    @Test
    public void implementationSelfRegistersIntoTheRegistry() {
        // 触碰实现类 ⇒ 其静态块自注册 ⇒ pw-web-ui 业务门面经 Registry.get() 零反射取用（本类构造即触发）
        assertEquals("SPI 实现必须在类初始化时自注册（业务门面的可用性前提）",
                ApiCaptureQueryImpl.class, com.hsbc.cmb.hk.dbb.automation.framework.common.route
                        .ApiCaptureQueryRegistry.get().getClass());
    }

    @Test
    public void nonBrowserContextOrNullDegradeToEmptyResults() {
        assertEquals("null context ⇒ 空结果", 0, spi.getAllApiCalls(null).size());
        assertEquals("非 BrowserContext ⇒ 按无该 context 处理", 0, spi.getAllApiCalls("not-a-context").size());
        assertEquals(0, spi.getAllLastApiCalls(null).size());
        assertNull(spi.getLastApiCall(null, CAP_PATTERN));
        assertNull("非 BrowserContext 同样降级为 null", spi.getLastApiCall(new Object(), CAP_PATTERN));
        assertEquals(0, spi.getCallsByUrl(null, CAP_URL).size());
        assertEquals(0, spi.getCallsByChannel(null, "MONITOR").size());
        assertNull(spi.waitForApi(null, v -> true, 10L));
        // reset 对无 context 场景必须安全（幂等、不抛）
        spi.resetCapturedApis(null);
        spi.resetCapturedApis(new Object());
    }

    @Test
    public void recordsAreIsolatedPerContext() {
        ctx = mockContext();
        RouteEngine.runtimeOf(ctx);
        RouteDsl.on(ctx).api(CAP_PATTERN).monitor().done().start();
        driveAndWait(ctx, CAP_URL);
        assertEquals(1, spi.getAllApiCalls(ctx).size());

        other = mockContext();
        RouteEngine.runtimeOf(other);
        assertEquals("另一 Context 必须看不到本 Context 的采集（隔离维度 = Context）",
                0, spi.getAllApiCalls(other).size());
        assertNull(spi.getLastApiCall(other, CAP_PATTERN));
    }

    @Test
    public void resetClearsTheContextRecords() {
        ctx = mockContext();
        RouteEngine.runtimeOf(ctx);
        RouteDsl.on(ctx).api(CAP_PATTERN).monitor().done().start();
        driveAndWait(ctx, CAP_URL);
        assertEquals(1, spi.getAllApiCalls(ctx).size());

        spi.resetCapturedApis(ctx);

        assertEquals("reset 必须清空该 Context 的采集（查询随之归零）", 0, spi.getAllApiCalls(ctx).size());
        assertNull(spi.getLastApiCall(ctx, CAP_PATTERN));
    }

    // ── 夹具 ──

    private BrowserContext mockContext() {
        BrowserContext context = mock(BrowserContext.class);
        when(context.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        org.mockito.Mockito.doAnswer(inv -> null).when(context).onClose(any());
        when(context.request()).thenReturn(mock(APIRequestContext.class));
        return context;
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

    /** 驱动一次 route 事件，并有界等待采集落库（响应侧为有界轮询，故不立即断言）。 */
    private static void driveAndWait(BrowserContext context, String url) {
        Request request = mockRequest(url, mockResponse(CAP_BODY));
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(context).route(anyString(), captor.capture(), any());
        captor.getValue().accept(route);

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (RouteEngine.allCapturedApis(context).isEmpty() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(20_000_000L);
        }
        assertTrue("采集必须在有界窗口内落库（夹具前提）", !RouteEngine.allCapturedApis(context).isEmpty());
    }
}

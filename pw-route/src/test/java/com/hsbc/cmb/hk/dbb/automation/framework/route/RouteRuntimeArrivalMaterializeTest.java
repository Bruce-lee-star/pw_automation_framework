package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
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
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到达即读（2026-10-16）：响应被 main drain 检出到达后，<b>同批 drain 收尾即读 body</b>，
 * 不再延迟到下一次采集查询——即使到达是在 {@code drainSettledAssertionFailures/register/close}
 * 这类<b>非查询型 drain</b>里检出的。
 *
 * <p>锁定两点：① dispatch（事件线程）只持句柄、<b>不读 body</b>（{@code onResponseForSpec} 零协议）；
 * ② {@code drainMainThreadActions()}（业务线程）收尾的 {@code materializeArrivedBodies} 读 body。
 * body() 是协议调用，必须在业务线程——这正是把物化挂在 drain 收尾而非调度线程的原因。</p>
 */
public class RouteRuntimeArrivalMaterializeTest {

    private BrowserContext ctx;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(AutoCloseable.class));
        doAnswer(inv -> null).when(ctx).onClose(org.mockito.ArgumentMatchers.any());
        return ctx;
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
    }

    @Test
    public void drainMaterializesBodyOnArrivalWithoutWaitingForQuery() {
        mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> handlerCaptor = ArgumentCaptor.forClass((Class) Consumer.class);
        RouteDsl.on(ctx).api("/api/users/**").monitor().expectStatus(200).register();
        verify(ctx).route(org.mockito.ArgumentMatchers.anyString(), handlerCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Consumer<Route> handler = handlerCaptor.getValue();

        // 请求侧：existingResponse() 已能取到响应（模拟响应已到达）
        Request request = mock(Request.class);
        when(request.url()).thenReturn("https://host/api/users/1");
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);

        Response response = mock(Response.class);
        when(response.status()).thenReturn(200);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn("{\"recovered\":true}".getBytes(StandardCharsets.UTF_8));
        when(request.existingResponse()).thenReturn(response);

        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);

        // 事件线程分发：recordObservation → 首轮轮询检出到达 → deliverResponse 持句柄（零协议，不读 body）
        handler.accept(route);
        verify(response, never()).body(); // 断言①：dispatch 只持句柄，绝不读 body

        // 业务线程 drain：收尾 materializeArrivedBodies → 到达即读
        runtime.drainMainThreadActions();
        verify(response, atLeastOnce()).body(); // 断言②：drain 读到了 body（而非拖到下一次查询）

        // 物化结果可被采集查询拿到（body 已在 drain 时读定）
        List<com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall> calls =
                runtime.dumpCapturedApis();
        assertFalse("drain 后应有采集记录", calls.isEmpty());
        assertTrue("到达即读：记录里必须带 body", calls.get(0).responseBody().contains("recovered"));
        assertEquals((long) 200L, (long) calls.get(0).responseStatus());
    }
}

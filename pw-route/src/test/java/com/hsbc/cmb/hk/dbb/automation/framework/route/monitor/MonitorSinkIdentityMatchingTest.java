package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 Monitor 响应侧<b>按请求身份精确配对</b>（而非 FIFO 队首）：
 * 同 pattern 并发多请求时，响应必须结算到"与其共享同一 {@link Request} 引用"的交换，
 * 否则会出现"响应 B 错误结算请求 A 的交换"的串扰。
 */
public class MonitorSinkIdentityMatchingTest {

    private MonitorSink newSink() {
        return new MonitorSink();
    }

    private Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(url);
        return request;
    }

    private Response mockResponse(String url, int status) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        return response;
    }

    @Test
    public void responseSettlesOnlyMatchingRequestIdentity() {
        ApiSpec spec = ApiSpec.builder("/api/x", RouteCapability.MONITOR).expectStatus(200).build();

        Request r1 = mockRequest("https://host/api/x");
        Request r2 = mockRequest("https://host/api/x"); // 同 pattern 并发第二请求
        MonitorSink sink = newSink();
        assertTrue(sink.recordRequest(r1, spec));
        assertTrue(sink.recordRequest(r2, spec));

        // 仅按身份投递 r2 的响应
        sink.onResponseForSpec(spec, r2, mockResponse("https://host/api/x", 200));

        List<CapturedExchange> all = sink.drain();
        assertEquals(2, all.size());
        CapturedExchange e2 = all.stream().filter(e -> e.request() == r2).findFirst().orElseThrow();
        CapturedExchange e1 = all.stream().filter(e -> e.request() == r1).findFirst().orElseThrow();
        assertEquals(200L, (long) e2.responseStatus());           // r2 被精确结算
        assertNull("r1 仍 pending，不得被 FIFO 误结算（旧 FIFO 行为会误结算 r1）", e1.responseStatus());
    }

    @Test
    public void bothIdentitiesEventuallySettled() {
        ApiSpec spec = ApiSpec.builder("/api/x", RouteCapability.MONITOR).expectStatus(200).build();
        Request r1 = mockRequest("https://host/api/x");
        Request r2 = mockRequest("https://host/api/x");
        MonitorSink sink = newSink();
        sink.recordRequest(r1, spec);
        sink.recordRequest(r2, spec);

        sink.onResponseForSpec(spec, r1, mockResponse("https://host/api/x", 200));
        sink.onResponseForSpec(spec, r2, mockResponse("https://host/api/x", 200));

        List<CapturedExchange> all = sink.drain();
        assertEquals(2, all.size());
        assertTrue("两个身份都应被各自结算为 200", all.stream()
                .allMatch(e -> e.request() != null && e.responseStatus() != null && e.responseStatus() == 200));
    }

    @Test
    public void nullRequestFallsBackToFifo() {
        ApiSpec spec = ApiSpec.builder("/api/x", RouteCapability.MONITOR).expectStatus(200).build();
        Request r1 = mockRequest("https://host/api/x");
        Request r2 = mockRequest("https://host/api/x");
        MonitorSink sink = newSink();
        sink.recordRequest(r1, spec);
        sink.recordRequest(r2, spec);

        // 2-arg（request=null）退化为 FIFO：投递任一响应都结算队首 r1
        sink.onResponseForSpec(spec, mockResponse("https://host/api/x", 200));

        List<CapturedExchange> all = sink.drain();
        assertEquals(2, all.size());
        CapturedExchange e1 = all.stream().filter(e -> e.request() == r1).findFirst().orElseThrow();
        CapturedExchange e2 = all.stream().filter(e -> e.request() == r2).findFirst().orElseThrow();
        assertEquals(200L, (long) e1.responseStatus()); // FIFO 取队首 r1
        assertNull(e2.responseStatus());
    }
}

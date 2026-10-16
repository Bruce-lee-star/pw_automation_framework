package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CaptureSink 请求身份精确配对验证：同 pattern 并发多请求、响应乱序到达时，
 * 响应只归档到"与其共享同一 {@link Request} 引用"的交换，杜绝旧 FIFO 盲取导致的
 * "响应 B 结算/归档请求 A 的交换"串扰。
 *
 * <p>与 {@link MonitorSinkIdentityMatchingTest} 同源，固化 {@code RouteRuntimeImpl} 把
 * {@code request} 透传给 {@link CaptureSink#onResponseForSpec(ApiSpec, Request, Response)} 的契约。</p>
 */
public class CaptureSinkIdentityMatchingTest {

    private Request mockRequest(String url, String id) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("POST");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(request.postData()).thenReturn("{\"id\":\"" + id + "\"}");
        return request;
    }

    private Response mockResponse(String url, int status) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        return response;
    }

    @Test
    public void outOfOrderResponsesSettleMatchingRequestsByIdentity() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = new CaptureSink(100, 30_000);

        Request reqA = mockRequest("https://host/api/users/A", "A");
        Request reqB = mockRequest("https://host/api/users/B", "B");
        sink.recordRequest(reqA, spec);
        sink.recordRequest(reqB, spec);

        // 响应乱序：B 的响应先到、A 的后到。旧 FIFO 盲取会把 B 的 500 结算给 A、A 的 200 结算给 B。
        sink.onResponseForSpec(spec, reqB, mockResponse("https://host/api/users/B", 500));
        sink.onResponseForSpec(spec, reqA, mockResponse("https://host/api/users/A", 200));

        List<CapturedApiCall> calls = sink.dump();
        assertEquals("两条请求都必须被各自响应定案", 2, calls.size());
        for (CapturedApiCall call : calls) {
            if (call.url().endsWith("/A")) {
                assertEquals("A 必须结算自己的 200（不得串成 B 的 500）", 200L, (long) call.responseStatus());
            } else {
                assertEquals("B 必须结算自己的 500（不得串成 A 的 200）", 500L, (long) call.responseStatus());
            }
        }
    }

    @Test
    public void unknownRequestResponseDoesNotSettleAnyPending() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = new CaptureSink(100, 30_000);

        Request reqA = mockRequest("https://host/api/users/A", "A");
        sink.recordRequest(reqA, spec);

        // 传入一个未登记的 Request 引用：既不能归档它（pending 无此项），也不能误消费 reqA。
        Request stranger = mockRequest("https://host/api/users/stranger", "S");
        sink.onResponseForSpec(spec, stranger, mockResponse("https://host/api/users/stranger", 200));
        assertTrue("未登记请求的响应不得定案任何交换", sink.dump().isEmpty());

        // reqA 仍可由其自身响应正常结算（证明上一步未误摘其 pending）。
        sink.onResponseForSpec(spec, reqA, mockResponse("https://host/api/users/A", 200));
        List<CapturedApiCall> calls = sink.dump();
        assertEquals("合法请求仍能结算", 1, calls.size());
        assertEquals(200L, (long) calls.get(0).responseStatus());
    }

    @Test
    public void twoArgOverloadDegradesToFifoAndKeepsBackwardCompatibility() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = new CaptureSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/users/1", "1"), spec);
        // 旧 2 参重载（无 request 身份）退化为 FIFO 队首：单一请求仍正常配对（向后兼容）。
        sink.onResponseForSpec(spec, mockResponse("https://host/api/users/1", 200));

        List<CapturedApiCall> calls = sink.dump();
        assertEquals("2 参重载向后兼容：仍能定案", 1, calls.size());
        assertEquals(200L, (long) calls.get(0).responseStatus());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CaptureSink body 读失败路径验证：{@code response.body()} 抛错时 fail-open——
 * 保留 status/headers 快照、body=null、分类计数，<b>绝不重试、绝不抛异常、绝不判场景失败</b>。
 *
 * <p>依据《网络API分层指南》§3.1：body 读失败无法跨浏览器挽回（页面关闭三浏览器俱败、Chromium 重取
 * 仅限 GET 静态安全类型、不含 JSON/XHR/POST），故只读一次。读取细节见 {@link ResponseBodyReaderTest}。</p>
 */
public class CaptureSinkBodyReadFailureTest {

    private Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("POST");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(request.postData()).thenReturn("{\"id\":\"1\"}");
        return request;
    }

    private Response baseResponse(String url) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(200);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        return response;
    }

    @Test
    public void reclaimedHandleFailsOpenWithNullBodyAndNoRetry() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR).capture(true).build();
        CaptureSink sink = new CaptureSink(100, 30_000);

        Request req = mockRequest("https://host/api/users/1");
        Response resp = baseResponse("https://host/api/users/1");
        when(resp.body()).thenThrow(
                new PlaywrightException("Object doesn't exist: response@c832e10dcc5c"));

        sink.recordRequest(req, spec);
        sink.onResponseForSpec(spec, req, resp);

        List<CapturedApiCall> calls = sink.dump();
        assertEquals("fail-open：仍应定案 1 条（保留 status/headers）", 1, calls.size());
        assertNull("读失败 ⇒ body 置 null（不抛异常、不判失败）", calls.get(0).responseBody());
        assertEquals("主读取失败计数 +1", 1L, sink.bodyReadFailureCount());
        assertEquals("句柄回收分类计数 +1", 1L, sink.handleReclaimedBodyReads());
        assertEquals("不计入真协议错误桶", 0L, sink.protocolErrorBodyReads());
        verify(resp, times(1)).body(); // 只读一次，失败后绝不重试
    }
}

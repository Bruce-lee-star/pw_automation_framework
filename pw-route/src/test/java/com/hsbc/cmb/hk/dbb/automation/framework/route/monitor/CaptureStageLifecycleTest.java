package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureStage;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * route captured body 状态分层契约（MATCHED → ARRIVED → BODY_MATERIALIZED）。
 *
 * <p>锁定四条：① 请求命中/响应到达两阶段业务完全不可见；② 只有 body 在业务线程物化后
 * 才进入业务只读查询，且跨模块视图携带 {@link CaptureStage}；③ 未物化终态（3xx / 读取失败 /
 * 超时）不污染业务查询但仍可经消费式诊断通道 {@code dump()} 查看并解释原因；
 * ④ 规则显式 {@code .captureBody(false)} 属"按配置完成"，业务可见但 body 为 null。
 *
 * <p>全部基于 Mockito 替身，不启动真实浏览器；body() 只在业务线程物化入口被调用。
 */
public class CaptureStageLifecycleTest {

    private static final String PATTERN = "/api/users/**";
    private static final String URL = "https://host/api/users/1";
    private static final String BODY = "{\"ok\":true}";

    private static ApiSpec spec(boolean captureBody) {
        return ApiSpec.builder(PATTERN, RouteCapability.MONITOR)
                .capture(true)
                .captureBody(captureBody)
                .build();
    }

    private static Request mockRequest() {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(URL);
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        return request;
    }

    private static Response mockResponse(int status, String contentType, byte[] body) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(URL);
        when(response.status()).thenReturn(status);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", contentType);
        when(response.headers()).thenReturn(headers);
        if (body != null) {
            when(response.body()).thenReturn(body);
        }
        return response;
    }

    private static CaptureSink newSink(long timeoutMs) {
        return new CaptureSink(100, timeoutMs);
    }

    private static void assertCount(CaptureSink sink, CaptureStage stage, int expected) {
        EnumMap<CaptureStage, Integer> counts = sink.stageCounts();
        assertEquals("stage=" + stage, Integer.valueOf(expected), counts.get(stage));
    }

    /** 业务只读查询在任一入口都不得看到未物化记录；诊断通道仍返回该记录并解释原因。 */
    private static void assertDiagnosticsOnly(CaptureSink sink, CaptureStage expectedStage) {
        assertTrue(sink.all().isEmpty());
        assertTrue(sink.peek().isEmpty());
        assertNull(sink.lastOf(PATTERN));
        assertTrue(sink.byUrl(URL).isEmpty());
        assertTrue(sink.byChannel("MONITOR").isEmpty());
        assertNull(sink.waitFor(call -> true, 20L));
        assertCount(sink, CaptureStage.BODY_MATERIALIZED, 0);

        List<CapturedApiCall> diagnostics = sink.dump();
        assertEquals("消费式诊断通道必须保留未物化终态", 1, diagnostics.size());
        CapturedApiCall call = diagnostics.get(0);
        assertEquals(expectedStage, call.stage());
        assertNotNull("必须说明 body 为何未物化", call.bodyUnavailableReason());
        assertFalse(call.bodyUnavailableReason().isEmpty());

        CapturedApiCallView view = CaptureSink.toView(call);
        assertEquals(expectedStage, view.stage());
        assertFalse(view.isBodyMaterialized());
        assertEquals(call.bodyUnavailableReason(), view.bodyUnavailableReason());
    }

    @Test
    public void matchedStageIsInvisibleToBusinessReads() {
        CaptureSink sink = newSink(30_000L);
        assertTrue(sink.recordRequest(mockRequest(), spec(true)));

        assertCount(sink, CaptureStage.MATCHED, 1);
        assertCount(sink, CaptureStage.ARRIVED, 0);
        assertCount(sink, CaptureStage.BODY_MATERIALIZED, 0);
        assertTrue(sink.all().isEmpty());
        assertTrue(sink.peek().isEmpty());
        assertNull(sink.lastOf(PATTERN));
        assertNull(sink.waitFor(call -> true, 20L));
    }

    @Test
    public void arrivedStageHoldsHandleWithoutReadingBody() {
        CaptureSink sink = newSink(30_000L);
        ApiSpec spec = spec(true);
        Request request = mockRequest();
        sink.recordRequest(request, spec);
        Response response = mockResponse(200, "application/json",
                BODY.getBytes(StandardCharsets.UTF_8));
        sink.onResponseForSpec(spec, request, response);

        assertCount(sink, CaptureStage.MATCHED, 0);
        assertCount(sink, CaptureStage.ARRIVED, 1);
        assertCount(sink, CaptureStage.BODY_MATERIALIZED, 0);
        verify(response, never()).body(); // 响应到达路径零协议调用
    }

    @Test
    public void bodyMaterializedIsTheOnlyBusinessVisibleStage() {
        CaptureSink sink = newSink(30_000L);
        ApiSpec spec = spec(true);
        Request request = mockRequest();
        sink.recordRequest(request, spec);
        Response response = mockResponse(200, "application/json",
                BODY.getBytes(StandardCharsets.UTF_8));
        sink.onResponseForSpec(spec, request, response);

        sink.materializeArrivedBodies();

        assertCount(sink, CaptureStage.MATCHED, 0);
        assertCount(sink, CaptureStage.ARRIVED, 0);
        assertCount(sink, CaptureStage.BODY_MATERIALIZED, 1);
        verify(response).body();

        List<CapturedApiCall> calls = sink.all();
        assertEquals(1, calls.size());
        CapturedApiCall call = calls.get(0);
        assertEquals(CaptureStage.BODY_MATERIALIZED, call.stage());
        assertNull(call.bodyUnavailableReason());

        CapturedApiCallView view = CaptureSink.toView(call);
        assertEquals(CaptureStage.BODY_MATERIALIZED, view.stage());
        assertTrue(view.isBodyMaterialized());
        assertEquals(BODY, view.responseBody());
    }

    @Test
    public void captureBodyOffStillMaterializesByConfiguration() {
        CaptureSink sink = newSink(30_000L);
        ApiSpec spec = spec(false);
        Request request = mockRequest();
        sink.recordRequest(request, spec);
        Response response = mockResponse(200, "application/json",
                BODY.getBytes(StandardCharsets.UTF_8));
        sink.onResponseForSpec(spec, request, response);

        sink.materializeArrivedBodies();

        assertCount(sink, CaptureStage.BODY_MATERIALIZED, 1);
        List<CapturedApiCall> calls = sink.all();
        assertEquals("显式关闭读体不等于不采集，记录仍须对业务可见", 1, calls.size());
        assertEquals(CaptureStage.BODY_MATERIALIZED, calls.get(0).stage());
        assertNull(calls.get(0).responseBody());
        verify(response, never()).body();
    }

    @Test
    public void redirectRemainsArrivedAndIsDiagnosticsOnly() {
        CaptureSink sink = newSink(30_000L);
        ApiSpec spec = spec(true);
        Request request = mockRequest();
        sink.recordRequest(request, spec);
        sink.onResponseForSpec(spec, request,
                mockResponse(302, "text/html", "<html></html>".getBytes(StandardCharsets.UTF_8)));

        // helper 内已由诊断记录本身断言 stage=ARRIVED；dump 消费后 stageCounts 归零属预期
        assertDiagnosticsOnly(sink, CaptureStage.ARRIVED);
    }

    @Test
    public void bodyReadFailureRemainsArrivedAndIsDiagnosticsOnly() {
        CaptureSink sink = newSink(30_000L);
        ApiSpec spec = spec(true);
        Request request = mockRequest();
        sink.recordRequest(request, spec);
        Response response = mockResponse(200, "application/json", null);
        when(response.body()).thenThrow(new RuntimeException("driver handle gone"));
        sink.onResponseForSpec(spec, request, response);

        assertDiagnosticsOnly(sink, CaptureStage.ARRIVED);
        assertEquals(1L, sink.bodyReadFailureCount());
    }

    @Test
    public void timeoutRemainsMatchedAndIsDiagnosticsOnly() {
        CaptureSink sink = newSink(0L); // 0ms 窗口：下一次扫描即超时定案
        sink.recordRequest(mockRequest(), spec(true));

        assertDiagnosticsOnly(sink, CaptureStage.MATCHED);
        assertEquals(1L, sink.timedOutCount());
    }
}

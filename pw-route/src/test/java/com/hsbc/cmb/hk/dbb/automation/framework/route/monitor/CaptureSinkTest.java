package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteIoExecutor;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CaptureSink 采集验证：请求/响应配对、敏感头脱敏、响应体截断、超时定案、
 * 消费式幂等、队列上限与 fail-open 语义。
 *
 * <p>并发模型与 {@link MonitorSink} 一致：record/onResponse 在事件线程（本测试直接调用），
 * body 读取就地同步（2026-09-28 修复：旧实现投 IO 线程排队导致句柄被回收、body 恒 null），
 * dump 在业务线程消费。
 */
public class CaptureSinkTest {

    private CaptureSink newSink(int maxCaptured, long timeoutMs) {
        return new CaptureSink(maxCaptured, timeoutMs);
    }

    private Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("POST");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("content-type", "application/json",
                "authorization", "Bearer secret-token-abc"));
        when(request.postData()).thenReturn("{\"name\":\"u\"}");
        return request;
    }

    private Response mockResponse(String url, int status) {
        return mockResponse(url, status, "{\"code\":0,\"data\":{\"name\":\"ok\"}}");
    }

    private Response mockResponse(String url, int status, String body) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json",
                "set-cookie", "sid=abc123"));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    @Test
    public void recordsRequestAndResponsePair() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .captureBody(false)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/users/1", 200));

        List<CapturedApiCall> calls = sink.dump();
        assertEquals("请求-响应配对后必须产出 1 条快照", (long) 1, (long) calls.size());
        CapturedApiCall call = calls.get(0);
        assertEquals((Object) "POST", (Object) call.method());
        assertEquals((Object) "https://host/api/users/1", (Object) call.url());
        assertEquals((Object) "/api/users/**", (Object) call.pattern());
        assertEquals((long) 200L, (long) call.responseStatus());
        assertTrue(call.durationMs() >= 0);
        assertEquals((Object) "{\"name\":\"u\"}", (Object) call.requestBodyPreview());
        assertNull( "显式 .captureBody(false) 时响应体必须为 null", call.responseBody());
        assertFalse(call.responseBodyTruncated());
        assertTrue(call.recordedAtMillis() > 0);
    }

    @Test
    public void sensitiveHeadersAreKeptRawInTheRecordAndMaskedInRendering() {
        ApiSpec spec = ApiSpec.builder("/api/login", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/login"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/login", 200));

        CapturedApiCall call = sink.dump().get(0);
        // 数据层保真：业务需要真实值（取整响应 → 改指定节点 → 当 mock 回放）
        assertEquals("记录里必须保留原始 authorization", (Object) "Bearer secret-token-abc",
                (Object) call.requestHeaders().get("authorization"));
        assertEquals("记录里必须保留原始 set-cookie", (Object) "sid=abc123",
                (Object) call.responseHeaders().get("set-cookie"));
        assertEquals("非敏感头保持原样", (Object) "application/json", (Object) call.requestHeaders().get("content-type"));
        // 渲染层脱敏：日志/报告不得出现原始敏感值，但仍要展示被打码的头
        String rendered = call.detail();
        assertFalse("渲染文本不得出现原始 token", rendered.contains("secret-token-abc"));
        assertFalse("渲染文本不得出现原始 cookie 值", rendered.contains("sid=abc123"));
        assertTrue("渲染文本仍需展示（已打码的）敏感头", rendered.contains("authorization"));
    }

    @Test
    public void responseBodyCapturedInFullWithoutTruncation() throws Exception {
        ApiSpec spec = ApiSpec.builder("/api/big", RouteCapability.MONITOR)
                .capture(true)
                .captureBody(true)
                .captureBodyLimitBytes(8)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/big"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/big", 200,
                "{\"data\":\"very long payload\"}"));

        List<CapturedApiCall> calls = sink.dump();
        assertEquals((long) 1, (long) calls.size());
        CapturedApiCall call = calls.get(0);
        assertNotNull("captureBody 开启后响应体必须非 null", call.responseBody());
        // 行为变更：不再截断（整响应保留，供业务"改指定节点后当 mock 回放"）。
        assertTrue("响应体必须整份保留（不受 captureBodyLimitBytes 影响）",
                call.responseBody().contains("very long payload"));
        assertFalse("不再截断，标志必须为 false", call.responseBodyTruncated());
        assertNotNull("原始字节必须同时保留", call.responseBodyBytes());
        assertEquals("原始字节与文本长度一致", (long) call.responseBody().length(),
                (long) call.responseBodyBytes().length);
    }

    @Test
    public void pendingRequestTimesOutOnDump() throws Exception {
        ApiSpec spec = ApiSpec.builder("/api/slow", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 50); // 50ms 超时窗口

        sink.recordRequest(mockRequest("https://host/api/slow"), spec);
        Thread.sleep(120); // 超过超时窗口

        List<CapturedApiCall> calls = sink.dump();
        assertEquals("超时未响应必须定案为 timedOut 快照", (long) 1, (long) calls.size());
        CapturedApiCall call = calls.get(0);
        assertTrue(call.timedOut());
        assertNull(call.responseStatus());
        assertEquals((long) -1, (long) call.durationMs());
        assertTrue(sink.timedOutCount() >= 1);
    }

    @Test
    public void dumpIsIdempotent() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/users/1", 200));

        assertEquals((long) 1, (long) sink.dump().size());
        assertTrue( "消费式语义：第二次 dump 必须为空", sink.dump().isEmpty());
        assertEquals((long) 0, (long) sink.size());
    }

    @Test
    public void queueLimitDropsExcess() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(2, 30_000); // 上限 2 条

        for (int i = 1; i <= 3; i++) {
            sink.recordRequest(mockRequest("https://host/api/users/" + i), spec);
            sink.onResponseForSpec(spec, mockResponse("https://host/api/users/" + i, 200));
        }

        assertEquals("队列上限内保留", (long) 2, (long) sink.dump().size());
        assertEquals("超出部分丢弃并计数（fail-open）", (long) 1, (long) sink.droppedCount());
    }

    @Test
    public void bareMonitorRuleIsCapturedByDefault() {
        ApiSpec spec = ApiSpec.builder("/api/plain", RouteCapability.MONITOR).build(); // 裸 monitor：零 monitor 参数
        assertEquals("声明即算 MONITOR 类型（口径二回退）", "MONITOR", spec.primaryChannel());

        CaptureSink sink = newSink(100, 30_000);
        sink.recordRequest(mockRequest("https://host/api/plain"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/plain", 200));

        List<CapturedApiCall> calls = sink.dump();
        assertEquals("默认只采 MONITOR：MONITOR 规则无需显式 capture 即入库", (long) 1, (long) calls.size());
        assertEquals((long) 200L, (long) calls.get(0).responseStatus());
        assertEquals((Object) "/api/plain", (Object) calls.get(0).pattern());
    }

    @Test
    public void bareMockRuleIsNotCapturedByDefaultUnlessAllTypesEnabled() {
        ApiSpec spec = ApiSpec.builder("/api/plain-mock", RouteCapability.MOCK).build(); // 裸 mock
        assertEquals("裸 mock 的主渠道是 MOCK", "MOCK", spec.primaryChannel());

        CaptureSink sink = newSink(100, 30_000);
        sink.recordRequest(mockRequest("https://host/api/plain-mock"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/plain-mock", 200));
        assertTrue("默认只采 MONITOR：MOCK 规则不入采集库", sink.dump().isEmpty());

        // 打开统一配置键 route.capture.all.type（系统属性层）后，同一规则应被采集；
        // 采集闸门按该键的声明式层序解析，且策略在 sink 构造期冻结（RouteCapturePolicy：
        // 环境变量 → 系统属性 → 配置文件属性 → 默认值）——故本用例必须先设属性、后构造 sink。
        // 注意：System 属性是 JVM 全局，本用例独占该键的读写，结束前显式写回默认值，不依赖 clearProperty。
        String prev = System.getProperty("route.capture.all.type");
        try {
            System.setProperty("route.capture.all.type", "true");
            CaptureSink capturing = newSink(100, 30_000);
            capturing.recordRequest(mockRequest("https://host/api/plain-mock"), spec);
            capturing.onResponseForSpec(spec, mockResponse("https://host/api/plain-mock", 200));
            assertEquals("route.capture.all.type=true 后 MOCK 规则应被采集", (long) 1, (long) capturing.dump().size());
        } finally {
            System.setProperty("route.capture.all.type", prev == null ? "false" : prev);
        }
    }

    @Test
    public void explicitCaptureStillForcesCollectionForAnyChannel() {
        ApiSpec spec = ApiSpec.builder("/api/plain-cap", RouteCapability.MOCK).capture(true).build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/plain-cap"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/plain-cap", 200));

        assertEquals("显式 .capture() 必须强制采集（向后兼容，与主渠道无关）", (long) 1, (long) sink.dump().size());
    }

    @Test
    public void pendingWithoutTimeoutIsKept() {
        ApiSpec spec = ApiSpec.builder("/api/pending", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/pending"), spec);
        // 未响应、未超时：不得被 dump 误定案
        assertTrue(sink.dump().isEmpty());
        assertTrue(sink.timedOutCount() == 0);
    }

    @Test
    public void responseDeliveredForOtherSpecDoesNotSettleThisSpec() {
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        ApiSpec other = ApiSpec.builder("/api/other/**", RouteCapability.MONITOR)
                .capture(true)
                .build();
        CaptureSink sink = newSink(100, 30_000);

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        // 响应侧按 spec 精确配对：投递给别的规则绝不消费本规则的 pending（取代原 URL 匹配语义）
        sink.onResponseForSpec(other, mockResponse("https://host/api/users/1", 200));

        assertTrue( "跨规则投递不得定案本规则的快照", sink.dump().isEmpty());
    }
}

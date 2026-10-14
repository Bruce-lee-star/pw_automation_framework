package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采集序号（{@link CapturedApiCall#captureSeq()}）与等待门槛契约。
 *
 * <p><b>动机</b>：历史队列是<b>只增</b>的，且不区分导航代次。连续导航 / 同一 URL 重复轮询时，
 * 上一轮请求的迟到响应会被新一轮的等待命中 —— 测试"通过"却根本没有验证本轮流量
 * （"过期命中"）。用单调序号把等待限定在"动作之后产生的记录"，即可消除这条失效模式。
 *
 * <p>覆盖：序号严格单调、{@code captureSequence()} 与记录数一致、
 * 门槛等待跳过陈旧记录、{@code minCaptureSeq <= 0} 完全等价于既有行为（零回归）。
 */
public class CaptureSinkSequenceTest {

    private CaptureSink sink;

    private static final Predicate<CapturedApiCall> URL_A = c -> "https://host/api/a".equals(c.url());

    @Before
    public void setUp() {
        sink = new CaptureSink(100, 30_000);
    }

    private static ApiSpec monitor(String pattern) {
        return ApiSpec.builder(pattern, RouteCapability.MONITOR).build();
    }

    private static Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("content-type", "application/json"));
        return request;
    }

    private static Response mockResponse(int status) {
        Response response = mock(Response.class);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        return response;
    }

    /** 一条命中 MONITOR 的往返（pattern 取 url，便于按 pattern 区分记录）。 */
    private void capture(String url) {
        ApiSpec spec = monitor(url);
        sink.recordRequest(mockRequest(url), spec);
        sink.onResponseForSpec(spec, mockResponse(200));
    }

    @Test
    public void recordsCarryStrictlyIncreasingSequence() {
        assertEquals("初始无记录", 0L, sink.captureSequence());

        capture("https://host/api/a");
        capture("https://host/api/b");

        assertEquals("两条记录 ⇒ 序号已分配到 2", 2L, sink.captureSequence());
        long seqA = -1L;
        long seqB = -1L;
        for (CapturedApiCall c : sink.all()) {
            if ("https://host/api/a".equals(c.url())) {
                seqA = c.captureSeq();
            } else {
                seqB = c.captureSeq();
            }
        }
        assertEquals("先到者序号更小", 1L, seqA);
        assertEquals("后到者序号更大", 2L, seqB);
    }

    @Test
    public void waitWithThresholdSkipsStaleRecordsFromAnEarlierAction() {
        // 第一轮动作：产生一条同 URL 的记录（模拟"上一轮导航的迟到响应"）
        capture("https://host/api/a");
        // 业务在触发第二轮动作之前取门槛
        long beforeSecondAction = sink.captureSequence();
        assertEquals(1L, beforeSecondAction);

        // 门槛 > 0 且此后尚无新记录 ⇒ 不得被陈旧记录满足
        assertNull("只看新记录时必须超时返回 null，不能被上一轮记录命中（过期命中）",
                sink.waitFor(URL_A, 150L, beforeSecondAction + 1));

        // 第二轮动作真正到达 ⇒ 命中，且命中的必须是本轮那条
        capture("https://host/api/a");
        CapturedApiCall hit = sink.waitFor(URL_A, 1_000L, beforeSecondAction + 1);
        assertNotNull("本轮产生的新记录必须被命中", hit);
        assertEquals("命中的必须是本轮那条", 2L, hit.captureSeq());
    }

    @Test
    public void zeroThresholdKeepsLegacyUnscopedBehaviour() {
        capture("https://host/api/a");

        // minCaptureSeq <= 0 的语义必须完全等价于改造前：陈旧记录照样命中
        CapturedApiCall hit = sink.waitFor(URL_A, 1_000L, 0L);
        assertNotNull("零门槛时行为不得改变（向后兼容）", hit);
        assertEquals(1L, hit.captureSeq());
    }

    @Test
    public void resetRestartsEvictedHistoryButSequenceStaysMonotonic() {
        capture("https://host/api/a");
        sink.reset();

        // 清空后重新采集：序号不得回退（回退会让"动作前取的门槛"失效）
        capture("https://host/api/b");
        long freshSeq = -1L;
        for (CapturedApiCall c : sink.all()) {
            freshSeq = c.captureSeq();
        }
        assertEquals("清空历史后序号必须继续单调前进", 2L, freshSeq);
    }
}

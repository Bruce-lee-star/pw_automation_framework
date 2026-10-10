package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MonitorSink 断言失败结算（settlement）验证 —— P0：fail-on-assertion 上报的前置管道。
 *
 * <p>覆盖：status 失败/成功、body 断言失败/成功、超时定案、结算幂等（消费式防重）、
 * 去重（{@code tryMarkSettled} CAS）、无期望不结算。并发模型与 {@link MonitorSink} 一致：
 * 结算入队发生在事件线程 / 调度线程，本测试在业务线程 drain 消费。
 *
 * <p>2026-10-10 同步化契约：body 断言在 {@code onResponseForSpec} 所在线程<b>就地同步</b>结算
 * （不再投 IO 线程），故断言结果<b>零等待</b>立即可见，且<b>不可能因队列满被丢弃</b>——
 * 本文件用显式测试守卫这两条（{@link #bodyAssertionIsSynchronousNoThreadCoordinationNeeded} /
 * {@link #concurrentAssertionsAreNeverDropped}）。
 */
public class MonitorSinkAssertionSettlementTest {

    private MonitorSink newSink() {
        return new MonitorSink();
    }

    private Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("x-test", "1"));
        when(request.postData()).thenReturn(null);
        return request;
    }

    private Response mockResponse(String url, int status) {
        return mockResponse(url, status, "{\"code\":0,\"data\":{\"name\":\"ok\"}}");
    }

    private Response mockResponse(String url, int status, String body) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    @Test
    public void statusMismatchSettlesImmediatelyAndIsIdempotent() {
        ApiSpec spec = ApiSpec.builder("/api/login", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/login"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/login", 500));

        List<CapturedExchange> failures = sink.drainSettledFailures();
        assertEquals("status 不匹配必须立即结算为失败", (long) 1, (long) failures.size());
        CapturedExchange failure = failures.get(0);
        assertEquals((long) 500L, (long) failure.responseStatus());
        assertFalse(failure.assertionPassed());
        assertEquals((Object) "https://host/api/login", (Object) failure.url());
        assertEquals((Object) "/api/login", (Object) failure.pattern());

        assertTrue( "消费式语义：第二次 drain 必须为空", sink.drainSettledFailures().isEmpty());
    }

    @Test
    public void statusMatchDoesNotSettle() {
        ApiSpec spec = ApiSpec.builder("/api/login", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/login"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/login", 200));

        assertTrue( "断言通过不得结算为失败", sink.drainSettledFailures().isEmpty());
        assertEquals((long) 0, (long) sink.settledFailureCount());
    }

    @Test
    public void bodyAssertionFailureSettlesSynchronously() throws Exception {
        ApiSpec spec = ApiSpec.builder("/api/user", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.code", 1) // 实际 0 → 失败
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/user"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/user", 200));

        List<CapturedExchange> failures = sink.drainSettledFailures();
        assertEquals("body 断言失败必须结算", (long) 1, (long) failures.size());
        assertFalse(failures.get(0).assertionPassed());
        assertFalse(
                "body 断言失败明细必须随结算带出", failures.get(0).bodyAssertionFailures().isEmpty());
        assertTrue( "消费式幂等", sink.drainSettledFailures().isEmpty());
    }

    @Test
    public void bodyAssertionPassDoesNotSettle() throws Exception {
        ApiSpec spec = ApiSpec.builder("/api/user", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.code", 0)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/user"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/user", 200));

        assertTrue( "body 断言通过不得结算", sink.drainSettledFailures().isEmpty());
    }

    @Test
    public void timedOutRequestSettlesAsFailure() throws Exception {
        ApiSpec spec = ApiSpec.builder("/api/slow", RouteCapability.MONITOR)
                .expectStatus(200)
                .monitorTimeoutMs(50)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/slow"), spec);
        Thread.sleep(120); // 超过 monitorTimeoutMs

        List<CapturedExchange> failures = sink.drainSettledFailures();
        assertEquals("超时未响应必须结算为失败", (long) 1, (long) failures.size());
        assertTrue(failures.get(0).responseTimedOut());
        assertFalse(failures.get(0).assertionPassed());
    }

    @Test
    public void pendingRequestWithoutTimeoutIsNotSettled() {
        ApiSpec spec = ApiSpec.builder("/api/pending", RouteCapability.MONITOR)
                .expectStatus(200)
                .monitorTimeoutMs(0) // 永不超时
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/pending"), spec);
        // 未响应、未超时：必须保留在观测队列，不得被误判为失败
        assertTrue(sink.drainSettledFailures().isEmpty());
        assertEquals((long) 0, (long) sink.settledFailureCount());
    }

    @Test
    public void settleIsDeduplicatedAcrossPaths() {
        ApiSpec spec = ApiSpec.builder("/api/dup", RouteCapability.MONITOR)
                .expectStatus(200)
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/dup"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/dup", 500));

        // 第一次结算后，模拟并发路径再次尝试结算（直接调用同一 exchange 的 tryMarkSettled）
        CapturedExchange first = sink.drainSettledFailures().get(0);
        assertFalse( "已结算的交换不得再次标记成功（CAS 去重）", first.tryMarkSettled());
        assertEquals("去重后失败队列不得再增长", (long) 0, (long) sink.settledFailureCount());
    }

    @Test
    public void monitorWithoutExpectNeverSettles() {
        ApiSpec spec = ApiSpec.builder("/api/plain", RouteCapability.MONITOR).build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/plain"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/plain", 200));

        assertTrue( "无期望的 monitor 永不结算为失败", sink.drainSettledFailures().isEmpty());
    }

    @Test
    public void bodyAssertionIsSynchronousNoThreadCoordinationNeeded() {
        // 2026-10-10 同步化契约（核心）：onResponseForSpec 返回后，body 断言已就地同步结算——
        // settledFailureCount 立即可见，零线程协调、零等待。
        // 历史异步版需 IO 线程执行 + close()/轮询等待（且 IO 队满会丢断言）；本测试守卫该回归。
        ApiSpec spec = ApiSpec.builder("/api/sync", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.code", 1) // 实际 0 → 失败
                .build();
        MonitorSink sink = newSink();

        sink.recordRequest(mockRequest("https://host/api/sync"), spec);
        sink.onResponseForSpec(spec, mockResponse("https://host/api/sync", 200));

        assertEquals("onResponseForSpec 返回后失败必须立即可见（同步结算，无 IO 等待）",
                1, sink.settledFailureCount());
        List<CapturedExchange> failures = sink.drainSettledFailures();
        assertEquals("失败明细必须同步带出", (long) 1, (long) failures.size());
        assertFalse(failures.get(0).assertionPassed());
    }

    @Test
    public void concurrentAssertionsAreNeverDropped() throws Exception {
        // 守卫"假绿"回归：同步化消除了"IO 队列满 → 断言被静默丢弃（rejectedBodyAssertions）"。
        // N 路并发失败断言必须【全部】结算，任何一条丢失都意味着断言未执行而场景仍绿。
        ApiSpec spec = ApiSpec.builder("/api/conc", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.code", 1) // 实际 0 → 失败
                .build();
        MonitorSink sink = newSink();
        int workers = 8;
        int perWorker = 25;
        int total = workers * perWorker;

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            final int id = w;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                for (int i = 0; i < perWorker; i++) {
                    String url = "https://host/api/conc/" + id + "/" + i;
                    sink.recordRequest(mockRequest(url), spec);
                    sink.onResponseForSpec(spec, mockResponse(url, 200));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue("测试线程池未在时限内收敛", pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals("并发 body 断言必须全部结算（0 丢失，无假绿）",
                (long) total, (long) sink.drainSettledFailures().size());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CaptureSink} 的<b>查询 / 清理 / 等待</b> API 契约（此前 0 覆盖）。
 *
 * <p>覆盖：{@code all} / {@code allLastPerPattern} / {@code lastOf} / {@code byUrl} / {@code byChannel}
 * 的非消费语义、{@code dump} 的消费语义、{@code waitFor} 的事件驱动等待（不轮询空转）与超时、
 * {@code reset} 的清空（队列 + 在途等待 + 计数）。
 *
 * <p>夹具说明：{@code CaptureSink} 的 {@code io} 参数当前<b>未被本类路径使用</b>
 * （采集路径在响应轮询线程同步完成字节快照与留存），故本类传 {@code null} 以最小化夹具；
 * 若将来 {@code io} 被真正使用（如派发留存到 IO 池），这里需改为真实执行器。
 */
public class CaptureSinkQueryApiTest {

    private CaptureSink sink;

    @Before
    public void setUp() {
        sink = new CaptureSink(null, 100, 30_000);
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

    /** 一条命中 MONITOR 的往返（pattern 取 url 便于按 pattern 区分记录）。 */
    private void capture(String url) {
        ApiSpec spec = monitor(url);
        sink.recordRequest(mockRequest(url), spec);
        sink.onResponseForSpec(spec, mockResponse(200));
    }

    @Test
    public void queryApisExposeTheRecordsAndStayNonConsuming() {
        capture("https://host/api/a");
        capture("https://host/api/b");

        assertEquals(2, sink.size());
        assertEquals("peek 为非消费式", 2, sink.peek().size());
        assertEquals("all 的数据源是只增历史", 2, sink.all().size());
        assertEquals(2, sink.allLastPerPattern().size());
        assertEquals(1, sink.byUrl("https://host/api/a").size());
        // 渠道查询以记录自带的 channels() 为准（大小写不敏感）；不假设裸 monitor 一定带渠道项，
        // 而是断言"声明了某渠道的记录必被查到 + 未知渠道必为空"这一自洽契约。
        for (CapturedApiCall c : sink.all()) {
            for (String channel : c.channels()) {
                assertTrue("声明该渠道的记录必须被 byChannel 查到（大小写不敏感）",
                        sink.byChannel(channel.toLowerCase(java.util.Locale.ROOT)).contains(c));
            }
        }
        assertTrue("未知渠道必须为空", sink.byChannel("NOT_A_CHANNEL").isEmpty());
        assertNotNull(sink.lastOf("https://host/api/a"));
        assertEquals("查询不得消费记录", 2, sink.all().size());
    }

    @Test
    public void dumpIsConsumingWhileTheHistoryRemains() {
        capture("https://host/api/a");
        assertEquals(1, sink.dump().size());
        assertTrue("消费式：二次 dump 为空", sink.dump().isEmpty());
        assertEquals("非消费式历史不受 dump 影响", 1, sink.all().size());
        assertEquals(1, sink.peek().size());
    }

    @Test
    public void waitForFindsExistingRecordsAndTimesOut() {
        capture("https://host/api/a");
        assertNotNull("已有记录应立即可得",
                sink.waitFor(c -> "https://host/api/a".equals(c.url()), 500));

        long start = System.nanoTime();
        assertNull("无匹配记录应超时返回 null", sink.waitFor(c -> false, 150));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("事件驱动等待应贴合设定超时（非轮询空转），实测 " + elapsedMs + "ms",
                elapsedMs >= 100L && elapsedMs < 2_000L);
    }

    @Test
    public void resetClearsQueuesHistoryAndInFlightWait() {
        capture("https://host/api/a");
        sink.reset();

        assertEquals(0, sink.size());
        assertTrue("历史清空", sink.all().isEmpty());
        assertTrue("消费队列清空", sink.peek().isEmpty());
        assertNull("在途等待也被清掉", sink.waitFor(c -> true, 50));
    }

    @Test
    public void everyWaiterIsWokenWhenARecordArrives() throws Exception {
        java.util.concurrent.CountDownLatch armed = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicReference<CapturedApiCall> first =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<CapturedApiCall> second =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.Predicate<CapturedApiCall> match =
                c -> "https://host/api/a".equals(c.url());

        Thread t1 = new Thread(() -> {
            armed.countDown();
            first.set(sink.waitFor(match, 5_000));
        });
        Thread t2 = new Thread(() -> {
            armed.countDown();
            second.set(sink.waitFor(match, 5_000));
        });
        t1.start();
        t2.start();
        assertTrue("两个等待者应已启动", armed.await(2, java.util.concurrent.TimeUnit.SECONDS));
        // 给两个等待者进入阻塞的机会（可中断的有界 park，非 Thread.sleep）
        java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);

        capture("https://host/api/a");   // 单次入队必须唤醒"所有"等待者

        t1.join(2_000);
        t2.join(2_000);
        assertFalse("等待线程不得仍在运行", t1.isAlive() || t2.isAlive());
        assertNotNull("等待者 1 必须被唤醒并命中", first.get());
        assertNotNull("等待者 2 也必须被唤醒并命中（旧实现单次 release 只唤醒一个，会在此暴露）",
                second.get());
    }
}

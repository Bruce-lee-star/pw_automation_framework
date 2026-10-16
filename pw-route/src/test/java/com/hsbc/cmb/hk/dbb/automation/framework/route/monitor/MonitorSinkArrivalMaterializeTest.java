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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Monitor 到达即读入快照（2026-10-16）：body 断言规则的响应，在 main drain 收尾的
 * {@link MonitorSink#materializeArrivedBodies()} 里就地读 body 成 {@link BodySnapshot} 存入 exchange，
 * 结算（{@code settleBodyAssertionNow}）消费快照而非活句柄——消除"持有句柄→延迟读取"的驱逐窗口。
 *
 * <p>锁定三点：① dispatch 只持句柄不读 body；② {@code materializeArrivedBodies} 读一次；
 * ③ 结算用快照、零二次读；外加回退安全（未预取时结算仍从句柄现读，行为不变）。</p>
 */
public class MonitorSinkArrivalMaterializeTest {

    private Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("x-test", "1"));
        when(request.postData()).thenReturn(null);
        return request;
    }

    private Response jsonResponse(String url, String body) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(200);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    @Test
    public void materializeReadsBodyIntoSnapshotAndAssertionConsumesIt() {
        ApiSpec spec = ApiSpec.builder("/api/leftmenu", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.enableAdminTools", "Y")
                .build();
        MonitorSink sink = new MonitorSink();
        Response response = jsonResponse("https://host/api/leftmenu", "{\"enableAdminTools\":\"Y\"}");

        sink.recordRequest(mockRequest("https://host/api/leftmenu"), spec);
        sink.onResponseForSpec(spec, response);
        verify(response, never()).body(); // ① dispatch 只持句柄，不读 body

        sink.materializeArrivedBodies(); // ② 到达即读：读 body 入快照
        verify(response, times(1)).body();

        List<CapturedExchange> drained = sink.drain(); // ③ 结算消费预取快照
        assertEquals(1, drained.size());
        CapturedExchange exchange = drained.get(0);
        assertTrue("断言应基于预取快照通过", exchange.assertionPassed());
        assertTrue(exchange.bodyAssertionFailures().isEmpty());
        assertNull("正常路径不得标记 inconclusive", exchange.bodyAssertionInconclusiveReason());
        verify(response, times(1)).body(); // 结算用快照，零二次读 body
    }

    @Test
    public void withoutMaterialize_settlementStillReadsFromHandle_fallbackUnchanged() {
        // 回退安全：未调用 materializeArrivedBodies（如首轮 dispatch 交付未及预取）时，
        // 结算仍从持有句柄现读——行为与历史一致，绝不漏判。
        ApiSpec spec = ApiSpec.builder("/api/leftmenu", RouteCapability.MONITOR)
                .expectStatus(200)
                .expectJsonPath("$.enableAdminTools", "Y")
                .build();
        MonitorSink sink = new MonitorSink();
        Response response = jsonResponse("https://host/api/leftmenu", "{\"enableAdminTools\":\"Y\"}");

        sink.recordRequest(mockRequest("https://host/api/leftmenu"), spec);
        sink.onResponseForSpec(spec, response);

        CapturedExchange exchange = sink.drain().get(0); // 无预取 → 回退现读句柄
        assertTrue("回退路径断言仍应通过", exchange.assertionPassed());
        verify(response, times(1)).body();
    }
}

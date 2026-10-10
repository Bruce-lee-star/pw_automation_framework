package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CaptureSink;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F8+ 采集保真：3xx 重定向响应的 body 语义（对照评审「3xx body 不可读、单独断言 location」）。
 *
 * <p><b>修复前</b>：{@code CaptureSink.captureBodyNow} 对 3xx 也会发起一次 {@code Response.body()}
 * 协议调用（已知重定向无 body 或不可读）——无谓驱动往返，且句柄不可用时误计
 * {@code bodyReadFailures}（把"正常 3xx"记成"读取失败"）。</p>
 *
 * <p><b>修复后断言</b>：
 * <ul>
 *   <li>3xx 时 <b>绝不调用</b> {@code response.body()}（零协议往返）；</li>
 *   <li>采集记录保留 status=302 与 {@code location} 头（供业务断言重定向目标）；</li>
 *   <li>记录体为 null（3xx 无 body 语义，与官方一致）。</li>
 * </ul>
 */
public class CaptureRedirectSkippedTest {

    @Test
    public void redirectResponseSkipsBodyReadAndKeepsLocationHeader() {
        CaptureSink sink = new CaptureSink(100, 5000);
        ApiSpec spec = ApiSpec.builder("**/r/**", RouteCapability.MONITOR)
                .captureBody(true)
                .build();

        Request request = mock(Request.class);
        when(request.url()).thenReturn("http://h/r/1");
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        sink.recordRequest(request, spec); // 入 pending 索引（等待响应配对）

        Response response = mock(Response.class);
        when(response.status()).thenReturn(302);
        when(response.headers()).thenReturn(Map.of(
                "location", "/sso/login",
                "content-type", "text/html"));
        sink.onResponseForSpec(spec, response);

        verify(response, never()).body(); // 3xx 绝不发起 body() 协议调用

        List<CapturedApiCall> calls = sink.all();
        assertEquals("3xx 响应必须产生一条采集记录", 1, calls.size());
        CapturedApiCall call = calls.get(0);
        assertEquals("状态码保留", Integer.valueOf(302), call.responseStatus());
        assertEquals("location 头保留（重定向断言入口）", "/sso/login",
                call.responseHeaders().get("location"));
        assertNull("3xx 无 body 语义：记录体必须为空", call.responseBody());
    }
}

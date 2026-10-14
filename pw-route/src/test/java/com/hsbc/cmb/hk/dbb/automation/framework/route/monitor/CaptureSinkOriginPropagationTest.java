package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RequestOriginView;
import com.hsbc.cmb.hk.dbb.automation.framework.route.diag.RequestOrigin;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 归属三元组从采集记录<b>透传到跨模块只读视图</b>的契约。
 *
 * <p>这是"归属可查询"的落地点：业务侧（pw-web-ui）只能看到 {@link CapturedApiCallView}，
 * 若 {@code origin} 没有映射到视图上，三元组就只是"入库但没人能读"，
 * {@code NAVIGATION_EPOCH_NOT_QUERYABLE} 这条覆盖范围声明也就无法消除。
 */
public class CaptureSinkOriginPropagationTest {

    private CaptureSink sink;

    @Before
    public void setUp() {
        sink = new CaptureSink(100, 30_000);
    }

    private static ApiSpec monitor(String pattern) {
        return ApiSpec.builder(pattern, RouteCapability.MONITOR).build();
    }

    /** 归属到指定 Page 的请求（main=true 表示主 Frame）。 */
    private static Request requestOn(Page page, Frame frame, boolean main, boolean navigation) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn("https://host/api/x");
        when(request.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(request.frame()).thenReturn(frame);
        when(request.isNavigationRequest()).thenReturn(navigation);
        when(frame.parentFrame()).thenReturn(main ? null : mock(Frame.class));
        when(frame.page()).thenReturn(page);
        return request;
    }

    private static Response mockResponse() {
        Response response = mock(Response.class);
        when(response.status()).thenReturn(200);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        return response;
    }

    private void capture(Request request, String pattern) {
        ApiSpec spec = monitor(pattern);
        sink.recordRequest(request, spec);
        sink.onResponseForSpec(spec, mockResponse());
    }

    @Test
    public void originIsCarriedThroughToTheCrossModuleView() {
        Page page = mock(Page.class);
        Frame frame = mock(Frame.class);
        capture(requestOn(page, frame, true, true), "/api/x");

        CapturedApiCallView view = CaptureSink.toView(sink.all().get(0));
        assertNotNull(view);
        assertNotNull("视图必须带归属（不得为 null）", view.origin());
        assertTrue("主文档导航必须可归属", view.origin().attributed());
        assertEquals("代次须为 1", 1L, view.origin().navSeq());
        assertNotNull("owner id 必须有值", view.origin().ownerPageId());
    }

    @Test
    public void ownerScopedFilterSeparatesPages() {
        Page pageA = mock(Page.class);
        Page pageB = mock(Page.class);
        Frame frameA = mock(Frame.class);
        Frame frameB = mock(Frame.class);

        capture(requestOn(pageA, frameA, true, true), "/api/a");
        capture(requestOn(pageB, frameB, true, true), "/api/b");

        CapturedApiCallView a = CaptureSink.toView(sink.all().get(0));
        CapturedApiCallView b = CaptureSink.toView(sink.all().get(1));

        String ownerA = a.origin().ownerPageId();
        String ownerB = b.origin().ownerPageId();
        assertTrue(a.fromOwner(ownerA));
        assertFalse("A 的记录不得被 B 的 owner 匹配", a.fromOwner(ownerB));
        assertTrue(b.fromOwner(ownerB));
        assertFalse("B 的记录不得被 A 的 owner 匹配", b.fromOwner(ownerA));
    }

    @Test
    public void degradedOriginIsMappedWithoutLosingTheRecord() {
        Request fromSw = mock(Request.class);
        when(fromSw.method()).thenReturn("GET");
        when(fromSw.url()).thenReturn("https://host/api/sw");
        when(fromSw.headers()).thenReturn(Map.of());
        when(fromSw.frame()).thenThrow(new IllegalStateException("Service Worker request"));

        capture(fromSw, "/api/sw");

        CapturedApiCallView view = CaptureSink.toView(sink.all().get(0));
        assertNotNull("SW 请求照样入库，不得丢观测", view);
        assertEquals("降级必须映射到视图的 DEGRADED", RequestOriginView.DEGRADED, view.origin());
        assertFalse("降级后不可归属", view.fromOwner("any"));
    }

    @Test
    public void recordsWithoutOriginMapToUnknown() {
        // 派发链证据等不走采集通道的记录：origin 为 UNKNOWN，视图侧同样不得为 null
        CapturedApiCall legacy = CapturedApiCall.of("/p", "GET", "https://host/legacy", null, null, 200, null, null,
                false, 1L, false);
        assertEquals("非采集路径的归属为 UNKNOWN", RequestOrigin.UNKNOWN, legacy.origin());

        CapturedApiCallView view = CaptureSink.toView(legacy);
        assertEquals(RequestOriginView.UNKNOWN, view.origin());
    }
}

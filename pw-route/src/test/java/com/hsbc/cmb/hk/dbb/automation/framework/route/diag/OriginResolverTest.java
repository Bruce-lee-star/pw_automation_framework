package com.hsbc.cmb.hk.dbb.automation.framework.route.diag;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link OriginResolver} 契约：事件线程上的<b>零协议调用</b>归属解析。
 *
 * <p>覆盖：主文档导航推进代次、非导航与 iframe 导航不推进、不同 Page 各自计数、
 * Service Worker / Frame 缺失 / frame() 抛异常时<b>降级但不丢观测</b>（返回 DEGRADED 而非抛）。
 */
public class OriginResolverTest {

    private OriginResolver resolver;

    @Before
    public void setUp() {
        resolver = new OriginResolver();
    }

    /** 归属一个请求：page → frame（main 表示是否主 Frame）。 */
    private static Request requestOn(Page page, Frame frame, boolean main, boolean navigation) {
        Request request = mock(Request.class);
        when(request.frame()).thenReturn(frame);
        when(request.isNavigationRequest()).thenReturn(navigation);
        Frame parent = main ? null : mock(Frame.class);
        when(frame.parentFrame()).thenReturn(parent);
        when(frame.page()).thenReturn(page);
        return request;
    }

    private static Page page() {
        return mock(Page.class);
    }

    private static Frame frame() {
        return mock(Frame.class);
    }

    @Test
    public void mainDocumentNavigationAdvancesEpoch() {
        Page page = page();
        Frame frame = frame();

        Request first = requestOn(page, frame, true, true);
        RequestOrigin o1 = resolver.resolve(first);
        assertTrue("主 Frame 的主文档导航必须可归属", o1.attributed());
        assertEquals("首个主文档导航代次为 1", 1L, o1.navSeq());

        RequestOrigin o2 = resolver.resolve(requestOn(page, frame, true, true));
        assertEquals("下一次主文档导航推进代次", 2L, o2.navSeq());
        assertEquals("同一 Page 的 owner id 必须稳定", o1.ownerPageId(), o2.ownerPageId());
        assertEquals("同一 Frame 的 frame id 必须稳定", o1.frameId(), o2.frameId());
    }

    @Test
    public void ordinaryRequestsAndIframeNavigationDoNotAdvanceEpoch() {
        Page page = page();
        Frame main = frame();

        assertEquals(1L, resolver.resolve(requestOn(page, main, true, true)).navSeq());

        Frame iframe = frame();
        RequestOrigin xhr = resolver.resolve(requestOn(page, main, true, false));
        assertEquals("普通 API 请求不改变代次", 1L, xhr.navSeq());

        RequestOrigin iframeNav = resolver.resolve(requestOn(page, iframe, false, true));
        assertEquals("iframe 内的导航不改变所属 Page 的主代次", 1L, iframeNav.navSeq());
        assertNotEquals("iframe 与主 Frame 必须解析出不同的 frame id", xhr.frameId(), iframeNav.frameId());
    }

    @Test
    public void differentPagesKeepSeparateEpochs() {
        Page a = page();
        Page b = page();
        Frame fa = frame();
        Frame fb = frame();

        RequestOrigin originA = resolver.resolve(requestOn(a, fa, true, true));
        RequestOrigin originB = resolver.resolve(requestOn(b, fb, true, true));

        assertNotEquals("不同 Page 必须有不同 owner id", originA.ownerPageId(), originB.ownerPageId());
        assertEquals("两个 Page 各自从代次 1 起算", 1L, originA.navSeq());
        assertEquals("两个 Page 各自从代次 1 起算", 1L, originB.navSeq());
        assertEquals(2, resolver.trackedPages());
    }

    @Test
    public void serviceWorkerRequestDegradesInsteadOfFailing() {
        Request fromSw = mock(Request.class);
        // 官方：对 request.serviceWorker() 非空的请求调 frame() 会抛异常
        when(fromSw.frame()).thenThrow(new IllegalStateException("Service Worker request"));

        RequestOrigin origin = resolver.resolve(fromSw);
        assertTrue("SW 请求必须标记为降级", origin.degraded());
        assertFalse("降级后不可归属，但不得抛异常", origin.attributed());
        assertNull("降级时无 owner", origin.ownerPageId());
    }

    @Test
    public void missingFrameIsUnknownNotError() {
        Request beforeFrame = mock(Request.class);
        when(beforeFrame.frame()).thenReturn(null);   // 主文档先于 Frame 创建
        when(beforeFrame.isNavigationRequest()).thenReturn(true);

        RequestOrigin origin = resolver.resolve(beforeFrame);
        assertNull("无 Frame 时不可归属", origin.ownerPageId());
        assertEquals("无 Frame 属于 UNKNOWN，而非 DEGRADED", RequestOrigin.UNKNOWN, origin);
    }

    @Test
    public void nullRequestIsNullSafe() {
        assertEquals("null 输入必须安全（不得 NPE 到 route 线程）", RequestOrigin.UNKNOWN, resolver.resolve(null));
    }

    @Test
    public void redirectHopsShareOneChainId() {
        Page page = page();
        Frame frame = frame();

        Request first = requestOn(page, frame, true, false);
        Request second = requestOn(page, frame, true, false);
        Request third = requestOn(page, frame, true, false);
        when(second.redirectedFrom()).thenReturn(first);
        when(third.redirectedFrom()).thenReturn(second);

        RequestOrigin o1 = resolver.resolve(first);
        RequestOrigin o2 = resolver.resolve(second);
        RequestOrigin o3 = resolver.resolve(third);

        assertNull("链首自身不带链 id", o1.redirectChainId());
        assertEquals("链首跳序为 0", 0, o1.redirectHop());

        assertNotNull("第二跳必须归入某条链", o2.redirectChainId());
        assertEquals("各跳必须共享同一个链 id", o2.redirectChainId(), o3.redirectChainId());
        assertEquals("第二跳跳序为 1", 1, o2.redirectHop());
        assertEquals("第三跳跳序为 2", 2, o3.redirectHop());
        assertTrue("第二/三跳须标记为重定向跳", o2.isRedirectHop() && o3.isRedirectHop());
        assertFalse("链首不算重定向跳", o1.isRedirectHop());
    }

    @Test
    public void circularRedirectIsBounded() {
        Page page = page();
        Frame frame = frame();
        Request a = requestOn(page, frame, true, false);
        Request b = requestOn(page, frame, true, false);
        // 循环重定向：a → b → a → …（事件线程上绝不允许无界回溯）
        when(a.redirectedFrom()).thenReturn(b);
        when(b.redirectedFrom()).thenReturn(a);

        RequestOrigin origin = resolver.resolve(a);
        assertTrue("循环链必须被硬上限截断，跳序有界",
                origin.redirectHop() >= 0 && origin.redirectHop() <= 32);
    }

    @Test
    public void redirectChainLookupFailureDoesNotThrow() {
        Page page = page();
        Frame frame = frame();
        Request request = requestOn(page, frame, true, false);
        when(request.redirectedFrom()).thenThrow(new IllegalStateException("object already collected"));

        RequestOrigin origin = resolver.resolve(request);
        assertTrue("回溯失败也必须能解析出归属（不得整条降级）", origin.attributed());
        assertEquals("回溯失败时跳序保守为 0", 0, origin.redirectHop());
    }
}

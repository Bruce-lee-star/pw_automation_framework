package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle.RouteAssertionProbeImpl;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * case 级断言失败消费的<b>并行隔离</b>契约：两个 BrowserContext 各自持有 runtime，
 * 消费 A 绝不触碰 B，B 也绝不能取走 A 的失败。
 */
public class RouteAssertionContextIsolationTest {

    private BrowserContext ctxA;
    private BrowserContext ctxB;
    private RouteRuntime rtA;
    private RouteRuntime rtB;
    private ConcurrentMap<BrowserContext, RouteRuntime> runtimes;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        ctxA = mock(BrowserContext.class);
        ctxB = mock(BrowserContext.class);
        rtA = mock(RouteRuntime.class);
        rtB = mock(RouteRuntime.class);
        when(rtA.drainSettledAssertionFailures()).thenReturn(List.of(failure("/api/a")));
        when(rtB.drainSettledAssertionFailures()).thenReturn(List.of());

        Field field = RouteEngine.class.getDeclaredField("RUNTIMES");
        field.setAccessible(true);
        runtimes = (ConcurrentMap<BrowserContext, RouteRuntime>) field.get(null);
        runtimes.put(ctxA, rtA);
        runtimes.put(ctxB, rtB);
    }

    @After
    public void tearDown() {
        if (runtimes != null) {
            runtimes.remove(ctxA);
            runtimes.remove(ctxB);
        }
    }

    @Test
    public void drainingOneContextNeverTouchesTheOtherRuntime() {
        List<RouteAssertionFailure> failures = RouteEngine.drainSettledAssertionFailures(ctxA);

        assertEquals(1, failures.size());
        assertEquals("/api/a", failures.get(0).pattern());
        verify(rtA, times(1)).drainSettledAssertionFailures();
        verify(rtB, never()).drainSettledAssertionFailures();
    }

    @Test
    public void drainingCaseBDoesNotConsumeCaseAFailure() {
        assertTrue("B case 没有失败", RouteEngine.drainSettledAssertionFailures(ctxB).isEmpty());
        verify(rtA, never()).drainSettledAssertionFailures();

        // A case 的失败仍在，只能由 A 自己取走
        assertEquals(1, RouteEngine.drainSettledAssertionFailures(ctxA).size());
    }

    @Test
    public void probeImplementationDelegatesToContextScopedDrain() {
        List<RouteAssertionFailure> failures =
                new RouteAssertionProbeImpl().drainAndResolveFailures(ctxA);

        assertEquals(1, failures.size());
        verify(rtB, never()).drainSettledAssertionFailures();
    }

    @Test
    public void nullOrUnregisteredContextReturnsEmptyAndTouchesNoRuntime() {
        assertTrue(RouteEngine.drainSettledAssertionFailures(null).isEmpty());
        assertTrue(RouteEngine.drainSettledAssertionFailures(mock(BrowserContext.class)).isEmpty());
        verify(rtA, never()).drainSettledAssertionFailures();
        verify(rtB, never()).drainSettledAssertionFailures();
    }

    private static RouteAssertionFailure failure(String pattern) {
        return new RouteAssertionFailure(pattern, "POST", "https://host" + pattern,
                200, 500, List.of(), false, 60_000L, "", "", "", "");
    }
}

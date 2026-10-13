package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RoutePatterns;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sec.6.3 verification gate (torture): high parallelism + deliberately slow main drain +
 * deliberately enabled DELAY capability. Asserts that protocol calls (resume) ALWAYS happen on the
 * main owning thread only -- i.e. Sec.8.4 "remove the scheduling thread's driving of route" holds
 * under pressure; cross-thread protocol calls (the source of {@code Object doesn't exist} cascade
 * corruption) count is zero.
 *
 * <p>This test uses a Mockito fake {@link BrowserContext} (same shape as
 * {@link DelayAppliesWaitTest}), runnable locally. It verifies the <b>mechanism</b> (thread
 * ownership of protocol calls): if Sec.8.4 regresses (DELAY delivery lands on the scheduler/IO
 * thread), then {@code resume} executes on a non-main thread -> {@code offThreadProtocolCalls > 0}
 * -> failure. The actual observation "runtime protocol-exception count == 0" on a real connection
 * is provided by {@code DriverRaceErrors.Monitor} (wired at {@code PageNavigation}) and asserted
 * by the CI real-browser channel-pressure suite.
 */
public class DelayGuardTortureTest {

    private BrowserContext ctx;
    private RouteRuntime rt;
    private final String mainThreadName = Thread.currentThread().getName();
    private final CopyOnWriteArrayList<String> protocolCallThreads = new CopyOnWriteArrayList<>();
    private final AtomicInteger offThreadProtocolCalls = new AtomicInteger(0);
    private final AtomicInteger totalDeliveries = new AtomicInteger(0);

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(mock(AutoCloseable.class));
        doAnswer(inv -> null).when(ctx).onClose(any());
        rt = RouteEngine.runtimeOf(ctx);
        return ctx;
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            RouteEngine.shutdown(ctx);
        }
    }

    private Consumer<Route> arm(String pattern) {
        //  delay(1) = 1s: same semantics as DelayAppliesWaitTest -- only then does the request
        //  actually suspend and delay the resume. delay(0) is treated as "no-delay pass-through"
        //  by the framework, never triggers resume, so it cannot build delivery pressure.
        RouteDsl.on(ctx).api(pattern).delay(1).done().start();
        return handlerFor(pattern);
    }

    private Consumer<Route> handlerFor(String urlPattern) {
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Route>> handlerCaptor = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(ctx, org.mockito.Mockito.atLeastOnce()).route(urlCaptor.capture(), handlerCaptor.capture(), any());
        List<String> urls = urlCaptor.getAllValues();
        List<Consumer<Route>> handlers = handlerCaptor.getAllValues();
        for (int i = urls.size() - 1; i >= 0; i--) {
            if (urls.get(i).equals(RoutePatterns.normalize(urlPattern))) {
                return handlers.get(i);
            }
        }
        throw new AssertionError("no handler bound for pattern: " + urlPattern);
    }

    private Route mockRoute(String url) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        when(request.method()).thenReturn("GET");
        when(request.headers()).thenReturn(Map.of());
        when(request.postData()).thenReturn(null);
        when(request.resourceType()).thenReturn("xhr");
        when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = mock(Frame.class);
        when(frame.parentFrame()).thenReturn(null);
        when(request.frame()).thenReturn(frame);
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        //  Protocol call (resume) happens -> record the calling thread and flag if off-main
        //  (a non-main owning thread).
        doAnswer(inv -> recordProtocolCall()).when(route).resume();
        return route;
    }

    private Object recordProtocolCall() {
        totalDeliveries.incrementAndGet();
        String t = Thread.currentThread().getName();
        protocolCallThreads.add(t);
        if (!mainThreadName.equals(t)) {
            offThreadProtocolCalls.incrementAndGet();
        }
        return null;
    }

    @Test
    public void torture_delayDeliveryNeverRunsOffMainThread() throws Exception {
        mockContext();
        //  Multiple DELAY rules + multiple mock requests, to build concurrent delivery pressure.
        String[] patterns = {"/api/a/**", "/api/b/**", "/api/c/**", "/api/d/**"};
        Route[] routes = {
                mockRoute("http://h/api/a/1"), mockRoute("http://h/api/b/1"),
                mockRoute("http://h/api/c/1"), mockRoute("http://h/api/d/1")
        };
        Consumer<Route>[] handlers = new Consumer[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            handlers[i] = arm(patterns[i]);
        }

        int workers = 8;
        int iterations = 300;
        Thread[] ts = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            final int seed = w;
            ts[w] = new Thread(() -> {
                for (int k = 0; k < iterations; k++) {
                    int idx = (k + seed) % patterns.length;
                    handlers[idx].accept(routes[idx]); //  simulate event-thread concurrent DELAY driving
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "torture-worker-" + w);
            ts[w].start();
        }

        //  Main owning thread: deliberately "slow drain" to build queue pressure (mimics gaps
        //  between business steps). No protocol call may ever execute on a non-main thread.
        //  delay(1) = 1s, so the window must cover delivery arrival.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            rt.drainMainThreadActions();
            try {
                Thread.sleep(3);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread t : ts) {
            t.join(5000);
        }
        rt.drainMainThreadActions();

        assertTrue("DELAY delivery never happened -- torture produced no protocol call at all "
                + "(assertion is vacuous): " + totalDeliveries.get(),
                totalDeliveries.get() > 0);
        assertTrue("Sec.8.4 regression: protocol calls (resume) executed on a non-main thread = "
                + offThreadProtocolCalls.get() + ", call threads=" + new ArrayList<>(protocolCallThreads),
                offThreadProtocolCalls.get() == 0);
    }
}

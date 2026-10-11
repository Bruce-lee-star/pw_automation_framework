package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Context close 耗时探针（2026-10-10）：实测框架 {@link PlaywrightContextManager#closeContext} 收尾耗时分布，
 * 为 {@code CloseGuard.CONTEXT_CLOSE_LIMIT_MS = 1500} 的取值提供实测数据依据。
 *
 * <p><b>探针性质</b>：不设硬断言（数据收集，非功能回归）；依赖靶场 {@code http://127.0.0.1:8899} 存活
 * （{@code route-testbed} HttpServer）。三组对照：
 * <ul>
 *   <li><b>A-plain</b>：常规页面（home）—— 基线；</li>
 *   <li><b>B-sse</b>：页面打开常开 SSE（{@code /api/notifications/streams}）—— 模拟 SIT「卸载前须先断流」最坏场景；</li>
 *   <li><b>C-routed</b>：注册 route handler 后关闭 —— 模拟 E2E 路由收尾场景。</li>
 * </ul>
 * 输出每组 min / P50 / P90 / P95 / P99 / max / 均值，以及超 1000ms / 超 1500ms 次数。</p>
 */
public class ContextCloseProbeTest {

    private static final String BASE_URL = "http://127.0.0.1:8899";
    private static final int SAMPLES = 20;
    private static final long DWELL_MS = 2_000L;

    private static Playwright pw;
    private static Browser browser;

    @BeforeClass
    public static void initAll() {
        pw = Playwright.create();
        browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterClass
    public static void tearDownAll() {
        if (browser != null) {
            try { browser.close(); } catch (Exception ignored) { }
        }
        if (pw != null) {
            try { pw.close(); } catch (Exception ignored) { }
        }
    }

    @Test
    @Ignore("探针测试（非回归断言）：定向运行 mvn -o test -pl pw-web-ui -Dtest=ContextCloseProbeTest，需靶场 8899 存活")
    public void probeContextCloseTimings() {
        List<Long> plain = runGroup("A-plain-home", ctx -> { /* no-op: 仅导航 */ }, true, false, false);
        List<Long> sse = runGroup("B-sse-open", ctx -> { /* SSE 在导航后开启 */ }, true, true, false);
        List<Long> routed = runGroup("C-routed", ctx -> { /* route 在导航后注册 */ }, true, false, true);

        System.out.println("===== [PROBE] Context close 耗时分布 (ms) — 框架 PlaywrightContextManager.closeContext =====");
        printStats("A-plain-home   (常规页面)", plain);
        printStats("B-sse-open     (SSE 常开)", sse);
        printStats("C-routed       (路由注册后)", routed);
        System.out.println("===== [PROBE] 结论参考: CONTEXT_CLOSE_LIMIT_MS=1500 / PAGE_CLOSE=1000 / BROWSER_CLOSE=3000 =====");
    }

    private List<Long> runGroup(String name, Consumer<BrowserContext> prepare,
                                boolean navigate, boolean openSse, boolean registerRoute) {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            BrowserContext ctx = null;
            try {
                ctx = browser.newContext();
                Page page = ctx.newPage();
                if (navigate) {
                    page.navigate(BASE_URL + "/home");
                }
                if (openSse) {
                    page.evaluate("window.__probeEs = new EventSource('/api/notifications/streams'); true");
                }
                if (registerRoute) {
                    ctx.route("**/api/**", route -> route.resume());
                }
                Thread.sleep(DWELL_MS);
            } catch (Exception e) {
                System.out.println("[PROBE] " + name + " prepare error (sample " + i + "): " + e);
            }
            long t0 = System.nanoTime();
            try {
                PlaywrightContextManager.closeContext(ctx);
            } catch (Exception e) {
                System.out.println("[PROBE] " + name + " close error (sample " + i + "): " + e);
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            times.add(ms);
        }
        return times;
    }

    private static void printStats(String label, List<Long> times) {
        List<Long> sorted = new ArrayList<>(times);
        sorted.sort(Comparator.naturalOrder());
        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long p50 = percentile(sorted, 50);
        long p90 = percentile(sorted, 90);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);
        long over1s = sorted.stream().filter(t -> t > 1_000).count();
        long over1500 = sorted.stream().filter(t -> t > 1_500).count();
        System.out.printf("[PROBE] %-18s n=%-2d  min=%-4d p50=%-4d p90=%-4d p95=%-4d p99=%-4d max=%-4d avg=%-5.1f  >1000ms=%d  >1500ms=%d%n",
                label, sorted.size(), min, p50, p90, p95, p99, max, avg, over1s, over1500);
    }

    private static long percentile(List<Long> sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}

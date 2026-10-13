package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.Socket;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 端到端验证（真实浏览器 + 真实靶场 {@code route-testbed:8899}）：证明本次改动后，
 * ① {@code .monitor()} 默认连响应体一起采（无需 {@code .captureBody(true)}）；
 * ② {@code route.capture.all.type=true} 时 MOCK 等非 MONITOR 规则也被采集且含 body。
 *
 * <p>靶场端点默认只匹配 xhr/fetch（{@code onlyApiCall}），故用例用 {@code fetch} 触发 API，
 * 而非把浏览器直接导航到 API 端点（那是 document 请求，会被采集匹配跳过）。
 *
 * <p><b>环境守卫</b>：靶场 8899 不可达或 Playwright 浏览器不可用时，整套用例经 {@link Assume} 自动跳过，
 * 不污染默认单测门禁（与 {@code Manual302ProbeTest} 同源约定，但用 Assume 而非 @Ignore，
 * 以便 {@code -Dtest=RouteCaptureBodyEndToEndTest} 定向运行）。
 */
public class RouteCaptureBodyEndToEndTest {

    private static final String TESTBED = "http://127.0.0.1:8899";
    private Playwright playwright;
    private Browser browser;
    private BrowserContext ctx;

    @Before
    public void setup() {
        Assume.assumeTrue("route-testbed 8899 不可达 -> 跳过端到端", isTestbedReachable());
        try {
            this.playwright = Playwright.create();
        } catch (Exception e) {
            Assume.assumeNoException("Playwright 浏览器不可用 -> 跳过端到端", e);
        }
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        this.ctx = browser.newContext();
    }

    @After
    public void cleanup() {
        if (ctx != null) {
            try {
                RouteEngine.shutdown(ctx);
            } catch (Exception ignored) {
                // 收尾尽力而为
            }
            try {
                ctx.close();
            } catch (Exception ignored) {
                // 收尾尽力而为
            }
        }
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception ignored) {
                // 收尾尽力而为
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {
                // 收尾尽力而为
            }
        }
    }

    private static boolean isTestbedReachable() {
        try (Socket ignored = new Socket("127.0.0.1", 8899)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 轮询至该 context 下出现采集记录（采集落库为消费式 flush，需等待）。 */
    private CapturedApiCall waitForCapture(long waitMs) {
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        List<CapturedApiCall> calls;
        do {
            calls = RouteEngine.allCapturedApis(ctx);
            if (!calls.isEmpty()) {
                return calls.get(0);
            }
            LockSupport.parkNanos(20_000_000L);
        } while (System.nanoTime() < deadline);
        return null;
    }

    @Test
    public void monitorCapturesResponseBodyFromLiveTestbed() {
        RouteEngine.runtimeOf(ctx);
        // 业务层写法：只有 .monitor()，没有任何 .capture() / .captureBody(true)
        RouteDsl.on(ctx).api("/api/profile/list").monitor().done().start();

        // 页面保持打开：响应体读取发生在消费式 flush（查询时触发），须待采集落库后再随 context 关闭
        Page page = ctx.newPage();
        page.navigate(TESTBED + "/login", new Page.NavigateOptions().setTimeout(15000));
        page.evaluate("fetch('/api/profile/list').then(r => r.status)");

        CapturedApiCall call = waitForCapture(10_000L);
        assertNotNull("MONITOR 规则必须产生采集记录", call);
        assertNotNull("MONITOR 默认必须采集响应体（无需 .captureBody(true)）", call.responseBody());
        assertTrue("响应体应包含真实靶场字段", call.responseBody().contains("enableAdminTools"));
        assertEquals("状态码来自真实响应", 200L, (long) call.responseStatus());
    }

    @Test
    public void allTypesTrueCapturesMockResponseBody() {
        String prev = System.getProperty("route.capture.all.type");
        try {
            // 策略在运行时创建时冻结 ⇒ 属性必须先设
            System.setProperty("route.capture.all.type", "true");
            RouteEngine.runtimeOf(ctx);
            RouteDsl.on(ctx).api("/api/e2e-mock").mock().status(200).contentType("application/json")
                    .mockBody("{\"mocked\":true}").done().start();

            // 页面保持打开：响应体读取发生在消费式 flush（查询时触发），须待采集落库后再随 context 关闭
            Page page = ctx.newPage();
            page.navigate(TESTBED + "/login", new Page.NavigateOptions().setTimeout(15000));
            page.evaluate("fetch('/api/e2e-mock').then(r => r.status)");

            CapturedApiCall call = waitForCapture(10_000L);
            assertNotNull("allTypes=true 时 MOCK 规则必须被采集", call);
            assertNotNull("MOCK 采集必须含响应体", call.responseBody());
            assertEquals("MOCK  fulfilled 体应被原样采集", "{\"mocked\":true}", call.responseBody());
            assertEquals("主渠道必须是 MOCK", "MOCK", call.primaryChannel());
        } finally {
            System.setProperty("route.capture.all.type", prev == null ? "false" : prev);
        }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservationProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * 隔离靶场 SPI 的 Playwright 实现（设计 §3.1.2.1 / R4 / R8）。
 *
 * <p>为分析期 / 受控验证在<b>独立</b>的 Playwright 浏览器实例中提供 {@link BrowserCommandChannel}
 * —— 不复用生产浏览器管理（{@code BrowserStartupImpl}/{@code PlaywrightRuntime}），从而与回归环境
 * 逻辑隔离，契合"分析 / 重放仅在靶场"的设计意图。</p>
 *
 * <p>生命周期：首个 {@link #acquire(String)} 惰性创建 Playwright 实例并启动 chromium；每次 acquire
 * 新建独立 {@code BrowserContext} + {@code Page} 并导航到 targetUrl；{@link #release(BrowserCommandChannel)}
 * 关闭该通道的页面/上下文/浏览器；当所有通道释放完毕后关闭 Playwright 实例，避免 Node 子进程泄漏。</p>
 */
public final class RouteTestBedHarness implements IsolationHarness {

    private Playwright playwright;
    private final List<PlaywrightChannel> open = new ArrayList<>();
    /** 多语言目录（可选）：注入后靶场通道即可直接执行带 NLS key 的定位符（设计 §4.7）。 */
    private final NlsCatalog nls;
    /** 最近一次 acquire 创建的页面（供观察提供方复用，保证「执行」与「观察」同源同页面）。 */
    private Page lastPage;

    public RouteTestBedHarness() {
        this(null);
    }

    public RouteTestBedHarness(NlsCatalog nls) {
        this.nls = nls;
    }

    /** 靶场允许目标主机配置键：逗号分隔 host[:port]，默认空 = 全拒（fail-closed）。 */
    static final String ALLOWED_HOSTS_KEY = "pw.agent.testbed.allowedHosts";

    @Override
    public BrowserCommandChannel acquire(String targetUrl) {
        rejectNonSandboxTarget(targetUrl);
        if (playwright == null) {
            playwright = Playwright.create();
        }
        Browser browser = playwright.chromium()
                .launch(new BrowserType.LaunchOptions().setHeadless(true));
        BrowserContext context = browser.newContext();
        Page page = context.newPage();
        // R3 每跳重定向校验（设计 §10.4 / 企业出站由网络层兜底）：
        // 默认 WARN 模式<b>不</b>拦截任何请求 —— 登录 SSO、IdP 回调等合法流程本就会跨主机跳转，
        // 硬阻断会误伤。仅当显式开启 strict 模式（且已把 auth/IdP 主机列入 allow-list）时，
        // 才对命中非沙箱主机的请求 abort，用于在 CI 捕获"首跳沙箱、重定向逃逸生产"的绕过。
        if (RedirectValidationPolicy.guardMode() == RedirectValidationPolicy.RedirectGuardMode.STRICT) {
            page.route("**", (Route route) -> {
                if (RedirectValidationPolicy.isSandboxUrl(route.request().url())) {
                    route.resume();
                } else {
                    route.abort();
                }
            });
        }
        page.navigate(targetUrl);
        this.lastPage = page;
        PlaywrightChannel channel = new PlaywrightChannel(page, browser, context, nls);
        open.add(channel);
        return channel;
    }

    /**
     * 返回分析期页面观测提供方（设计 §4.6 ②），复用最近一次 {@link #acquire(String)} 创建的页面
     * 与已注入的 {@link NlsCatalog}。须先调用 {@code acquire(...)}。
     *
     * <p>观察提供方优先读取元素 {@code data-i18n} 作为跨语言稳定的 NLS key（见
     * {@link PlaywrightPageObservationProvider}），比"可访问名反查"更精确。</p>
     */
    public PageObservationProvider observationProvider() {
        if (lastPage == null) {
            throw new IllegalStateException("acquire(String) must be called before observationProvider()");
        }
        return new PlaywrightPageObservationProvider(lastPage, nls);
    }

    @Override
    public void release(BrowserCommandChannel channel) {
        if (!(channel instanceof PlaywrightChannel)) {
            return;
        }
        PlaywrightChannel pc = (PlaywrightChannel) channel;
        pc.close();
        open.remove(pc);
        if (open.isEmpty() && playwright != null) {
            quietlyClosePlaywright();
        }
    }

    /** 强制关闭所有通道与 Playwright 实例（测试 teardown 兜底）。 */
    public void closeAll() {
        for (PlaywrightChannel pc : new ArrayList<>(open)) {
            pc.close();
        }
        open.clear();
        quietlyClosePlaywright();
    }

    /**
     * fail-closed 靶场沙箱护栏（R4/R8 运行期落点）：受控重放/验证只能指向
     * file://（本地 fixture）、loopback 或 {@link #ALLOWED_HOSTS_KEY} 显式列出的主机；
     * 其余网络主机一律拒绝，确保隔离靶场不会被误指向生产环境。
     */
    static void rejectNonSandboxTarget(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new TestBedTargetRejectedException("<blank>", ALLOWED_HOSTS_KEY);
        }
        if (RedirectValidationPolicy.isSandboxUrl(targetUrl)) {
            return;
        }
        String host;
        try {
            URI uri = new URI(targetUrl);
            host = stripBrackets(uri.getHost());
        } catch (URISyntaxException e) {
            host = targetUrl;
        }
        throw new TestBedTargetRejectedException(host, ALLOWED_HOSTS_KEY);
    }

    /** IPv6 字面量在 {@link URI#getHost()} 中带方括号，去掉以便与 allow-list / loopback 判定一致。 */
    private static String stripBrackets(String host) {
        if (host == null) {
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private void quietlyClosePlaywright() {
        if (playwright == null) {
            return;
        }
        try {
            playwright.close();
        } catch (RuntimeException | Error ignored) {
            // 关闭期异常忽略
        }
        playwright = null;
    }
}

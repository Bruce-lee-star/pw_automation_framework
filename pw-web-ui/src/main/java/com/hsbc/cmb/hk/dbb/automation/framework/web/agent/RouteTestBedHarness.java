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

    @Override
    public BrowserCommandChannel acquire(String targetUrl) {
        if (playwright == null) {
            playwright = Playwright.create();
        }
        Browser browser = playwright.chromium()
                .launch(new BrowserType.LaunchOptions().setHeadless(true));
        BrowserContext context = browser.newContext();
        Page page = context.newPage();
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

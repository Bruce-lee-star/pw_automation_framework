package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotBounds;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.ActionTypePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotEnvelope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

import static com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command.ActionType.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * BrowserCommandChannel 的 Playwright 真实现（设计 3.1.3 / 4.1 / 4.5）。
 *
 * <p>把 pw-core 的类型化命令翻译为 Playwright 原语。仅暴露类型化操作，不向调用方暴露任何
 * evaluate / run_code_unsafe 入口（R2 红线由契约层保证）。</p>
 *
 * <p>快照来自 Playwright ARIA 快照（page.ariaSnapshot()），与 Playwright MCP 同源：仅枚举带
 * role/name 的可访问节点，天然契合 Role-First 定位；同时是语义化、数据最小化视图（R1/R7），
 * 不采集无关 DOM 文本。禁用态在快照中以 [disabled] 标注。</p>
 *
 * <p>定位翻译遵循 Role-First 梯队：ROLE 到 getByRole(role,{name})；TEST_ID 到 getByTestId；
 * ID 到 #id；TEXT 到 getByText；CSS 到 locator。</p>
 *
 * <p>本类由 RouteTestBedHarness（隔离靶场）创建与释放，不耦合生产浏览器生命周期，
 * 从而与回归环境逻辑隔离（R4/R8）。</p>
 */
public final class PlaywrightChannel implements BrowserCommandChannel {

    private Page page;
    private final Browser browser;
    private final BrowserContext context;
    /** 多语言目录（可选）：把 role 定位里的 NLS key 解析为当前语言文案（设计 §4.7）。 */
    private final NlsCatalog nls;
    /** 快照资源上界（§4.2）；默认 {@link SnapshotBounds#DEFAULT}。 */
    private final SnapshotBounds bounds;
    /**
     * 逐动作超时解析（§4.1 / P0 第 3 项运行期施加）：把 {@link ActionTypePolicy} 的
     * {@code defaultTimeout} 真正注入到每个 Playwright 调用；生产默认取策略元数据，
     * 测试可注入极短超时以确定性验证运行期超时生效。
     */
    private final TimeoutResolver timeoutResolver;
    /** 通道会话所有权（P0 第 4 项 · 并发所有权）：保证同一 page 会话的命令独占串行执行。 */
    private final SessionOwnership ownership;
    /** 捕获的 popup 新页面（P0 第 4 项 · Frame 生命周期）：click 触发 window.open 后由 onPopup 填充。 */
    private final List<Page> receivedPopups = new CopyOnWriteArrayList<>();
    /** 本通道内的快照序号，动作后每次重新快照单调递增。 */
    private long snapshotVersion;
    private final String contextId = "ctx-" + Integer.toHexString(System.identityHashCode(this));
    private String pageId = "page-" + Integer.toHexString(System.identityHashCode(this));

    /** 逐动作超时解析策略：运行期把元数据 defaultTimeout 施加到 Playwright 调用。 */
    @FunctionalInterface
    interface TimeoutResolver {
        long timeoutMillis(Command.ActionType action);
    }

    private static final TimeoutResolver DEFAULT_TIMEOUT_RESOLVER =
            a -> ActionTypePolicy.of(a).defaultTimeout().toMillis();

    PlaywrightChannel(Page page, Browser browser, BrowserContext context) {
        this(page, browser, context, null);
    }

    /**
     * @param nls 多语言目录；{@code null} 表示单语言（定位符里只有字面文案）
     */
    PlaywrightChannel(Page page, Browser browser, BrowserContext context, NlsCatalog nls) {
        this(page, browser, context, nls, SnapshotBounds.DEFAULT);
    }

    /** 包内构造器：可注入资源上界（有界协议测试 / 大页面显式放宽）。 */
    PlaywrightChannel(Page page, Browser browser, BrowserContext context, NlsCatalog nls, SnapshotBounds bounds) {
        this(page, browser, context, nls, bounds, DEFAULT_TIMEOUT_RESOLVER);
    }

    /** 包内构造器：额外注入超时解析（真实浏览器超时测试用极短超时确定性验证）。 */
    PlaywrightChannel(Page page, Browser browser, BrowserContext context, NlsCatalog nls,
                      SnapshotBounds bounds, TimeoutResolver timeoutResolver) {
        this(page, browser, context, nls, bounds, timeoutResolver, SessionOwnership.locked());
    }

    /** 包内构造器：额外注入会话所有权（并发所有权测试可注入自定义实现）。 */
    PlaywrightChannel(Page page, Browser browser, BrowserContext context, NlsCatalog nls,
                      SnapshotBounds bounds, TimeoutResolver timeoutResolver, SessionOwnership ownership) {
        this.page = page;
        this.browser = browser;
        this.context = context;
        this.nls = nls;
        this.bounds = bounds;
        this.timeoutResolver = timeoutResolver;
        this.ownership = ownership;
        // Frame 生命周期（P0 第 4 项）：注册 popup 捕获，使 window.open 弹出的新页面可被驱动捕获
        page.onPopup(popup -> receivedPopups.add(popup));
    }

    private long timeoutFor(Command.ActionType action) {
        return timeoutResolver.timeoutMillis(action);
    }

    @Override
    public synchronized Snapshot snapshot() {
        long version = ++snapshotVersion;
        String url;
        try {
            url = page.url();
        } catch (RuntimeException e) {
            url = "<unknown>";
        }
        // 采集异常不再静默返回「空快照」（会被误当作「页面确实没有元素」），
        // 而是明确标注 UNSUPPORTED，交由 SnapshotVerifier 的 fail-closed 门禁处理。
        String raw;
        try {
            raw = page.ariaSnapshot();
        } catch (RuntimeException e) {
            SnapshotEnvelope envelope = SnapshotEnvelope.unsupported(
                    contextId, pageId, "main", version,
                    "ARIA_COLLECTION_FAILED: " + e.getClass().getSimpleName());
            return new Snapshot(url, List.of(), envelope);
        }
        return AriaSnapshotParser.parse(raw, url, contextId, pageId, "main", version, bounds).snapshot();
    }

    @Override
    public CommandResult execute(Command command) {
        // 并发所有权（P0 第 4 项）：同一 page 会话的命令经所有权契约独占串行执行，
        // 防止多 StepDriver / 多线程交错驱动同一通道导致 Playwright 并发异常。
        return ownership.runExclusive(() -> executeOnPage(command));
    }

    private CommandResult executeOnPage(Command command) {
        try {
            Command.ActionType action = command.action();
            Locator locator = command.target() == null ? null : toLocator(command.target());
            Map<String, String> params = command.params();
            switch (action) {
                case NAVIGATE:
                    page.navigate(params.get("url"),
                            new Page.NavigateOptions().setTimeout(timeoutFor(NAVIGATE)));
                    break;
                case CLICK:
                    require(locator, action).click(
                            new Locator.ClickOptions().setTimeout(timeoutFor(CLICK)));
                    break;
                case FILL:
                    require(locator, action).fill(params.get("value"),
                            new Locator.FillOptions().setTimeout(timeoutFor(FILL)));
                    break;
                case SELECT:
                    require(locator, action).selectOption(params.get("option"),
                            new Locator.SelectOptionOptions().setTimeout(timeoutFor(SELECT)));
                    break;
                case CHECK:
                    require(locator, action).check(
                            new Locator.CheckOptions().setTimeout(timeoutFor(CHECK)));
                    break;
                case UNCHECK:
                    require(locator, action).uncheck(
                            new Locator.UncheckOptions().setTimeout(timeoutFor(UNCHECK)));
                    break;
                case WAIT:
                    if (locator != null) {
                        locator.waitFor(new Locator.WaitForOptions().setTimeout(timeoutFor(WAIT)));
                    } else {
                        page.waitForLoadState(LoadState.LOAD,
                                new Page.WaitForLoadStateOptions().setTimeout(timeoutFor(WAIT)));
                    }
                    break;
                case ASSERT_VISIBLE:
                    require(locator, action).waitFor(
                            new Locator.WaitForOptions().setTimeout(timeoutFor(ASSERT_VISIBLE)));
                    break;
                case ASSERT_TEXT:
                    require(locator, action);
                    String actual = locator.textContent();
                    String expected = params.get("text");
                    if (expected == null || !expected.equals((actual == null ? "" : actual.trim()))) {
                        return CommandResult.fail("ASSERT_TEXT mismatch: expected=[" + expected
                                + "] actual=[" + (actual == null ? "" : actual.trim()) + "]");
                    }
                    break;
                case SWITCH_TO_POPUP:
                    switchToPopup();
                    break;
                default:
                    return CommandResult.fail("Unsupported action: " + action);
            }
            return CommandResult.ok(snapshot());
        } catch (RuntimeException e) {
            return CommandResult.fail(e.getMessage());
        }
    }

    /**
     * 切换到最近弹出的新页面（P0 第 4 项 · Frame 生命周期）。
     *
     * <p>click 触发 {@code window.open} 后，新页面经 {@code onPopup} 捕获进 {@link #receivedPopups}；
     * 本方法把当前驱动目标切到该 popup（同一 context，资源随 {@link #close()} 一并释放）。
     * 若尚未捕获（回调异步），则以 {@link Command.ActionType#WAIT} 策略超时等待下一次 popup 事件兜底。</p>
     */
    private Page switchToPopup() {
        Page popup = awaitPopup();
        this.page = popup;
        this.pageId = "page-" + Integer.toHexString(System.identityHashCode(popup));
        // popup 由 window.open 异步加载，给一点 load 等待（失败不阻断切页）
        try {
            popup.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(timeoutFor(WAIT)));
        } catch (RuntimeException ignored) {
            // 导航进行中或已就绪：切页已生效，后续命令会重新解析
        }
        return popup;
    }

    /**
     * 等待 onPopup 回调捕获 popup（click 触发 window.open 后由 {@link #receivedPopups} 填充）。
     * Playwright 的 {@code onPopup} 通常在 click 返回前已派发，此处仅作异步边界兜底。
     */
    private Page awaitPopup() {
        long timeoutMs = timeoutFor(WAIT);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (receivedPopups.isEmpty()) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException("No popup captured within " + timeoutMs + "ms");
            }
            LockSupport.parkNanos(20_000_000L);
        }
        return receivedPopups.get(receivedPopups.size() - 1);
    }

    /**
     * 把 Role-First 定位符翻译为 Playwright Locator。
     *
     * <p>翻译只有一份实现（{@link LocatorResolver}）：命令通道与候选取证探针共用，
     * 保证"探测到的"与"将来执行到的"是同一条定位。</p>
     */
    private Locator toLocator(LocatorSpec spec) {
        return LocatorResolver.resolve(page, spec, nls);
    }

    /** 关闭底层页面/上下文/浏览器（幂等）。 */
    public void close() {
        quietlyClose(context);
        quietlyClose(browser);
    }

    private static Locator require(Locator locator, Command.ActionType action) {
        if (locator == null) {
            throw new IllegalArgumentException("Action " + action + " requires a target");
        }
        return locator;
    }

    private static void quietlyClose(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (RuntimeException | Error e) {
            // 关闭期异常忽略（资源已释放或已失效）
        } catch (Exception e) {
            // 同上
        }
    }
}

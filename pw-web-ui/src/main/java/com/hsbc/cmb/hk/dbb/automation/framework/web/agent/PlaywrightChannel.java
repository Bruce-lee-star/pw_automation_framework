package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final Page page;
    private final Browser browser;
    private final BrowserContext context;
    /** 多语言目录（可选）：把 role 定位里的 NLS key 解析为当前语言文案（设计 §4.7）。 */
    private final NlsCatalog nls;

    public PlaywrightChannel(Page page, Browser browser, BrowserContext context) {
        this(page, browser, context, null);
    }

    /**
     * @param nls 多语言目录；{@code null} 表示单语言（定位符里只有字面文案）
     */
    public PlaywrightChannel(Page page, Browser browser, BrowserContext context, NlsCatalog nls) {
        this.page = page;
        this.browser = browser;
        this.context = context;
        this.nls = nls;
    }

    @Override
    public Snapshot snapshot() {
        // ARIA 快照枚举（隐藏元素已被排除，隐含可见），逐节点生成 Role-First 定位符
        // （role + 可访问名 + 同组出现序号）作为快照索引键。
        List<Snapshot.ElementState> states = new ArrayList<>();
        try {
            String raw = page.ariaSnapshot();
            states.addAll(parseAria(raw));
        } catch (RuntimeException e) {
            // 快照采集失败不影响命令执行；返回空快照，断言阶段自然判失败
            return new Snapshot(page.url(), states);
        }
        return new Snapshot(page.url(), states);
    }

    /** 解析 ARIA 快照文本为角色优先的元素状态列表。 */
    private List<Snapshot.ElementState> parseAria(String raw) {
        List<Snapshot.ElementState> states = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();
        if (raw == null) {
            return states;
        }
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("- ")) {
                continue; // 跳过属性/续行（如 - /url:、- text:）
            }
            String body = t.substring(2).trim();
            if (body.startsWith("/")) {
                continue; // 属性行，如 - /url: https://...
            }
            // role：第一个 token（遇空格/"[/: 截断）
            int end = body.length();
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == ' ' || c == '"' || c == '[' || c == ':') {
                    end = i;
                    break;
                }
            }
            String role = body.substring(0, end);
            if ("text".equals(role)) {
                continue; // 跳过纯文本节点
            }
            // name：首个引号对内的内容
            String name = null;
            int q1 = body.indexOf('"');
            if (q1 >= 0) {
                int q2 = body.indexOf('"', q1 + 1);
                if (q2 > q1) {
                    name = body.substring(q1 + 1, q2);
                }
            }
            boolean disabled = body.contains("[disabled]");
            String key = role + "|" + (name == null ? "" : name);
            int nth = counters.getOrDefault(key, 0);
            counters.put(key, nth + 1);
            LocatorSpec spec = LocatorSpec.role(role, name, nth);
            String safeName = name == null ? "" : name;
            states.add(new Snapshot.ElementState(
                    spec.canonical(), role, safeName, true, !disabled, safeName));
        }
        return states;
    }

    @Override
    public CommandResult execute(Command command) {
        try {
            Command.ActionType action = command.action();
            Locator locator = command.target() == null ? null : toLocator(command.target());
            Map<String, String> params = command.params();
            switch (action) {
                case NAVIGATE:
                    page.navigate(params.get("url"));
                    break;
                case CLICK:
                    require(locator, action).click();
                    break;
                case FILL:
                    require(locator, action).fill(params.get("value"));
                    break;
                case SELECT:
                    require(locator, action).selectOption(params.get("option"));
                    break;
                case CHECK:
                    require(locator, action).check();
                    break;
                case UNCHECK:
                    require(locator, action).uncheck();
                    break;
                case WAIT:
                    if (locator != null) {
                        locator.waitFor();
                    } else {
                        page.waitForLoadState();
                    }
                    break;
                case ASSERT_VISIBLE:
                    require(locator, action).waitFor();
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
                default:
                    return CommandResult.fail("Unsupported action: " + action);
            }
            return CommandResult.ok(snapshot());
        } catch (RuntimeException e) {
            return CommandResult.fail(e.getMessage());
        }
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

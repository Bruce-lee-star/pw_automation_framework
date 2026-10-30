package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.quiet.CodegenQuiet;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * 面板窗口：Chrome {@code --app=} 独立窗口（无地址栏/标签页，外观等同桌面应用）。
 *
 * <p>通信采用 Playwright 原生 {@code exposeBinding}（面板 → Java 命令）与 {@code evaluate}
 * （Java → 面板数据推送），不需要 WebSocket / Electron。窗口本身可由 OS 拖拽边缘缩放，面板内部用 flex
 * 响应式布局填满窗口，彻底解决 v2"固定大小不可拖拽"的体验短板。
 */
public final class CodegenPanel {

    private static final Logger log = LoggerFactory.getLogger(CodegenPanel.class);

    /** 面板浏览器 profile 目录的系统属性覆盖项（默认复用系统临时目录下的固定目录，见 {@link #panelProfileDir()}）。 */
    private static final String PROFILE_DIR_PROPERTY = "codegen.panel.profile.dir";

    /** 浏览器进程启动上限：profile 被占用/驱动异常时快速失败并降级，绝不让面板无限等待。 */
    private static final double LAUNCH_TIMEOUT_MS = 60_000;

    /** 面板页导航上限（本地 http，正常毫秒级；有界以防代理/驱动异常时卡满默认 30s）。 */
    private static final double NAVIGATE_TIMEOUT_MS = 20_000;

    /** 面板可交互等待上限：判据是命令桥就绪（{@code window.__codegenOutbox}），不是 load 事件。 */
    private static final double READY_TIMEOUT_MS = 5_000;

    /** 命令 outbox 轮询间隔：面板按钮反馈的延迟上限（本地 evaluate，开销极低）。 */
    private static final long COMMAND_POLL_INTERVAL_MS = 100;

    /**
     * 「Java → 面板」推送的串行化锁（见 {@link #safeEval}）。
     *
     * <p>跨面板实例共享即可：同一进程内面板页之间互不相关，多一把锁不会成为瓶颈（每次 evaluate 毫秒级）。
     */
    private static final Object PUSH_LOCK = new Object();

    private final Playwright panelPlaywright;
    private final BrowserContext panelCtx;
    private final Page panel;
    private final HttpServer panelServer;
    private final ScheduledExecutorService cmdScheduler;

    private CodegenPanel(Playwright panelPlaywright, BrowserContext panelCtx, Page panel,
                         HttpServer panelServer, ScheduledExecutorService cmdScheduler) {
        this.panelPlaywright = panelPlaywright;
        this.panelCtx = panelCtx;
        this.panel = panel;
        this.panelServer = panelServer;
        this.cmdScheduler = cmdScheduler;
    }

    /**
     * 用 {@code --app=} 模式打开面板窗口并加载 panel.html，注册命令桥。
     *
     * @param panelHtml   panel.html 本地路径（见 {@link PanelResource}）
     * @param onCommand   面板按钮/行交互回调，参数列表首元素为命令名（如 start/stop/close/highlight），
     *                   其后为可选参数（如 highlight 后的 sigKey）
     */
    public static CodegenPanel open(Path panelHtml, Consumer<List<Object>> onCommand, String nlsLang) {
        // 面板资源经 PanelResource 导出到临时目录（或开发态直接用 src/main/resources/codegen）；
        // 改为用内嵌本地 HTTP 服务托管，以 http://127.0.0.1:PORT/panel.html 取代 file:// 打开。
        // 原因：Chrome 把每个 file:// 视为「独立安全源（unique origin）」，面板内的 exposeBinding /
        // 相对资源加载 / evaluate 在该源下会被安全策略干扰，控制台报
        // "Unsafe attempt to load URL file://... 'file:' URLs are treated as unique security origins"，
        // 并可能导致 Start 命令通道（__codegenCmd）静默失效。HTTP 同源（localhost）彻底消除该问题。
        long tStart = System.currentTimeMillis();
        Path serveDir = panelHtml.getParent().toAbsolutePath().normalize();
        HttpServer server = startPanelServer(serveDir);
        String panelUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/panel.html";
        long tServer = System.currentTimeMillis();
        // 命令轮询调度器（单线程）：命令处理必须脱离 Playwright 连接（消息派发）线程——若在派发线程上
        // 同步调用 panel.evaluate / cdp.send 并阻塞等响应，会收不到响应而死锁（表现即「点 Start 面板冻结」）。
        // 用固定延迟调度而非自旋 + Thread.sleep：不忙等、可精确取消；单线程天然串行，
        // 保证 start/stop/group 的先后顺序。
        ScheduledExecutorService cmdScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                // NLS 当前语言是 ThreadLocal（LanguageState）；命令处理（含 Stop 时的类生成）在独立线程执行，
                // 必须把语言带过来，否则 NlsReverseLookup 会因当前线程未设语言而抛错。
                if (nlsLang != null) com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState.setLanguage(nlsLang);
                r.run();
            }, "codegen-panel-cmd");
            t.setDaemon(true);
            return t;
        });

        Playwright pw = createPanelPlaywright();
        long tPlaywright = System.currentTimeMillis();
        BrowserContext ctx = launchPanelContext(pw, panelUrl);
        long tLaunch = System.currentTimeMillis();
        // --app= 通常会自动打开该 URL 的首页；若只建了空白页（或未建页）则自己建/导航。
        // 必须按 URL 认页：直接把 pages().get(0) 当面板页，在某些 Chromium 版本上会拿到 about:blank，
        // 导致面板看得见、按钮全无反应（命令桥落到了空白页）。
        Page p = resolvePanelPage(ctx, panelUrl);
        long tPage = System.currentTimeMillis();
        // 命令桥：面板 JS 自己定义 window.__codegenCmd，把命令推入 window.__codegenOutbox 队列；
        // 这里按固定延迟取走并分发。不再用 exposeBinding —— 它在 --app= 持久上下文窗口里注入不稳定
        // （命令偶尔根本到不了 Java，表现即「点 Start 毫无反应」徽标停在 ready）。
        final Page panelPage = p;
        cmdScheduler.scheduleWithFixedDelay(
                () -> pollCommandsOnce(panelPage, onCommand), 0, COMMAND_POLL_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        // 就绪判据是「命令桥可用」（panel.js 顶部定义的 window.__codegenOutbox），而不是 load/DOMContentLoaded
        // 事件：Monaco 等增强资源从 CDN 注入、公司网不可达时 load 事件会被拖很久，但拾取/录制根本不需要它。
        // 等待失败同样不阻断——轮询线程会在页面真正就绪后自然接上命令。
        waitPanelInteractive(p);
        long tReady = System.currentTimeMillis();
        log.info("[codegen][panel] startup timings(ms): server={} playwright={} launch={} page={} ready={} total={} (profile={})",
                tServer - tStart, tPlaywright - tServer, tLaunch - tPlaywright,
                tPage - tLaunch, tReady - tPage, tReady - tStart, panelProfileDir());
        log.info("[codegen][panel] panel window opened (Chrome --app=, served via http://127.0.0.1:{}): {} -> {}",
                server.getAddress().getPort(), panelHtml, p.url());
        return new CodegenPanel(pw, ctx, p, server, cmdScheduler);
    }

    /**
     * 取走面板 outbox 中的命令并串行分发（调度器每 {@value #COMMAND_POLL_INTERVAL_MS}ms 调一次）。
     *
     * <p>异常必须被吞掉：{@code scheduleWithFixedDelay} 的任务一旦抛出，后续调度会被静默取消，
     * 命令桥就永久失效了（面板页未就绪、导航中、已关闭都会命中）。
     */
    private static void pollCommandsOnce(Page panelPage, Consumer<List<Object>> onCommand) {
        try {
            // 取走并清空 outbox；splice 返回被移除的元素（二维数组：[命令][参数...]）。
            Object out = panelPage.evaluate("() => (window.__codegenOutbox || []).splice(0)");
            if (!(out instanceof List)) {
                return;
            }
            for (Object c : (List<?>) out) {
                if (!(c instanceof List)) continue;
                @SuppressWarnings("unchecked")
                List<Object> cmd = (List<Object>) c;
                if (cmd.isEmpty() || cmd.get(0) == null) continue;
                log.info("[codegen][panel] command received: {}", cmd.get(0));
                try {
                    onCommand.accept(cmd);
                } catch (Exception e) {
                    log.warn("[codegen][panel] command handling failed: {}", e.getMessage());
                }
            }
        } catch (Throwable ignore) {
            // 面板页尚未就绪或已关闭：本拍跳过，下一拍继续（下一拍由调度器保证）。
        }
    }

    /**
     * 面板专用 {@link Playwright} 实例。
     *
     * <p><b>必须显式关掉 driver 的浏览器安装/校验步骤</b>（{@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1}）：
     * 该步骤在每次 {@code Playwright.create()} 时都会校验本地浏览器版本，公司网下会走网络。本机实测
     * {@code create()} 由 <b>7730ms</b> 降到 <b>881ms</b>（网络更差时是数十秒）。面板只用本机已装好的
     * Chromium，无需这一步；浏览器目录沿用进程环境变量 {@code PLAYWRIGHT_BROWSERS_PATH}，与框架主浏览器
     * 共享同一份缓存，避免重复下载。
     */
    private static Playwright createPanelPlaywright() {
        Map<String, String> env = new HashMap<>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Playwright.CreateOptions options = new Playwright.CreateOptions();
        options.setEnv(env);
        return Playwright.create(options);
    }

    /**
     * 启动面板浏览器。profile 目录<b>跨次复用</b>（暖 profile）：本机实测全新 profile 启动 4944ms、
     * 复用同一 profile 958ms（新 profile 要建目录树/初始化偏好，机器上的安全软件还要逐个文件扫描）。
     *
     * <p>暖 profile 可能被同机另一个面板实例占用、或上次异常退出而损坏，故失败时退化为一次性全新
     * profile 重试一次，保证"慢一点但一定能开"。
     */
    private static BrowserContext launchPanelContext(Playwright pw, String panelUrl) {
        List<String> args = panelLaunchArgs(panelUrl);
        Path profile = panelProfileDir();
        try {
            return launchPersistent(pw, profile, args);
        } catch (RuntimeException e) {
            log.warn("[codegen][panel] launch with warm profile failed ({}), retrying with a fresh profile: {}",
                    profile, e.getMessage());
            Path fresh;
            try {
                fresh = Files.createTempDirectory("codegen-panel-profile");
            } catch (IOException io) {
                throw new RuntimeException("cannot create panel profile dir", io);
            }
            fresh.toFile().deleteOnExit();
            return launchPersistent(pw, fresh, args);
        }
    }

    private static BrowserContext launchPersistent(Playwright pw, Path profile, List<String> args) {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(false)
                .setTimeout(LAUNCH_TIMEOUT_MS)
                .setArgs(args);
        return pw.chromium().launchPersistentContext(profile, options);
    }

    private static List<String> panelLaunchArgs(String panelUrl) {
        return List.of(
                "--app=" + panelUrl,
                "--window-size=1000,800",
                "--window-position=140,90",
                // Chromium 默认会在启动/首跑时联网做组件更新、同步、健康上报等；公司网墙住后各自空等
                // 数十秒（这正是面板冷启动 25~45s 死窗的元凶）。面板只加载 127.0.0.1 的本地资源，全部关掉。
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-component-update",
                "--disable-background-networking",
                "--disable-sync",
                "--disable-domain-reliability",
                "--disable-features=Translate,OptimizationHints,MediaRouter");
    }

    /**
     * 面板浏览器 profile 目录：默认复用系统临时目录下的固定目录，使第二次及以后的面板启动走「暖 profile」。
     * 可用 {@code -Dcodegen.panel.profile.dir=<dir>} 指定（例如放到工程外固定盘位）。
     */
    private static Path panelProfileDir() {
        String configured = System.getProperty(PROFILE_DIR_PROPERTY);
        Path dir = (configured == null || configured.trim().isEmpty())
                ? Path.of(System.getProperty("java.io.tmpdir"), "codegen-panel-profile")
                : Path.of(configured.trim());
        dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create panel profile dir: " + dir, e);
        }
        return dir;
    }

    /**
     * 选出承载面板的页面：优先按 URL 前缀匹配（{@code --app=} 已打开面板）；匹配不到则复用现有页并导航。
     *
     * <p>暖 profile 可能恢复出上一次的旧页面（旧端口），故只保留一个承载页、其余关闭，避免窗口堆积与
     * "命令桥挂在旧页上"的假死。
     */
    private static Page resolvePanelPage(BrowserContext ctx, String panelUrl) {
        for (Page candidate : ctx.pages()) {
            String url = candidate.url();
            if (url != null && url.startsWith(panelUrl)) {
                return candidate;
            }
        }
        List<Page> pages = new ArrayList<>(ctx.pages());
        Page target;
        if (pages.isEmpty()) {
            target = ctx.newPage();
        } else {
            target = pages.get(0);
            for (Page extra : pages) {
                if (extra == target) continue;
                try {
                    extra.close();
                } catch (RuntimeException e) {
                    log.debug("[codegen][panel] close stale page failed: {}", e.getMessage());
                }
            }
        }
        if (target.url() == null || !target.url().startsWith(panelUrl)) {
            target.navigate(panelUrl, new Page.NavigateOptions().setTimeout(NAVIGATE_TIMEOUT_MS));
        }
        return target;
    }

    /** 等面板命令桥就绪（{@code window.__codegenOutbox} 由 panel.js 顶部同步定义）；超时不阻断。 */
    private static void waitPanelInteractive(Page p) {
        try {
            p.waitForFunction("() => typeof window.__codegenOutbox !== 'undefined'", null,
                    new Page.WaitForFunctionOptions().setTimeout(READY_TIMEOUT_MS));
        } catch (RuntimeException e) {
            log.warn("[codegen][panel] panel not interactive within {}ms (continuing): {}",
                    (long) READY_TIMEOUT_MS, e.getMessage());
        }
    }

    /**
     * 在 127.0.0.1 随机端口起一个仅本机可达的静态文件服务，托管面板资源（panel.html/js/css）。
     * 用 http 同源取代 file:// 打开面板，规避 Chrome 对 file:// 独立安全源的限制。
     */
    private static HttpServer startPanelServer(Path serveDir) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    servePanelFile(exchange, serveDir);
                } catch (Exception e) {
                    try {
                        exchange.sendResponseHeaders(500, -1);
                    } catch (IOException ignore) {
                        CodegenQuiet.ignore("CodegenPanel#serve", ignore);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new RuntimeException("cannot start panel http server", e);
        }
    }

    private static void servePanelFile(HttpExchange exchange, Path serveDir) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // 浏览器会自动请求 /favicon.ico；面板无需图标，直接回 204 避免控制台 404 噪音。
        if ("/favicon.ico".equals(path)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        Path file = serveDir.resolve(path.equals("/") ? "panel.html" : path.substring(1)).normalize();
        // 防目录穿越：解析后必须仍在 serveDir 内。
        if (!file.startsWith(serveDir) || !Files.exists(file) || Files.isDirectory(file)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        String mime = file.toString().endsWith(".html") ? "text/html; charset=utf-8"
                : file.toString().endsWith(".js") ? "text/javascript; charset=utf-8"
                : file.toString().endsWith(".css") ? "text/css; charset=utf-8"
                : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().add("Content-Type", mime);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 推送一条拾取结果到面板元素列表。
     *
     * @param pickId 本次拾取的唯一 id（同一元素在不同容器可能与既有条目 sigKey 相同，
     *               必须靠它区分列表行，见 {@code CodegenLauncher.Pick}）
     */
    public void onPicked(long pickId, CodegenEntry entry) {
        Map<String, Object> payload = entry.toMap();
        payload.put("pickId", pickId);
        safeEval("entry => window.__onPicked(entry)", payload);
    }

    /**
     * 推送「该条目已被修订」（ASYNC role 复核失败 ⇒ 策略回退 CSS）：<b>就地更新</b>既有行，不新增行。
     *
     * <p>修订可能来自生成线程（{@code codegen-live-gen}），而 {@link #onPicked} 来自拾取执行器线程；
     * 两者都会 {@code evaluate} 同一个面板页，故所有推送统一经 {@link #PUSH_LOCK} 串行化。
     *
     * @param pickId 目标行 id（{@link #onPicked} 时分配的同一个 id）
     * @param entry  修订后的条目（{@code sigKey} 不变，仅 strategy/selector 变化）
     */
    public void onPickedRevised(long pickId, CodegenEntry entry) {
        onPickedRevised(pickId, entry, null);
    }

    /**
     * 同上，并带上<b>修订原因</b>（如 {@code hits=2, expected exactly 1 hit}）。
     *
     * <p>原因会显示在该行悬停提示上：策略从 role 静默变成 CSS 时，用户必须能一眼看出"为什么变了"，
     * 否则只会以为是面板抽风。
     *
     * @param reason 简短原因；{@code null}/空表示不展示
     */
    public void onPickedRevised(long pickId, CodegenEntry entry, String reason) {
        Map<String, Object> payload = entry.toMap();
        payload.put("pickId", pickId);
        if (reason != null && !reason.isEmpty()) {
            payload.put("revisionReason", reason);
        }
        safeEval("entry => window.__onPickedRevised(entry)", payload);
    }

    /** 通知面板：这些行已被标记为「一组元素」（将生成一个 {@code PageElementList}）。 */
    public void onGrouped(List<Long> pickIds) {
        safeEval("ids => window.__onGrouped(ids)", pickIds);
    }

    /** 推送一条录制动作到面板步骤区。 */
    public void onAction(Map<String, Object> action) {
        safeEval("action => window.__onAction(action)", action);
    }

    /** 推送生成的代码到对应 Tab（tab ∈ {page,step,assert}）。 */
    public void onCode(String tab, String code) {
        safeEval("(p) => window.__onCode(p)", Map.of("tab", tab, "code", code));
    }

    /**
     * 推送按页拆分的代码块到对应 Tab（tab ∈ {page,step,assert}），供面板在 Tab 内做「页内分页面切换器」。
     *
     * <p>每个块是一个 {@code (label, code)} 二元组：label 为页面/步骤/断言类名。
     * 块已折叠成 plain Map，因为 Playwright {@code evaluate} 序列化不支持自定义对象。
     */
    public void onCodeBlocks(String tab, List<com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen.CodeBlock> blocks) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (blocks != null) {
            for (com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen.CodeBlock b : blocks) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", b.label);
                m.put("code", b.code);
                list.add(m);
            }
        }
        safeEval("(p) => window.__onCode(p)", Map.of("tab", tab, "blocks", list));
    }

    /**
     * 面板窗口被用户关闭（OS 标题栏 ×）时回调。
     *
     * <p>面板已去掉 Close 按钮，关窗是唯一的结束方式；调用方据此释放交互会话的等待闩锁，
     * 避免「关掉面板后主线程还阻塞着」。
     */
    public void onClose(Runnable callback) {
        if (callback == null) return;
        panel.onClose(closed -> {
            try {
                callback.run();
            } catch (Exception e) {
                log.warn("[codegen][panel] onClose callback failed: {}", e.getMessage());
            }
        });
    }

    /** 更新面板状态徽标。 */
    public void setStatus(String status) {
        safeEval("s => window.__setStatus(s)", status);
    }

    /**
     * 面板页是否已关闭（用户点标题栏 × / 窗口被 OS 关闭即 true）。
     *
     * <p>用于关闭兜底看门狗：极少数情况下 Playwright 的 {@code page.onClose} 事件未被派发，面板窗口虽已关，
     * 但 {@code closeLatch} 一直不被释放会导致调用方主线程永久阻塞。轮询本方法即可在窗口已关时强制收尾。
     */
    public boolean isClosed() {
        try {
            return panel.isClosed();
        } catch (Exception e) {
            // 读取失败（上下文已销毁）也视为「已关闭」，让看门狗尽快收尾，避免误判为仍存活而继续空等。
            return true;
        }
    }

    /**
     * 统一的「Java → 面板」推送出口。
     *
     * <p><b>必须串行化</b>：推送来自多个线程（拾取执行器、实时生成线程、面板命令调度器），而 Playwright
     * 的 {@code Page} 不是为并发调用设计的——同一页面被多线程同时 {@code evaluate} 可能出现响应错配/阻塞。
     * 加锁成本可忽略（本地 evaluate 毫秒级），换来的是"行、码、状态"三者顺序一致。
     */
    private void safeEval(String expr, Object arg) {
        try {
            synchronized (PUSH_LOCK) {
                panel.evaluate(expr, sanitize(arg));
            }
        } catch (Exception e) {
            // 曾经完全静默（仅 CodegenQuiet），导致「面板没反应」时无从定位；降级为 WARN 但绝不影响主流程。
            log.warn("[codegen][panel] push to panel failed ({}): {}", expr, e.getMessage());
        }
    }

    /**
     * Playwright {@code page.evaluate(expr, arg)} 的参数序列化<b>不支持 {@link Long}</b>（只支持
     * Integer/Double/String/Boolean/Map/List 等）。推送载荷里 {@code pickId}、动作 {@code seq}、
     * 分组 {@code List<Long>} 都是 {@code Long}，会抛 "Unsupported type of argument: N"，表现为
     * "拾取成功但面板不出行/不出码"。此处递归把 {@code Long} 收敛为 {@code Integer}（超范围退 {@code Double}），
     * 并递归处理 Map/Iterable，一处修复覆盖所有推送方法。
     */
    private static Object sanitize(Object v) {
        if (v == null) return null;
        if (v instanceof Long) {
            long l = (Long) v;
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)
                    ? (Object) Integer.valueOf((int) l)
                    : (Object) Double.valueOf((double) l);
        }
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                out.put(String.valueOf(e.getKey()), sanitize(e.getValue()));
            }
            return out;
        }
        if (v instanceof Iterable) {
            List<Object> out = new ArrayList<>();
            for (Object o : (Iterable<?>) v) out.add(sanitize(o));
            return out;
        }
        return v;
    }

    /**
     * 关闭面板窗口（连同其独立浏览器进程与 Playwright driver 子进程）。
     *
     * <p>刻意<b>不删</b>面板 profile 目录：下次启动靠它走暖 profile（~1s 而不是 ~5s）。
     */
    public void close() {
        try {
            panelCtx.close();
        } catch (Exception ignore) {
            CodegenQuiet.ignore("CodegenPanel#close", ignore);
        }
        try {
            // 面板独占一个 Playwright driver（node）进程；不关会残留到 JVM 退出。
            panelPlaywright.close();
        } catch (Exception ignore) {
            CodegenQuiet.ignore("CodegenPanel#close(playwright)", ignore);
        }
        try {
            panelServer.stop(0);
        } catch (Exception ignore) {
            CodegenQuiet.ignore("CodegenPanel#close(server)", ignore);
        }
        try {
            cmdScheduler.shutdownNow();
        } catch (Exception ignore) {
            CodegenQuiet.ignore("CodegenPanel#close(cmdScheduler)", ignore);
        }
    }
}

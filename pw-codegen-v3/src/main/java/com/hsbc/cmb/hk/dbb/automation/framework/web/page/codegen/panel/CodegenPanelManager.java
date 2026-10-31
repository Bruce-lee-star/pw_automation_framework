package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel;

import com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState;
import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.quiet.CodegenQuiet;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 面板常驻服务管理器（B 轨道 2.1：{@code CodegenPanel} 拆两层后的"进程侧"）。
 *
 * <p><b>终态 B</b>（独立 driver 常驻服务模式）：面板浏览器进程（独立 Playwright driver + 持久上下文 +
 * 内嵌 HTTP server + 命令轮询）由本管理器<b>单例持有并跨会话常驻</b>，会话层只拿
 * {@link CodegenPanelHandle}。收益：后续会话不再付 3~4s 的冷启动（实测启动 total 3224~3931ms），
 * 进程只随 idle 超时或 JVM 退出被回收 —— 框架的 {@code cleanupAll / handleBrowserTypeSwitch / 浏览器重启}
 * 四条 driver 关闭路径因此<b>不再波及面板</b>（面板持独立 driver，见方案文档 P0-1 结论）。
 *
 * <p><b>单例串行模型</b>（已拍板：录制无并行场景）：本类不实现窗口池；同一时刻最多一个活跃会话
 * （{@code activeHandle}），open/release 由测试主线程调用，天然串行。并发保护仍按金融级纪律做全：
 * {@code lifecycleLock} 串行化状态迁移，{@code launchGeneration} 使过期 launch 自我作废，
 * 状态机五态（IDLE / STARTING / READY / DEGRADED / SHUTDOWN）与 09.6 的
 * {@code ensureProcess()} 重写版（join 在锁外、join 后回查 state、launch 失败锁内置 DEGRADED）一致。
 */
public final class CodegenPanelManager {

    private static final Logger log = LoggerFactory.getLogger(CodegenPanelManager.class);

    /** 单例（常驻服务全局唯一）。 */
    private static final CodegenPanelManager INSTANCE = new CodegenPanelManager();

    public static CodegenPanelManager instance() {
        return INSTANCE;
    }

    /** 面板进程生命周期状态（09 章五态）。 */
    public enum State {
        /** 无进程，可 launch。 */
        IDLE,
        /** launch 在途（LAUNCH_EXECUTOR 上），启动线程完成时持锁置 READY 或 DEGRADED。 */
        STARTING,
        /** 进程就绪可服务会话。 */
        READY,
        /** launch 失败（或进程异常消亡）——下次 open 自动重建。 */
        DEGRADED,
        /** 已随 JVM 收尾关闭（诊断/防御态）。 */
        SHUTDOWN
    }

    /**
     * 「Java → 面板」推送的串行化锁（跨实例共享，语义同旧 {@code PUSH_LOCK}）。
     *
     * <p>推送来自多个线程（拾取执行器、实时生成线程、命令调度器），而 Playwright {@code Page} 不是为
     * 并发调用设计的；加锁成本可忽略（本地 evaluate 毫秒级），换来行/码/状态顺序一致。
     */
    private static final Object PUSH_LOCK = new Object();

    /** 收尾期推送失败抑制窗口（2.5）：连续失败达到该值后降级为 DEBUG，不再逐条 WARN 刷屏。 */
    private static final int PUSH_FAIL_LIMIT = 5;

    private final Object lifecycleLock = new Object();
    private volatile State state = State.IDLE;
    private volatile CompletableFuture<PanelProcess> launchFuture;
    /** launch 代数：每次发起 launch 递增；在途 launch 完成时对比代数，过期则自我作废（防 watchdog 竞态）。 */
    private volatile long launchGeneration = 0;
    private volatile PanelProcess process;
    /** 当前活跃会话句柄（单例串行：同时至多一个）。 */
    private volatile CodegenPanelHandle activeHandle;
    /** 最后一次会话活跃时间（watchdog idle 判据；{@code System.nanoTime()}）。 */
    private volatile long lastActiveNanos = System.nanoTime();
    private volatile CodegenPanelConfig config = CodegenPanelConfig.fromSystem();
    private final AtomicInteger pushConsecutiveFailures = new AtomicInteger();

    /** launch 专用单线程（daemon）：保证一次只跑一个 launch，且不占用调用方线程。 */
    private static final ExecutorService LAUNCH_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "codegen-panel-launch");
        t.setDaemon(true);
        return t;
    });

    /** watchdog（2.5）：idle 超时回收；0 = 常驻时不启动。惰性创建（首次 open 时）。 */
    private volatile ScheduledExecutorService watchdog;

    static {
        // B 轨道 2.5：接入框架统一关闭编排（参照现有 page-close cleanup step 的注册方式），
        // 不裸加 Runtime hook —— 避免与框架 shutdown 竞态/超时刷屏（日志曾有
        // "Cleanup step 'page-close exceeded 1000ms and was abandoned"）。
        ShutdownCoordinator.register(
                ShutdownCoordinator.ORDER_DIAGNOSTICS, "codegen-panel",
                () -> INSTANCE.shutdownProcess());
    }

    private CodegenPanelManager() {
    }

    // ------------------------------------------------------------------
    // 会话层 API（2.0 / 2.1）
    // ------------------------------------------------------------------

    /**
     * 打开（或复用）面板窗口并绑定一个新会话。
     *
     * <p>进程已 READY 时直接复用（<500ms 级，实测冷启动 3.2~3.9s 不再复付）；未就绪/进程已死时按 09.6
     * {@code ensureProcess()} 重建。返回的句柄承载本会话的命令回调与全部推送方法。
     *
     * @param panelHtml  panel.html 本地路径（见 {@link PanelResource}；固定导出目录，跨会话稳定）
     * @param onCommand  面板按钮/行交互回调（null 表示本会话不接收命令）
     * @param nlsLang    命令线程 NLS 语言（ThreadLocal 传递，null 可）
     */
    public CodegenPanelHandle open(Path panelHtml, Consumer<List<Object>> onCommand, String nlsLang) {
        // 每次会话刷新配置（idle timeout 等 -D 可即时生效）；panelResourceDir 由固定导出的 html 推导。
        CodegenPanelConfig cfg = CodegenPanelConfig.fromSystem();
        Path serveDir = panelHtml == null ? null : panelHtml.getParent().toAbsolutePath().normalize();
        cfg = new CodegenPanelConfig(
                serveDir, cfg.profileDir(), cfg.idleTimeoutMillis(),
                cfg.launchTimeoutMs(), cfg.navigateTimeoutMs(), cfg.readyTimeoutMs(),
                cfg.commandPollIntervalMs(), nlsLang);
        this.config = cfg;
        // 活跃标记提前（watchdog 竞态防护）：ensureProcess 在途期间不回收新进程。
        lastActiveNanos = System.nanoTime();

        PanelProcess p = ensureProcess();
        p.attachCommand(onCommand);
        CodegenPanelHandle handle = new CodegenPanelHandle(this, p.page);
        activeHandle = handle;
        lastActiveNanos = System.nanoTime();
        ensureWatchdog();
        log.info("[codegen][panel] session bound to resident panel process (serveDir={}, profile={})",
                cfg.panelResourceDir(), cfg.profileDir());
        return handle;
    }

    /**
     * 释放会话句柄（2.0 会话结束语义）：清 activeHandle、解绑命令回调，<b>不关浏览器进程</b>。
     *
     * <p>幂等（可重复调用 / 传入 null / 已释放句柄均安全）。进程清理归 {@link #shutdownProcess()}
     * （仅 idle watchdog / ShutdownCoordinator）。
     */
    public void release(CodegenPanelHandle handle) {
        synchronized (lifecycleLock) {
            if (handle != null) {
                handle.markReleased();
                if (activeHandle == handle) {
                    activeHandle = null;
                    if (process != null) {
                        process.attachCommand(null);
                    }
                }
            }
            // 无论句柄是否匹配，都会话结束 → idle 计时重新起算（常驻期间最后一次活跃时间点）。
            lastActiveNanos = System.nanoTime();
        }
        log.info("[codegen][panel] session released; panel process stays resident (state={})", state);
    }

    // ------------------------------------------------------------------
    // ensureProcess（09.6 重写版）
    // ------------------------------------------------------------------

    /**
     * 保证面板进程就绪并返回之。
     *
     * <p>09.6 重写要点（对照旧文档评审缺陷逐条落实）：
     * <ol>
     *   <li><b>join 在锁外</b>：{@code launchFuture.join()} 不得在持 {@code lifecycleLock} 时执行
     *       （启动线程完成时要回持该锁置 READY，锁内 join 会死锁）；</li>
     *   <li><b>join 后回查 state</b>：join 返回后必须回持锁确认 {@code state == READY && process 存活}，
     *       否则（watchdog 抢先回收 / launch 失败）返回已死进程；</li>
     *   <li><b>launch 失败锁内置 DEGRADED</b>：启动线程在 catch 分支持锁置 DEGRADED 并置空 process；
     *       本方法 join 后回查 {@code state != READY} 时抛 {@link IllegalStateException}；</li>
     *   <li><b>generation 计数</b>：watchdog/收尾与 open 并发时，在途 launch 完成后对比代数，
     *       过期者关闭自身并抛 "superseded"，杜绝"回收后又冒出进程"。</li>
     * </ol>
     */
    private PanelProcess ensureProcess() {
        for (;;) {
            PanelProcess p = process;
            State st = state;
            if (st == State.READY && p != null) {
                if (p.isAlive()) {
                    return p;
                }
                // 进程已死（窗口被 OS 关闭等）：标记后走重建。
                synchronized (lifecycleLock) {
                    if (state == State.READY && process == p) {
                        process = null;
                        state = State.DEGRADED;
                        log.info("[codegen][panel] resident panel process is dead — DEGRADED, next open will relaunch");
                    }
                }
                continue;
            }
            if (st == State.STARTING) {
                CompletableFuture<PanelProcess> f = launchFuture;
                if (f != null) {
                    try {
                        f.join();
                    } catch (CompletionException ignore) {
                        // launch 失败已在启动线程置 DEGRADED；回环后走重建或抛。
                    }
                    synchronized (lifecycleLock) {
                        if (state == State.READY && process != null) {
                            if (process.isAlive()) {
                                return process;
                            }
                            process = null;
                            state = State.DEGRADED;
                        }
                    }
                }
                continue;
            }
            // IDLE / DEGRADED：发起一次 launch。
            synchronized (lifecycleLock) {
                if (state == State.STARTING || (state == State.READY && process != null)) {
                    continue;   // 双检：其它线程已推进状态
                }
                state = State.STARTING;
                launchGeneration++;
                long gen = launchGeneration;
                launchFuture = CompletableFuture.supplyAsync(() -> launchTask(gen), LAUNCH_EXECUTOR);
            }
            // join 在锁外；join 后回查。
            CompletableFuture<PanelProcess> f = launchFuture;
            if (f != null) {
                try {
                    f.join();
                } catch (CompletionException ignore) {
                    // 失败路径统一由下方回查处理。
                }
            }
            synchronized (lifecycleLock) {
                if (state == State.READY && process != null) {
                    if (process.isAlive()) {
                        return process;
                    }
                    process = null;
                    state = State.DEGRADED;
                }
                if (state != State.READY) {
                    // 09.6：join 后回查 state != READY → 抛（调用方感知 launch 失败，不无限重试）。
                    throw new IllegalStateException("codegen panel launch did not reach READY (state=" + state + ")");
                }
            }
        }
    }

    /** LAUNCH_EXECUTOR 上的启动任务：launch 成功持锁置 READY；失败持锁置 DEGRADED；过期自我作废。 */
    private PanelProcess launchTask(long gen) {
        try {
            PanelProcess np = PanelProcess.launch(CodegenPanelManager.this, config);
            synchronized (lifecycleLock) {
                if (launchGeneration == gen && state == State.STARTING) {
                    process = np;
                    state = State.READY;   // 启动线程持锁置位（09 章）
                    return np;
                }
            }
            // 过期（watchdog 已回收 / 更新代）：关闭新进程，杜绝泄漏。
            np.closeNow();
            throw new IllegalStateException("panel launch superseded by generation " + gen);
        } catch (Throwable t) {
            synchronized (lifecycleLock) {
                if (launchGeneration == gen && state == State.STARTING) {
                    state = State.DEGRADED;   // 失败锁内置 DEGRADED（09.6）
                    process = null;
                }
            }
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new RuntimeException("panel launch failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 进程回收（2.5）
    // ------------------------------------------------------------------

    /**
     * 关闭常驻面板进程（仅 idle watchdog / ShutdownCoordinator 调用）。
     *
     * <p>置回 IDLE（下次 open 自动重建）、作废旧 launch 代数、解绑会话与命令回调；
     * 进程四段清理（ctx / driver / server / scheduler）在锁外执行，不阻塞其它状态迁移太久。
     */
    public void shutdownProcess() {
        PanelProcess victim;
        synchronized (lifecycleLock) {
            if (state == State.IDLE && process == null) {
                return;   // 已回收 / 从未启动：幂等。
            }
            victim = process;
            process = null;
            launchGeneration++;   // 作废在途 launch
            activeHandle = null;
            state = State.IDLE;
            if (victim != null) {
                victim.attachCommand(null);
            }
        }
        if (victim != null) {
            log.info("[codegen][panel] shutting down resident panel process (idle/JVM exit)");
            victim.closeNow();
        }
    }

    /** watchdog 单拍（每 1s）：READY 且无活跃会话且 idle 超时 → 回收。 */
    private void idleCheck() {
        long idleMillis = config.idleTimeoutMillis();
        if (idleMillis <= 0 || state != State.READY || activeHandle != null) {
            return;
        }
        long elapsedNanos = System.nanoTime() - lastActiveNanos;
        if (elapsedNanos >= TimeUnit.MILLISECONDS.toNanos(idleMillis)) {
            log.info("[codegen][panel] no active session for {}ms — idle timeout reached, reclaiming panel process",
                    idleMillis);
            shutdownProcess();
        }
    }

    private void ensureWatchdog() {
        long idleMillis = config.idleTimeoutMillis();
        if (idleMillis <= 0) {
            return;   // 0 = 常驻：watchdog 不启动。
        }
        ScheduledExecutorService wd = watchdog;
        if (wd == null) {
            synchronized (this) {
                if (watchdog == null) {
                    wd = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "codegen-panel-watchdog");
                        t.setDaemon(true);
                        return t;
                    });
                    watchdog = wd;
                    wd.scheduleWithFixedDelay(this::idleCheck, 1_000, 1_000, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 窗口关闭派发（进程级注册一次，会话级回调）
    // ------------------------------------------------------------------

    /** 面板窗口被 OS 关闭（进程终止）时的统一派发：执行活跃会话的 close 回调并置 DEGRADED。 */
    void onPageClosed(PanelProcess p) {
        CodegenPanelHandle h = activeHandle;
        Runnable cb = null;
        if (h != null) {
            h.markReleased();
            cb = h.closeCallback();
            if (activeHandle == h) {
                activeHandle = null;
            }
        }
        synchronized (lifecycleLock) {
            if (process == p) {
                process = null;
                state = State.DEGRADED;
            }
            p.attachCommand(null);
        }
        // v2.18 修复（手动关窗泄漏）：窗口被 OS 关掉 ⇒ 浏览器进程已死，但该 PanelProcess 的
        // pw(独占 Playwright driver/node)、server(非 daemon HttpServer 派发线程)、cmdScheduler
        // 仍存活，若无人回收会泄漏 driver 进程且 JVM 无法干净退出。shutdownProcess() 在
        // process 已为 null 时是 no-op，此处是唯一回收点。offload 到 LAUNCH_EXECUTOR，
        // 避免在本 Playwright 事件派发线程上同步发 close 协议引发潜在死锁（closeNow 各段已吞异常）。
        log.info("[codegen][panel] panel window closed by OS — reclaiming driver/server resources");
        LAUNCH_EXECUTOR.submit(p::closeNow);
        if (cb != null) {
            try {
                cb.run();   // 回调内（CodegenLauncher）负责释放闩锁与收尾清理
            } catch (Exception e) {
                log.warn("[codegen][panel] panel close callback failed: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 推送出口（含收尾抑制 2.5）
    // ------------------------------------------------------------------

    /**
     * 统一的「Java → 面板」推送出口：串行化（PUSH_LOCK）+ 参数 Long 收敛（sanitize）+ 收尾抑制。
     *
     * <p>收尾期（会话结束、浏览器正在关）会出现连续 {@code push failed ... Playwright connection closed}；
     * 连续失败 ≥ {@value #PUSH_FAIL_LIMIT} 后降级为 DEBUG（抑制窗口），不再逐条 WARN 刷屏 —— 这是
     * 既定行为（收尾期预期失败），<b>不</b>作为 DEGRADED 判据（避免每次收尾都误报并触发重建）。
     */
    void pushSafely(CodegenPanelHandle h, String expr, Object arg) {
        if (h.isReleased()) {
            return;   // 会话已结束：不再推送。
        }
        try {
            synchronized (PUSH_LOCK) {
                h.page().evaluate(expr, sanitize(arg));
            }
            pushConsecutiveFailures.set(0);
        } catch (Exception e) {
            int n = pushConsecutiveFailures.incrementAndGet();
            if (n >= PUSH_FAIL_LIMIT) {
                log.debug("[codegen][panel] push suppressed after {} consecutive failures (session ending): {}", n, e.getMessage());
            } else {
                log.warn("[codegen][panel] push to panel failed ({}): {}", expr, e.getMessage());
            }
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

    // ------------------------------------------------------------------
    // PanelProcess：常驻面板进程（pw 独立 driver / ctx / server / scheduler / page）
    // ------------------------------------------------------------------

    /** 面板进程（2.1 拆层后的"进程侧"）：独占一个 Playwright driver + 持久上下文 + HTTP server + 命令轮询。 */
    static final class PanelProcess {

        private final CodegenPanelManager owner;
        final Playwright pw;
        final BrowserContext ctx;
        final Page page;
        final HttpServer server;
        final Path serveDir;
        final ScheduledExecutorService cmdScheduler;
        private volatile Consumer<List<Object>> activeCommand;

        private PanelProcess(CodegenPanelManager owner, Playwright pw, BrowserContext ctx, Page page,
                             HttpServer server, Path serveDir, ScheduledExecutorService cmdScheduler) {
            this.owner = owner;
            this.pw = pw;
            this.ctx = ctx;
            this.page = page;
            this.server = server;
            this.serveDir = serveDir;
            this.cmdScheduler = cmdScheduler;
        }

        /** 绑定/解绑当前会话的命令回调（null = 解绑；轮询拍读到 null 时丢弃命令）。 */
        void attachCommand(Consumer<List<Object>> command) {
            this.activeCommand = command;
        }

        /** 进程是否存活（面板页未关闭）；读取失败视为已死。 */
        boolean isAlive() {
            try {
                return !page.isClosed();
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * 启动面板进程（迁移自旧 {@code CodegenPanel#open} 的完整启动时序，含 startup timings 日志
         * —— 全链路回归第 1 项「启动耗时」的观测依据）。
         */
        static PanelProcess launch(CodegenPanelManager owner, CodegenPanelConfig cfg) {
            long tStart = System.currentTimeMillis();
            Path serveDir = cfg.panelResourceDir();
            if (serveDir == null) {
                throw new IllegalStateException("panel resource dir is not set (PanelResource must resolve panel.html first)");
            }
            HttpServer server = startPanelServer(serveDir);
            String panelUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/panel.html";
            long tServer = System.currentTimeMillis();
            // 命令轮询调度器（单线程）：命令处理必须脱离 Playwright 连接（消息派发）线程——若在派发线程上
            // 同步调用 panel.evaluate / cdp.send 并阻塞等响应，会收不到响应而死锁（表现即「点 Start 面板冻结」）。
            // 用固定延迟调度而非自旋 + Thread.sleep：不忙等、可精确取消；单线程天然串行，
            // 保证 start/stop/group 的先后顺序。NLS 语言是 ThreadLocal，命令线程必须带语言（null 可）。
            ScheduledExecutorService cmdScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(() -> {
                    if (cfg.nlsLang() != null) LanguageState.setLanguage(cfg.nlsLang());
                    r.run();
                }, "codegen-panel-cmd");
                t.setDaemon(true);
                return t;
            });

            Playwright pw = createPanelPlaywright(cfg);
            long tPlaywright = System.currentTimeMillis();
            BrowserContext ctx = launchPanelContext(pw, panelUrl, cfg);
            long tLaunch = System.currentTimeMillis();
            // --app= 通常会自动打开该 URL 的首页；若只建了空白页（或未建页）则自己建/导航。
            // 必须按 URL 认页：直接把 pages().get(0) 当面板页，在某些 Chromium 版本上会拿到 about:blank，
            // 导致面板看得见、按钮全无反应（命令桥落到了空白页）。
            Page p = resolvePanelPage(ctx, panelUrl, cfg);
            long tPage = System.currentTimeMillis();
            PanelProcess np = new PanelProcess(owner, pw, ctx, p, server, serveDir, cmdScheduler);
            // 命令桥：面板 JS 自己定义 window.__codegenCmd，把命令推入 window.__codegenOutbox 队列；
            // 这里按固定延迟取走并分发。不再用 exposeBinding —— 它在 --app= 持久上下文窗口里注入不稳定
            // （命令偶尔根本到不了 Java，表现即「点 Start 毫无反应」徽标停在 ready）。
            // 会话解绑（activeCommand=null）后轮询继续但不动作：进程常驻，下一会话重新 attach。
            cmdScheduler.scheduleWithFixedDelay(
                    np::pollCommandsOnce, 0, cfg.commandPollIntervalMs(), TimeUnit.MILLISECONDS);
            // 就绪判据是「命令桥可用」（panel.js 顶部定义的 window.__codegenOutbox），而不是 load/DOMContentLoaded
            // 事件：Monaco 等增强资源从 CDN 注入、公司网不可达时 load 事件会被拖很久，但拾取/录制根本不需要它。
            waitPanelInteractive(p, cfg);
            long tReady = System.currentTimeMillis();
            // 窗口关闭监听：进程级注册一次（会话级回调经 Manager.onPageClosed 派发，避免监听器累积）。
            p.onClose(closed -> np.ownerCallback().onPageClosed(np));
            log.info("[codegen][panel] startup timings(ms): server={} playwright={} launch={} page={} ready={} total={} (profile={})",
                    tServer - tStart, tPlaywright - tServer, tLaunch - tPlaywright,
                    tPage - tLaunch, tReady - tPage, tReady - tStart, cfg.profileDir());
            log.info("[codegen][panel] panel window opened (Chrome --app=, served via http://127.0.0.1:{}): {} -> {}",
                    server.getAddress().getPort(), serveDir.resolve("panel.html"), p.url());
            return np;
        }

        private CodegenPanelManager ownerCallback() {
            return owner;
        }

        /**
         * 取走面板 outbox 中的命令并串行分发（调度器每 {@code commandPollIntervalMs}ms 调一次）。
         *
         * <p>异常必须被吞掉：{@code scheduleWithFixedDelay} 的任务一旦抛出，后续调度会被静默取消，
         * 命令桥就永久失效了（面板页未就绪、导航中、已关闭都会命中）。会话已解绑（activeCommand==null）
         * 时直接跳过 —— 常驻期间面板按钮不再有响应（显示「会话已结束」），符合 2.0 语义。
         */
        private void pollCommandsOnce() {
            Consumer<List<Object>> command = activeCommand;
            if (command == null) {
                return;
            }
            try {
                Object out = page.evaluate("() => (window.__codegenOutbox || []).splice(0)");
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
                        command.accept(cmd);
                    } catch (Exception e) {
                        log.warn("[codegen][panel] command handling failed: {}", e.getMessage());
                    }
                }
            } catch (Throwable ignore) {
                // 面板页尚未就绪或已关闭：本拍跳过，下一拍继续。
            }
        }

        /**
         * 关闭面板进程：四段清理（context → driver → server → scheduler），异常各自吞掉。
         *
         * <p>刻意<b>不删</b>面板 profile 目录：下次启动靠它走暖 profile（~1s 而不是 ~5s）。
         */
        void closeNow() {
            try {
                ctx.close();
            } catch (Exception ignore) {
                CodegenQuiet.ignore("CodegenPanelManager#close(ctx)", ignore);
            }
            try {
                // 面板独占一个 Playwright driver（node）进程；不关会残留到 JVM 退出。
                pw.close();
            } catch (Exception ignore) {
                CodegenQuiet.ignore("CodegenPanelManager#close(playwright)", ignore);
            }
            try {
                server.stop(0);
            } catch (Exception ignore) {
                CodegenQuiet.ignore("CodegenPanelManager#close(server)", ignore);
            }
            try {
                cmdScheduler.shutdownNow();
            } catch (Exception ignore) {
                CodegenQuiet.ignore("CodegenPanelManager#close(cmdScheduler)", ignore);
            }
        }
    }

    // ------------------------------------------------------------------
    // 进程启动静态工具（迁移自旧 CodegenPanel，语义/注释保留）
    // ------------------------------------------------------------------

    /**
     * 面板专用 {@link Playwright} 实例。
     *
     * <p><b>必须显式关掉 driver 的浏览器安装/校验步骤</b>（{@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1}）：
     * 该步骤在每次 {@code Playwright.create()} 时都会校验本地浏览器版本，公司网下会走网络。本机实测
     * {@code create()} 由 <b>7730ms</b> 降到 <b>881ms</b>（网络更差时是数十秒）。面板只用本机已装好的
     * Chromium，无需这一步；浏览器目录沿用进程环境变量 {@code PLAYWRIGHT_BROWSERS_PATH}，与框架主浏览器
     * 共享同一份缓存，避免重复下载 —— 浏览器二进制下载/校验归框架（终态 B 约束，codegen 零下载逻辑）。
     */
    private static Playwright createPanelPlaywright(CodegenPanelConfig cfg) {
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
    private static BrowserContext launchPanelContext(Playwright pw, String panelUrl, CodegenPanelConfig cfg) {
        List<String> args = panelLaunchArgs(panelUrl);
        Path profile = cfg.profileDir();
        try {
            try {
                Files.createDirectories(profile);
            } catch (IOException io) {
                throw new RuntimeException("cannot create panel profile dir", io);
            }
            return launchPersistent(pw, profile, args, cfg);
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
            return launchPersistent(pw, fresh, args, cfg);
        }
    }

    private static BrowserContext launchPersistent(Playwright pw, Path profile, List<String> args, CodegenPanelConfig cfg) {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(false)
                .setTimeout(cfg.launchTimeoutMs())
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
     * 选出承载面板的页面：优先按 URL 前缀匹配（{@code --app=} 已打开面板）；匹配不到则复用现有页并导航。
     *
     * <p>暖 profile 可能恢复出上一次的旧页面（旧端口），故只保留一个承载页、其余关闭，避免窗口堆积与
     * "命令桥挂在旧页上"的假死。
     */
    private static Page resolvePanelPage(BrowserContext ctx, String panelUrl, CodegenPanelConfig cfg) {
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
            target.navigate(panelUrl, new Page.NavigateOptions().setTimeout(cfg.navigateTimeoutMs()));
        }
        return target;
    }

    /** 等面板命令桥就绪（{@code window.__codegenOutbox} 由 panel.js 顶部同步定义）；超时不阻断。 */
    private static void waitPanelInteractive(Page p, CodegenPanelConfig cfg) {
        try {
            p.waitForFunction("() => typeof window.__codegenOutbox !== 'undefined'", null,
                    new Page.WaitForFunctionOptions().setTimeout(cfg.readyTimeoutMs()));
        } catch (RuntimeException e) {
            log.warn("[codegen][panel] panel not interactive within {}ms (continuing): {}",
                    (long) cfg.readyTimeoutMs(), e.getMessage());
        }
    }

    /**
     * 在 127.0.0.1 随机端口起一个仅本机可达的静态文件服务，托管面板资源（panel.html/js/css）。
     * 用 http 同源取代 file:// 打开面板，规避 Chrome 对 file:// 独立安全源的限制。
     *
     * <p>2.6：serveDir 来自 {@link PanelResource} 的<b>固定导出目录</b>（{@code %TEMP%\codegen-panel-<hash>}），
     * 随面板进程生命周期恒定 —— 常驻进程跨会话服务同一目录，不再有"第 2 会话 404 / NLS 陈旧"（P0-3）。
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
                        CodegenQuiet.ignore("CodegenPanelManager#serve", ignore);
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
}

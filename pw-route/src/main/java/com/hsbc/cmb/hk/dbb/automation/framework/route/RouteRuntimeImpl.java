package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.DriverFailureSignal;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GenerationRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.PatternBinder;
import com.hsbc.cmb.hk.dbb.automation.framework.route.diag.HangWatchdog;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RouteBinderRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RuleGeneration;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.ClaimRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.claim.PendingGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch.RouteDispatcher;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.GuardedRunner;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteDelayScheduler;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.GuardedRunner;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteRetireExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CaptureSink;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedExchange;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorSink;
import com.hsbc.cmb.hk.dbb.automation.framework.route.reporting.RouteMonitorFailureReportSink;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Route 运行时（每 BrowserContext 一份）——{@link RouteRuntime} 角色的生产实现。
 *
 * <p>并发安全设计（为什么每个 context 一份、为什么不再有全局 static 可变表）：见 {@link RouteRuntime} 契约。
 *
 * <p>创建收口：实例仅经 {@link RouteRuntimeFactory}（默认 {@link DefaultRouteRuntimeFactory}）
 * 创建，{@link RouteEngine} 不再直接 new；可经 SPI/factory 替换为测试替身（真多态）。
 *
 * <p>线程清单：事件线程（Playwright 回调，route 处理器在此就地执行 fetch/fulfill）、巡检线程（daemon）。
 */
public final class RouteRuntimeImpl implements RouteRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteRuntimeImpl.class);

    private final BrowserContext context;
    private final RouteConfig config;
    private final GenerationRegistry generations = new GenerationRegistry();
    private final PendingGuard pendingGuard;
    private final ClaimRegistry claims;
    /** F1（2026-10-10）：失败信号下的挂起收敛已触发标记（CAS 幂等 —— 只收敛一次）。 */
    private final AtomicBoolean failFastAborted = new AtomicBoolean(false);
    private final GuardedRunner guarded;
    /** 独立撤销执行器（S2，R-4）：与 fetch 调用线程隔离，避免撤销等待占住线程、挤压 body 断言/采集。 */
    private final RouteRetireExecutor retire;
    private final MonitorSink monitor;
    private final CaptureSink capture;
    /** 采集策略：构造期解析并冻结一次，供两个采集闸门共用（杜绝口径漂移）。 */
    private final RouteCapturePolicy capturePolicy;
    private final RouteDispatcher dispatcher = new RouteDispatcher();
    private final ConcurrentMap<String, PatternBinder> binders = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** runtime 是否已降级（注册曾失败）；context 复用方据此丢弃该 Context。 */
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    /**
     * 本次关闭是否由 {@code context.onClose} 触发（context 正在销毁）。
     * 该路径只做内存收尾，绝不发起协议调用 —— 见 {@link #closeAfterContextClosed()}。
     */
    private volatile boolean contextClosing;
    /** T2+：按"目的达成"撤销的规则数（观测指标）。 */
    private final AtomicInteger retiredByPurpose = new AtomicInteger(0);
    /** T2+：未被确证的撤销次数（>0 ⇒ 本用例路由状态"不可确证干净"，见不变式 I-8 / I-9）。 */
    private final AtomicInteger unconfirmedRetirements = new AtomicInteger(0);
    /** T1：每条规则 armed 起点（纳秒，单调时钟），用于计算 armed 时长。 */
    private final ConcurrentMap<String, Long> armedAtNs = new ConcurrentHashMap<>();
    /** T1：每条规则 armed 时长（毫秒），撤销/收尾时定稿（per-rule）。 */
    private final ConcurrentMap<String, Long> armedDurationsMs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    private final HangWatchdog hangWatchdog;
    private final ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry;
    private final AtomicInteger contextSeq = new AtomicInteger(0);
    /**
     * 方案 B（2026-10-11）：DELAY 到点后的协议动作队列。
     *
     * <p>生产者：{@code RouteDelayScheduler} 线程池（delay 到点回调只入队、零协议）；
     * 消费者：main 业务线程（{@link #drainMainThreadActions()}，在断言/采集查询/收尾 seam 调用）。
     * 入队任务都是 <b>协议调用</b>（resume/fulfillWithBody 等 sendMessageAsync）——Connection 的
     * {@code ++lastId}/{@code callbacks.put}（HashMap）/{@code transport.send} 全部无锁，任何非 main
     * 线程发消息都与 main 并发写 ⇒ 对象表级联损坏（E2E run5 实证）。收敛后协议调用 100% 在 main。</p>
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> mainActions =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private RouteRuntimeImpl(BrowserContext context, RouteConfig config,
                             ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry) {
        this.context = context;
        this.config = config;
        this.ownerRegistry = ownerRegistry;
        this.pendingGuard = new PendingGuard(config.maxPending());
        this.claims = new ClaimRegistry(pendingGuard, Duration.ofSeconds(config.ioAwaitTimeoutSeconds()));
        this.guarded = new GuardedRunner(config.guardedRunnerMaxConcurrent(), Duration.ofMillis(500));
        String tag = String.valueOf(contextSeq.incrementAndGet());
        this.retire = new RouteRetireExecutor(tag);
        // 采集配置在本刻唯一解析并冻结（RouteCapturePolicy）：两个采集闸门 + monitor 断言体上限共用同一快照
        this.capturePolicy = RouteCapturePolicy.resolveNow();
        // monitor 观测队列上限回落 MonitorSink 默认（1000）；断言体上限来自冻结策略（超限降级 status/headers）
        this.monitor = new MonitorSink(this::retireByPurpose, 0, this.capturePolicy.assertBodyMaxBytes());
        this.capture = new CaptureSink(config.maxCaptured(), config.captureTimeoutMs(), this.capturePolicy);
        this.sweeper = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "route-sweep-" + tag);
                t.setDaemon(true);
                return t;
            }
        });
        this.sweeper.scheduleWithFixedDelay(this::sweep, config.sweepIntervalMs(), config.sweepIntervalMs(),
                TimeUnit.MILLISECONDS);
        // context 关闭 → 运行时自动关闭（幂等）
        context.onClose(ignored -> closeAfterContextClosed());
        // 刻意【不订阅】context.onResponse：该订阅是驱动侧 "Object doesn't exist: response@…" 竞态的
        // **主要**触发源（2026-10-09 修正：早期注释写"唯一"，实测仍有该异常 ⇒ 另有订阅方或其它来源，
        // 需在运行期锁定；本框架自身不注册该订阅这一点仍然成立）——Playwright 在 BrowserContextImpl
        // .handleEvent 的 "response" 分支会为每个事件句柄调用 Connection.getExistingObject(guid)
        // （**先于**任何监听器判定），句柄一旦被服务端回收即抛异常；异常发生在业务 lambda
        // 之前无法被拦截，且因 Connection 为共享单连接，会以「此刻在等结果的任意调用线程」为宿主抛出
        // （实测：会话校验导航被炸 → 误判会话失效 → 删缓存 → 每轮全量重登）。
        // 响应侧观测改走 route 通道：recordObservation 持有 Request，轮询 existingResponse()（本地字段）。
        VerboseLogging.logInfoIfVerbose(LOGGER, "[Route] runtime installed for context @{} (maxPending={})",
                System.identityHashCode(context), config.maxPending());
        // 临时诊断看门狗（与 web HangWatchdog 同源，待去除）：按本 runtime 生命周期武装。
        // P1-5：改为 opt-in —— 默认不武装（不常驻诊断 daemon、不污染生产日志），
        // 仅当 -Droute.hang.watchdog.enabled=true 时才武装（排查卡死时显式开启）。
        this.hangWatchdog = new HangWatchdog("ctx@" + System.identityHashCode(context));
        if (Boolean.getBoolean("route.hang.watchdog.enabled")) {
            this.hangWatchdog.arm();
            VerboseLogging.logInfoIfVerbose(LOGGER,
                    "[Route] HangWatchdog armed (diag, opt-in) for context @{}",
                    System.identityHashCode(context));
        } else {
            VerboseLogging.logInfoIfVerbose(LOGGER,
                    "[Route] HangWatchdog not armed (opt-in disabled) for context @{}; "
                            + "set -Droute.hang.watchdog.enabled=true to enable",
                    System.identityHashCode(context));
        }
    }

    /** 响应轮询<b>起始</b>间隔（毫秒）：细粒度使已到达的响应近乎即时定案，且不空转。 */
    private static final long RESPONSE_POLL_INITIAL_MS = 25L;

    /**
     * 响应轮询间隔<b>上限</b>（毫秒）：每轮翻倍退避封顶于此。
     *
     * <p>为什么要退避：固定 25ms 时，一个 30s 观测窗口 = 每请求 <b>1200 个调度任务</b>，
     * 而这些任务与 DELAY 到点回调共用同一个（线程数有界的）{@link RouteDelayScheduler} 池 ——
     * 并发观测多时任务量线性膨胀。改成 25→50→100→100… 后，任务量降到约 1/5，
     * 而代价只是"响应到达后再晚至多 100ms 定案"（相对 30s 窗口可忽略）。</p>
     */
    private static final long RESPONSE_POLL_MAX_MS = 100L;

    /**
     * 响应侧观测轮询（route 通道）——取代原 {@code context.onResponse} 事件订阅。
     *
     * <p><b>为什么能取代</b>：{@link Request#existingResponse()} 是请求本地持有的字段
     * （playwright-java 1.59+），零协议往返、不触碰 Connection 对象表；route 分发入口本就持有
     * {@link Request}，因此无需任何事件订阅即可完成请求-响应配对（配对键为 dispatch 时的
     * {@link ApiSpec} 实例，比原先按 URL 匹配更精确）。
     *
     * <p><b>线程模型</b>：经 {@link RouteDelayScheduler}（模块自有 daemon 调度池）递归调度，
     * 不占用调用线程、不使用 {@code Thread.sleep}（框架 ArchUnit 明令禁止）；命中即投递 sink
     * （sink 在<b>本线程就地</b>完成 {@code status}/{@code headers}/{@code body} 快照 ——
     * {@code body()} 是协议调用，必须趁句柄存活时同步读（2026-09-28 修正：旧实现交独立 IO 线程
     * 排队执行，实测 IO 池繁忙时句柄被回收、body 恒 null；现行实现改为在调用线程就地读取，
     * 零句柄依赖，见 {@code MonitorSink}/{@code CaptureSink}）。窗口内未命中则静默结束
     * ——由 sink 的 drain 超时定案路径兜底（与事件驱动时代语义一致）。轮询间隔按
     * {@link #RESPONSE_POLL_MAX_MS} 自适应退避（见该常量的说明），既保证及时定案，
     * 又不让观测任务淹没延迟调度池。</p>
     *
     * @param intervalMs 本轮到下一轮的间隔（每轮翻倍，封顶 {@link #RESPONSE_POLL_MAX_MS}）
     */
    private void scheduleResponsePoll(Request request, ApiSpec spec,
                                      boolean monitorPending, boolean capturePending, long deadlineNs,
                                      long intervalMs) {
        if (closed.get() || DriverFailureSignal.reported()) {
            return; // runtime 已关闭 / F4：驱动连接已不可信 → 停止轮询（不再泵连接）
        }
        Response response = safeExistingResponse(request);
        if (response != null) {
            deliverResponse(spec, request, response, monitorPending, capturePending);
            return;
        }
        if (System.nanoTime() >= deadlineNs) {
            return; // 窗口内未到达：交由 sink 的超时定案
        }
        long next = Math.min(RESPONSE_POLL_MAX_MS, intervalMs * 2);
        // 2026-10-15：响应轮询收口 —— 调度器仅作「节拍器」（回调只 enqueue，不碰连接对象），
        // existingResponse/deliverResponse 由业务线程 main drain 执行，与 q-0 DELAY 交付同构；
        // 彻底消除调度线程触碰连接对象的窗口。保留 intervalMs 节拍，避免纯 enqueue 在单次 drain 内忙等。
        RouteDelayScheduler.delay(intervalMs,
                () -> enqueueMainAction(() -> scheduleResponsePoll(request, spec, monitorPending,
                        capturePending, deadlineNs, next)));
    }

    /** {@code existingResponse()} 为本地字段读取；防御性兜底，绝不因取响应失败而中断观测链。 */
    private static Response safeExistingResponse(Request request) {
        try {
            return request == null ? null : request.existingResponse();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 spec 投递响应；单条管道异常不得影响另一条，更不得逃逸到调度线程。request 透传以支持按请求身份精确配对。 */
    private void deliverResponse(ApiSpec spec, Request request, Response response,
                                 boolean monitorPending, boolean capturePending) {
        if (monitorPending) {
            try {
                monitor.onResponseForSpec(spec, request, response);
            } catch (Throwable t) {
                LOGGER.warn("[Route] monitor onResponse failed for pattern='{}': {}", spec.pattern(), t.toString());
            }
        }
        if (capturePending) {
            try {
                capture.onResponseForSpec(spec, request, response);
            } catch (Throwable t) {
                LOGGER.warn("[Route] capture onResponse failed for pattern='{}': {}", spec.pattern(), t.toString());
            }
        }
    }

    /** 安装运行时（静态工厂，由 RouteRuntimeFactory / DefaultRouteRuntimeFactory 调用）。 */
    static RouteRuntime install(BrowserContext context, RouteConfig config,
                                ConcurrentMap<BrowserContext, RouteRuntime> ownerRegistry) {
        return new RouteRuntimeImpl(context, config, ownerRegistry);
    }

    // ── 访问器（供 RouteDispatcher / PatternBinder 使用；模块内部契约）──

    /** 规则代只读快照（而非注册表本体）—— 见接口注释：暴露本体等于交出可变内部状态，且会被绕过 CAS/令牌。 */
    @Override
    public RuleGeneration ruleSnapshot() {
        return generations.snapshot();
    }

    @Override
    public ClaimRegistry claims() {
        return claims;
    }

    @Override
    public PendingGuard pending() {
        return pendingGuard;
    }

    @Override
    public GuardedRunner guarded() {
        return guarded;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isDegraded() {
        return degraded.get();
    }

    /**
     * 标记路由设施降级（注册未确证）。
     *
     * <p><b>语义边界（2026-10-15 修订）</b>：这是<b>可观测信号</b>（报告/诊断用），
     * <b>不表示要重建 Context</b> —— route 层永不动 Context（2026-09-30 收口后 Context 每 case
     * 由生命周期层重建，不变式 I-1）。teardown 已不再逐条 unroute（2026-10-15），故不存在
     * "撤销未确证 ⇒ 状态分叉" 的自愈需求；本标记现在只反映<b>注册期</b>未确证（MONITOR 降级绑定），
     * 注册期 {@code route()} 本就下发本地 Router 全量快照，单连接 FIFO 有序，状态一致性由全量快照 +
     * Context 每 case 重建保证。</p>
     */
    void markDegraded() {
        if (degraded.compareAndSet(false, true)) {
            LOGGER.warn("[Route] route facility degraded for context @{} (registration/unroute not confirmed) — "
                    + "observability only, context is NOT rebuilt (I-1)", System.identityHashCode(context));
        }
    }

    @Override
    public APIRequestContext request() {
        return context.request();
    }

    @Override
    public void recordObservation(Request request, ApiSpec spec) {
        if (DriverFailureSignal.reported()) {
            // F1/F4：驱动连接已不可信 → 不再启动响应侧观测轮询
            //（避免调度线程在失败后继续泵连接，与主线程并发驱动同一条连接）。
            return;
        }
        // MONITOR 能力位已停止（stop* / 目的退役）⇒ 不再为其做响应侧观测轮询；
        // capture 维度不受 MONITOR 停止影响，照常采集。
        boolean monitorPending = spec.isStopped(RouteCapability.MONITOR) ? false : monitor.recordRequest(request, spec);
        boolean capturePending = capture.recordRequest(request, spec);
        if (!monitorPending && !capturePending) {
            return; // 本规则不需要响应（无响应侧断言 / 未开 capture）→ 不启动任何轮询
        }
        // 等待窗口：monitor 规则用其 timeout（0=永不超时 → 退化为采集超时窗口，避免无限轮询）；
        // 纯 capture 规则用采集超时窗口。窗口外未到达由 sink 的 drain 超时定案路径兜底。
        long capMs = spec.monitorTimeoutMs() > 0 ? spec.monitorTimeoutMs() : config.captureTimeoutMs();
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(capMs);
        scheduleResponsePoll(request, spec, monitorPending, capturePending, deadlineNs, RESPONSE_POLL_INITIAL_MS);
    }

    @Override
    public List<CapturedApiCall> dumpCapturedApis() {
        drainMainThreadActions(); // 方案 B：业务 seam 先执行延迟协议动作（DELAY resume/fulfill）
        return capture.dump();
    }

    @Override
    public List<CapturedApiCall> peekCapturedApis() {
        drainMainThreadActions(); // 方案 B：业务 seam
        return capture.peek();
    }

    // ---------- 采集数据索引查询与清理：一律委托 CaptureSink（非消费式读取 + 收尾清理） ----------

    @Override
    public void resetCapturedApis() {
        capture.reset();
    }

    @Override
    public List<CapturedApiCall> allCapturedApis() {
        drainMainThreadActions(); // 方案 B：业务 seam
        return capture.all();
    }

    @Override
    public List<CapturedApiCall> lastCapturedPerPattern() {
        return capture.allLastPerPattern();
    }

    @Override
    public CapturedApiCall lastCapturedApi(String endpoint) {
        return capture.lastOf(endpoint);
    }

    @Override
    public List<CapturedApiCall> capturedApisByUrl(String url) {
        return capture.byUrl(url);
    }

    @Override
    public List<CapturedApiCall> capturedApisByChannel(String channel) {
        return capture.byChannel(channel);
    }

    @Override
    public long capturedApiSequence() {
        return capture.captureSequence();
    }

    @Override
    public CapturedApiCall waitForCapturedApi(java.util.function.Predicate<CapturedApiCall> predicate,
                                              long timeoutMs, long minCaptureSeq) {
        //  方案 B（2026-10-11）：等待期间<b>周期</b>执行 main 队列（DELAY resume）——只开头 drain 一次不够：
        //  delay 到点可能在等待途中发生，resume 入队后若无人执行 ⇒ 请求继续挂起、响应永不到达 ⇒ waitFor 超时。
        //  短窗口（≤50ms）事件驱动等待 + 每窗口前 drain，delay 到点至多滞后一个窗口即被 main 执行。
        long deadlineNs = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            drainMainThreadActions();
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
            if (remainingMs <= 0L) {
                return null;
            }
            CapturedApiCall hit = capture.waitFor(predicate, Math.min(50L, remainingMs), minCaptureSeq);
            if (hit != null) {
                return hit;
            }
        }
    }

    @Override
    public void enqueueMainAction(Runnable action) {
        if (action == null || closed.get()) {
            return; // 已关闭：丢弃（收尾路径不泵连接，见 closeAfterContextClosed 契约）
        }
        mainActions.add(action);
    }

    @Override
    public void drainMainThreadActions() {
        // main 业务线程串行执行：协议调用（resume/fulfillWithBody）在 main 发生 ⇒ 零并发写 Connection。
        // 异常绝不逃逸（单条失败不影响其余；fail-open）。
        Runnable action;
        while ((action = mainActions.poll()) != null) {
            try {
                action.run();
            } catch (Throwable t) {
                LOGGER.warn("[Route] main-thread action failed (fail-open): {}", t.toString());
            }
        }
        // 2026-10-16 到达即读：本批 main 动作（含响应轮询 deliverResponse 持句柄）跑完后，就地物化
        // 已到达 exchange 的 body（业务线程、协议调用安全）——不再延迟到下一次采集查询/断言结算。
        // 这让 drainSettledAssertionFailures/register/close 等非查询型 drain 检出的到达也同批读 body，
        // 压缩"检出到达→读取"窗口（对齐文档"立即读、自己存"）。两者内部均 fail-open，绝不抛异常。
        capture.materializeArrivedBodies();
        monitor.materializeArrivedBodies();
    }

    @Override
    public List<RouteAssertionFailure> drainSettledAssertionFailures() {
        drainMainThreadActions(); // 方案 B：业务 seam 先执行延迟协议动作（DELAY resume/fulfill）
        List<RouteAssertionFailure> result = new ArrayList<>();
        for (CapturedExchange e : monitor.drainSettledFailures()) {
            result.add(new RouteAssertionFailure(
                    e.pattern(), e.method(), e.url(), e.expectStatus(), e.responseStatus(),
                    e.bodyAssertionFailures(), e.responseTimedOut(), e.monitorTimeoutMs(),
                    e.responseBodyPreview() == null ? "" : e.responseBodyPreview(),
                    e.postDataPreview() == null ? "" : e.postDataPreview(),
                    formatHeaders(e.requestHeaders()),
                    formatHeaders(e.responseHeaders())));
        }
        //  2026-10-10：失败数据一份两用 —— 消费方（web 侧抛错/glue 断言/聚合器）拿到原列表之外，
        //  顺带复制进 monitor 失败报告归集器（喂入即落盘 target/monitor-failures-by-owner.json +
        //  HTML 报告显示区）。挂在本【唯一 drain 出口】：任何消费路径（RouteEngine 聚合、
        //  glue 直调 runtime）最终都走本方法，天然无重复、无遗漏。不做二次消费：原列表原样返回。
        if (!result.isEmpty()) {
            try {
                RouteMonitorFailureReportSink.recordFailures(result);
            } catch (Exception e) {
                LOGGER.debug("[Route] failed to record assertion failures into monitor report sink: {}",
                        e.toString());
            }
        }
        return result;
    }

    /** Map<String,String> headers → "key: value\n" 多行文本（null/空 → 空串）。 */
    private static String formatHeaders(java.util.Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        for (java.util.Map.Entry<String, String> en : headers.entrySet()) {
            sb.append(en.getKey()).append(": ").append(en.getValue()).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public AutoCloseable register(ApiSpec spec) {
        drainMainThreadActions(); // 方案 B：业务 seam（注册/启动前先执行延迟协议动作）
        if (closed.get()) {
            throw new IllegalStateException("RouteRuntime is closed for context @" + System.identityHashCode(context));
        }
        // 1) 先发布规则（读侧无锁即刻可见）
        generations.merge(spec);
        // 2) 绑定驱动（每 pattern 恰好一次；同 pattern 覆盖不重建绑定）。
        //    注册失败标记 runtime 降级，供 context 复用方丢弃该 Context。
        try {
            PatternBinder binder = binders.computeIfAbsent(spec.pattern(),
                    p -> RouteBinderRegistry.instance().bind(context, spec, this));
            //  注册超时被降级（规则已在内存发布，但驱动层未生效）→ 标记 runtime 降级仅用于
            //  **可观测/自愈判定**；绝不因此重建 Context（2026-09-29 裁定：路由是辅助设施，
            //  其可用性不得绑架会话/Context 生命周期；2026-09-30 收口后 Context 每 case 重建，
            //  该裁定与生命周期层解耦，route 层永不动 Context）。
            if (binder.isDegraded()) {
                markDegraded();
            }
            //  T2+ 规则生命上限（2026-09-29 裁定）：带监控窗口的 MONITOR 规则若在 monitorTimeoutMs
            //  内既未匹配、也未定案，同样"清理结束"该规则 —— 确保每条规则都有确定生命终点。
            armedAtNs.put(spec.pattern(), System.nanoTime()); // T1：规则 armed 计时起点
            schedulePurposeDeadline(spec);
            LOGGER.debug("[Route] registered pattern='{}' capability={}", spec.pattern(), spec.capability());
            return () -> {
                //  令牌化摘除（P2，单规则多能力位版）：仅当该能力位在当前合并规则中仍活跃时，
                //  将其剥离（清空字段 + disabled）。若该 pattern 剥离后已无任何活跃能力位，再摘除原生绑定。
                //  否则同 pattern 仍有其它能力位存活 ⇒ 本句柄只对自身能力位生效，不得摘掉同伴能力位的绑定。
                if (generations.removeCapabilityIfCurrent(spec.pattern(), spec.capability(), spec)
                        && generations.snapshot().specFor(spec.pattern()) == null) {
                    PatternBinder current = binders.get(spec.pattern());
                    if (current != null && binders.remove(spec.pattern(), current)) {
                        current.close();
                    }
                }
            };

        } catch (RuntimeException e) {
            //  绑定失败 ⇒ 必须把刚发布到内存规则表的规则摘掉：否则表里会留下"无驱动绑定、也无句柄可摘"的
            //  幽灵规则（stop* / metrics / clearRules 的计数与实际生命周期不一致）。
            removeGenerationIfCurrent(spec);
            markDegraded();
            throw e;
        }
    }

    @Override
    public boolean stop(RouteCapability capability, String pattern) {
        if (closed.get()) {
            return false;
        }
        // 单规则多能力位：stop 仅停用该能力位（标记 disabled，dispatch 链式裁决时跳过），
        // 不影响同 pattern 的其它能力位。并发安全由 GenerationRegistry 的 CAS 保证。
        boolean ok = generations.disableCapability(pattern, capability);
        if (ok) {
            LOGGER.debug("[Route] stopped capability={} for pattern='{}' (gen {})",
                    capability, pattern, generations.generation());
        }
        return ok;
    }

    @Override
    public boolean stopApi(String pattern) {
        ApiSpec current = generations.snapshot().specFor(pattern);
        return current != null && stop(current.capability(), pattern);
    }

    @Override
    public void dispatch(Route route, String pattern) {
        if (closed.get() || DriverFailureSignal.reported()) {
            // context 关闭中 / F1：UI 侧已上报"本场景驱动连接不可信" → fail-open 直通，
            // 绝不 resume / fulfill / abort（那些是拦截路径，失败信号下不得再碰驱动协议）。
            try {
                route.fallback();
            } catch (Exception ignored) {
                LOGGER.trace("[Route] fallback on closed/degraded context ignored: {}", ignored.toString());
            }
            abortPendingForFailFast();
            return;
        }
        dispatcher.dispatch(route, pattern, this);
    }

    /**
     * F1（2026-10-10）：失败信号下的<b>挂起收敛</b> —— 把已进入 DELAY 挂起（IO_AWAIT）的 claim
     * 立即终结（fallback 放行），不等 sweep(35s)，也不等场景收尾。
     *
     * <p><b>CAS 幂等</b>：首个失败信号下的 dispatch 触发一次收敛后置位，后续 dispatch 不再重复收敛
     * （不重复终结、route 不重复 fallback 命令）。终结动作是 sendMessageAsync（不泵连接），
     * 与 O1 单泵原则不冲突。</p>
     */
    private void abortPendingForFailFast() {
        if (failFastAborted.compareAndSet(false, true)) {
            claims.closeAll();
            LOGGER.warn("[Route] fail-fast: pending claims force-settled on driver failure "
                    + "(route is a helper, not a failure cause)");
        }
    }

    /** 巡检（调度线程）：强制落定超龄挂起 claim。 */
    private void sweep() {
        try {
            claims.sweep();
        } catch (Exception e) {
            LOGGER.warn("[Route] sweep failed: {}", e.toString());
        }
    }
    /**
     * 令牌化移除内存规则表条目：仅当该 pattern 当前仍是本实例时移除。
     *
     * <p><b>为什么必须令牌化</b>：句柄关闭者可能晚于"同 pattern 重新注册"发生 —— 若按 pattern 无条件移除，
     * 旧句柄就会摘掉<b>新规则</b>（与 {@code retireByPurpose} 的令牌语义保持一致）。</p>
     */
    private void removeGenerationIfCurrent(ApiSpec spec) {
        if (spec == null) {
            return;
        }
        // 单规则多能力位：注册绑定失败回滚时，只撤掉本能力位（合并规则下整条已变化，
        // 不能按整条实例比对），保留同 pattern 其它能力位。
        generations.removeCapabilityIfCurrent(spec.pattern(), spec.capability(), spec);
    }

    /**
     * 只清规则、保留 runtime 与 Context（V2-2；契约见 {@link RouteRuntime#clearRules()}）。
     *
     * <p><b>2026-10-10 语义对齐</b>：与目的退役一致 —— 本方法<b>只清内存规则表</b>
     * （方法返回后"不再有新请求命中"是确定性的，dispatch 即刻 fail-open），<b>不驱动 unroute</b>
     * （驱动绑定统一由收尾 {@link #close()} flush，消除 scenario 进行中的后台协议调用与
     * 主线程 UI 的连接竞态，理由见 {@link #retireByPurpose(ApiSpec)}）。</p>
     *
     * <p><b>返回值</b>：本次<b>实际清空的规则数</b>（基于规则表，幂等 —— 规则表已空时返回 0）；
     * 每个被清 pattern 补记退役观测（armed 时长 + 退役计数）。</p>
     */
    @Override
    public int clearRules() {
        if (closed.get()) {
            return 0; // 收尾中：close() 会统一 flush 剩余绑定
        }
        RuleGeneration base = generations.snapshot();
        int size = base.rules().size();
        if (size == 0) {
            return 0; // 幂等：规则表已空，无需任何动作
        }
        clearGenerations();
        for (String pattern : base.rules().keySet()) {
            recordArmedDuration(pattern); // T1：定稿本次被清规则的 armed 时长
            retiredByPurpose.incrementAndGet(); // 退役观测：本次清掉的规则计入目的退役
        }
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[Route] clearRules @{}: {} rule(s) cleared in memory (driver bindings kept; "
                        + "flushed at teardown), runtime and context kept",
                System.identityHashCode(context), size);
        return size;
    }

    /**
     * 【档 B】纯内存解绑：只清规则表，保留驱动侧绑定与 runtime（<b>零协议调用</b>，契约见
     * {@link RouteRuntime#detachRules()}）。
     *
     * <p><b>刻意不做</b>：不调 {@code retireByPurpose}、不调 {@code binder.close()}（二者都会发
     * {@code setNetworkInterceptionPatterns}）。handler 常驻 + 空规则表 ⇒ 命中即 fail-open
     * （见 {@code RouteDispatcher.dispatch} 的 {@code spec == null} 分支），与"未注册"等价。</p>
     */
    @Override
    public int detachRules() {
        if (closed.get()) {
            return 0; // 收尾中：close() 会统一处理剩余绑定
        }
        int cleared = generations.snapshot().rules().size();
        clearGenerations();
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[Route] detachRules @{}: {} rule(s) detached in memory; {} native binding(s) kept "
                        + "(zero protocol calls — hits fall back to pass-through)",
                System.identityHashCode(context), cleared, binders.size());
        return cleared;
    }

    /** 原子清空内存规则表（代际单调递增，读侧无锁即刻可见）。 */
    private void clearGenerations() {
        while (true) {
            RuleGeneration base = generations.snapshot();
            if (base.rules().isEmpty()) {
                return;
            }
            if (generations.compareAndSet(base, RuleGeneration.next(base, Map.of()))) {
                return;
            }
        }
    }

    /**
     * {@code context.onClose} 回调路径：context 正在销毁 ⇒ 只做内存收尾。
     *
     * <p><b>为什么 teardown 不 unroute（2026-10-15 定稿）</b>：runtime 关闭时无论 Context 是否仍存活，
     * 一律只做内存收尾（{@code binders.clear()}），驱动侧 handler 随 {@code context.close()} 由 Playwright
     * 原生释放。理由：
     * <ol>
     *   <li>unroute 是同步协议调用（{@code setNetworkInterceptionPatterns}，客户端 {@code NO_TIMEOUT}），
     *       实测可阻塞 10s/30s，且句柄已失效时直接抛 {@code Object doesn't exist}；</li>
     *   <li>本框架每 case 重建 Context（不变式 I-1），Context 关闭即 handler 全部释放，逐条 unroute
     *       纯属浪费的同步协议往返（也是"收尾未确证 ⇒ 状态分叉 ⇒ 病态 Context 遗传"的来源），无需再做。</li>
     * </ol>
     * 故 {@link #close()} 与 {@link #closeAfterContextClosed()} 均不再发起 unroute，与 {@code EngineControl} 裁定一致。</p>
     */
    private void closeAfterContextClosed() {
        markContextClosing();
        close();
    }

    /**
     * 标记「Context 正被主动关闭」（T8-5）：随后 {@link #close()} 跳过逐条 unroute，只做内存收尾。
     *
     * <p>与 {@link #closeAfterContextClosed()} 的区别仅在于<b>意图来源</b>：
     * 后者由 {@code context.onClose} 事件驱动（Context 已死），本方法由框架主动收尾驱动
     * （Context 仍存活、但紧接着就会被 {@code context.close()}）。
     */
    @Override
    public void markContextClosing() {
        contextClosing = true;
    }

    @Override
    public void close() {
        // 解除临时诊断看门狗武装（与 web HangWatchdog 一并去除）
        if (hangWatchdog != null) {
            hangWatchdog.disarm();
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        drainMainThreadActions(); // 方案 B：收尾 seam —— 最后一批 DELAY 协议动作（resume/fulfill）先执行
        VerboseLogging.logInfoIfVerbose(LOGGER, "[Route] closing runtime for context @{}",
                System.identityHashCode(context));
        sweeper.shutdownNow();
        claims.closeAll();
        //  收尾内存 flush（2026-10-15）：仅定稿剩余规则的 armed 时长 + binders.clear()，<b>不再发起任何
        //  unroute 协议调用</b>。与 {@code EngineControl} 裁定一致 —— context 关闭时驱动侧 route handler
        //  随 context 原生释放，逐条 unroute 是纯浪费的同步协议往返（客户端无超时、实测 10s 未确证），
        //  且是「收尾未确证 ⇒ 状态分叉 ⇒ 病态 Context 遗传」的来源；句柄已失效时 unroute 还会抛
        //  "Object doesn't exist" 报错。故 teardown 删除 unroute 清理，驱动绑定统一由 context.close() 释放。
        //  注：本方法仍可能由 context.onClose 在事件线程触发，但不再有同步协议调用，无泵连接风险。
        int flushed = binders.size();
        for (Map.Entry<String, PatternBinder> e : binders.entrySet()) {
            recordArmedDuration(e.getKey()); // T1：定稿剩余规则的 armed 时长
        }
        binders.clear();
        DriverFailureSignal.reset();   // B：消费即清零（本场景收尾已过，下一个场景重新开始）
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "[Route] route state: {} rule(s) retired by purpose, {} flushed at teardown",
                retiredByPurpose.get(), flushed);
        // T1 度量基线：每用例（= 每 runtime 生命周期）汇总一行，报表可见、真跑归档。
        LOGGER.info("[Route] case metrics @{}: dispatches={}, hits={}, retiredByPurpose={}, "
                        + "unconfirmedRetirements={}, rulesArmedMs={}, "
                        + "delayDeliveries(directed={}, queued={})",
                System.identityHashCode(context), dispatcher.dispatchCount(), dispatcher.hitCount(),
                retiredByPurpose.get(), unconfirmedRetirements.get(), armedDurationsMs,
                dispatcher.directedDeliveries(), dispatcher.queuedDeliveries());
        if (contextClosing) { retire.closeNoWait(); } else { retire.close(); } // 收尾 flush 是同步的；此处等待在途的"目的驱动撤销"完成（不阻塞、有界）
        if (ownerRegistry != null) {
            ownerRegistry.remove(context, this);
        }
    }

    /**
     * 目的达成即撤销（<b>令牌化入口</b>，2026-09-29：消除"旧触发者撤掉新规则"竞态）。
     *
     * <p><b>为何必须令牌化</b>：撤销触发者（监控窗口到期任务、响应定案回调）都持有<b>注册时的规则实例</b>，
     * 而 pattern 槽位可能已被"重新注册的新规则"接管。若只按 pattern 撤销，旧触发者就会撤掉<b>新规则</b>
     * ⇒ 辅助设施提前退场 ⇒ 行为漂移（正是要避免的"非业务层失败"）。</p>
     *
     * <p><b>令牌判定</b>：以框架既有的代际表 {@code generations.snapshot().specFor(pattern)} 为
     * "当前注册的规则实例"；与传入实例<b>不一致 ⇒ 已被替换/停止</b> ⇒ <b>拒绝撤销</b>
     * （新规则有自己的生命周期终点，不受旧触发者影响）。</p>
     *
     * @param spec 注册时的规则实例（令牌）
     * @return true=已退役 / 无需退役 / 令牌不一致（拒绝撤新规则）
     */
    public boolean retireByPurpose(ApiSpec spec) {
        if (spec == null || closed.get()) {
            return true;
        }
        // 令牌化摘除本能力位：仅当当前仍是本触发者注册的规则实例时才退役
        // （拒绝误撤新规则 / 已停规则）；同 pattern 其它能力位（MOCK/MODIFY/DELAY）不受影响。
        // 使用「移除」语义（而非仅标记 disabled）使该能力位彻底脱离合并视图。
        //
        // 【2026-10-10 结构性修复：目的退役不再驱动 unroute】
        //  此前"目的达成 → 提交独立执行器异步 unroute"会在 scenario 进行中产生<b>后台线程协议调用</b>，
        //  与主线程 UI 触发的请求 dispatch 并发泵同一条 Playwright 连接（官方模型：一个 Playwright =
        //  一条 Connection，客户端对象表/回调表<b>非线程安全</b>）→ 实证驱动级异常
        //  （"Cannot find parent object request@… to create route@…" / "Object doesn't exist: route@…"）
        //  冒泡为页面未捕获错误 ⇒ route 作为辅助设施反而中断 UI —— 违反核心不变式。
        //  修复：目的退役只做<b>内存规则表移除</b>（dispatch 即刻 fail-open 放行，观测语义不变），
        //  <b>驱动侧绑定保留</b>，统一由收尾 {@link #close()} 在场景结束时 flush（Context 即将关闭、
        //  无活跃 UI 操作 ⇒ 无并发窗口）。driver 侧 handler 常驻到收尾的开销：规则表空时命中即
        //  fallback（fail-open 出口），每请求一次 handler 调用 —— 可接受，换取「scenario 期间零后台
        //  驱动调用」的结构性保证。
        generations.removeCapabilityIfCurrent(spec.pattern(), spec.capability(), spec);
        if (generations.snapshot().specFor(spec.pattern()) == null) {
            retiredByPurpose.incrementAndGet();
            recordArmedDuration(spec.pattern());
        }
        return true;
    }

    /**
     * 目的达成即退役该 pattern（T2+ 入口：<b>规则随目的生灭</b>取代"随用例生灭"）。
     *
     * <p><b>2026-10-10 语义定稿（结构性竞态修复）</b>：本方法<b>只做内存退役</b>（规则表移除，
     * dispatch 即刻 fail-open），<b>不再驱动 unroute</b>。理由见 {@link #retireByPurpose(ApiSpec)}
     * 的注释：scenario 进行中的后台 unroute 与主线程 UI 并发泵同一连接会命中 Playwright
     * 客户端非线程安全竞态（实证 "Cannot find parent object"），违反"route 是辅助、不中断 UI"。
     * 驱动侧绑定统一由收尾 {@link #close()} flush（Context 关闭前无 UI 并发）。
     * 调用方（{@link #clearRules()} 等）负责先清空内存规则表，本方法只补记退役观测。</p>
     *
     * <p><b>不变式保持</b>：退役计数 {@link #retiredByPurpose} 照常累计（T1 可观测）；
     * {@code unconfirmedRetirements} 不再因目的退役增长（无协议提交 ⇒ 恒 0 ⇒ {@link #isClean()}
     * 恒 true）；撤销的"确证结论"由收尾 {@link #close()} 统一汇总。</p>
     *
     * @param pattern 规则 pattern（{@link ApiSpec#pattern()}）
     * @return true（幂等：未注册 / 已退役 / 收尾中均视为已处理）
     */
    @Override
    public boolean retireByPurpose(String pattern) {
        if (pattern == null || closed.get()) {
            return true; // 收尾中：close() 会统一 flush 剩余绑定
        }
        retiredByPurpose.incrementAndGet();
        recordArmedDuration(pattern); // T1：定稿 armed 时长
        return true;
    }

    /** T1：定稿某规则的 armed 时长（毫秒）；未记录起点（如从未 armed）则跳过。 */
    private void recordArmedDuration(String pattern) {
        Long start = armedAtNs.remove(pattern);
        if (start != null) {
            armedDurationsMs.put(pattern, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    @Override
    public int unconfirmedRetirements() {
        return unconfirmedRetirements.get();
    }

    @Override
    public boolean isClean() {
        return unconfirmedRetirements.get() == 0;
    }

    /**
     * 为"参与目的撤销"的规则安排<b>生命上限</b>（2026-09-29 裁定）：到 {@code monitorTimeoutMs}
     * 仍未达成目的（未匹配到 / 未定案）⇒ 同样"清理结束"该规则。
     *
     * <p><b>为什么需要</b>：若规则只因"没匹配到"就永久 armed，就会跨步骤/跨用例残留（正是"规则随用例
     * 生灭"的坏处）。窗口到期即清理，使"没匹配到"也有确定生命终点；窗口内已定案则由 {@code MonitorSink}
     * 提前撤销（{@link #retireByPurpose(String)} 幂等，本定时任务随即成为空操作）。</p>
     *
     * <p>{@code monitorTimeoutMs == 0}（对齐 {@code RouteDsl.timeout(0)} 的"永不超时"语义）⇒ 不设上限，
     * 交由用例收尾兜底 flush；{@code autoStopOnMatch} 类规则若未达标，同样由收尾 flush 清理。</p>
     */
    private void schedulePurposeDeadline(ApiSpec spec) {
        if (spec.capability() != RouteCapability.MONITOR
                || (spec.expectStatus() == null && !spec.hasBodyAssertions())
                || spec.monitorTimeoutMs() <= 0) {
            return;
        }
        try {
            sweeper.schedule(() -> retireByPurpose(spec), spec.monitorTimeoutMs(),
                    TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException runtimeClosing) {
            // runtime 已收尾（sweeper 已关闭）：无需再安排，收尾兜底 flush 已覆盖
            LOGGER.debug("[Route] purpose deadline skipped (runtime closing) for '{}'", spec.pattern());
        }
    }

    @Override
    public RouteMetrics metrics() {
        return new RouteMetrics(
                generations.generation(),
                claims.size(),
                claims.pendingCount(),
                guarded.acceptedCount(),
                guarded.rejectedCount(),
                monitor.size(),
                capture.size(),
                capture.droppedCount(),
                dispatcher.dispatchCount(),
                dispatcher.hitCount(),
                retiredByPurpose.get(),
                unconfirmedRetirements.get(),
                Map.copyOf(armedDurationsMs));
    }
}

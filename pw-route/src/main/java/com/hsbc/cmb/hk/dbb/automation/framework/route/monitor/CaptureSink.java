package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.exec.RouteIoExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API 信息采集器 —— CAPTURE 能力（横切观测）的记录与快照汇聚。
 *
 * <p>与 {@link MonitorSink}（断言导向）不同，本类是<b>信息导向</b>：采集规则（{@code capture()}
 * 修饰）与 mock/modify/delay/monitor 能力<b>正交共存</b>——同一请求同时被能力处理与被采集，
 * 两条路径无共享可变状态：
 * <ul>
 *   <li>请求侧 {@link #recordRequest} 在 route 事件线程（dispatch 入口，所有能力必经）调用，
 *       只入队（O(1)），不阻塞；</li>
 *   <li>响应侧 {@link #onResponseForSpec} 在响应轮询线程调用（由 {@code RouteRuntimeImpl} 轮询
 *       {@code Request#existingResponse()} 命中后按 spec 精确投递）：弹出 pending 快照 →
 *       volatile 写响应状态 → <b>就地</b>快照响应体（{@code Response.body()} 是协议调用，必须趁句柄
 *       存活时读；交给 IO 线程排队会因句柄被回收而永远采不到 body）→ 定案入队；</li>
 *   <li>{@link #dump} 在业务线程调用（消费式快照；含 pending 超时定案）。</li>
 * </ul>
 *
 * <p><b>不订阅 Playwright response 事件</b>：与 {@link MonitorSink} 同源——该订阅会令驱动在
 * {@code BrowserContextImpl.handleEvent} 解析已回收的 {@code response@} 句柄时抛
 * {@code Object doesn't exist}，并沿共享连接污染在途调用。改为路由通道按 spec 精确配对后，
 * 该事件根本不产生，竞态从根上消除（且响应配对不再依赖 URL 匹配，更精确）。
 *
 * <p>并发安全：线程之间只经 {@link ConcurrentLinkedQueue} 与 {@code volatile} 字段交换；
 * 入队（事件/IO 线程）与消费（业务线程）无锁。队列上限（{@link CaptureLimits#DEFAULT_MAX_CAPTURED}）
 * 由原子计数约束，超限丢弃新记录并计数（fail-open，防内存失控）。
 *
 * <p>防泄漏：等不到响应的请求在 {@link #dump} 时按超时窗口（默认 30s，复用
 * {@code ApiSpec.monitorTimeoutMs}）定案为 {@code timedOut} 快照并移出 pending。
 */
public final class CaptureSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureSink.class);

    private final int maxCaptured;
    private final long defaultTimeoutMs;
    private final RouteIoExecutor io;
    /** 已定案快照队列（业务线程 dump 消费）。 */
    private final ConcurrentLinkedQueue<CapturedApiCall> captured = new ConcurrentLinkedQueue<>();
    /** pattern → 未收到响应的请求快照（响应配对索引）。 */
    private final ConcurrentMap<String, ConcurrentLinkedQueue<CapturedExchange>> pendingByPattern =
            new ConcurrentHashMap<>();

    /** 队列上限计数（入队自增 / dump 消费递减；近似约束，防内存失控）。 */
    private final AtomicInteger count = new AtomicInteger();
    /** 超限丢弃数（可观测）。 */
    private final AtomicLong dropped = new AtomicLong();
    /** 超时定案数（可观测）。 */
    private final AtomicLong timedOutCount = new AtomicLong();
    /** 响应体读取失败数（可观测；失败仍保留核心快照）。 */
    private final AtomicLong bodyReadFailures = new AtomicLong();

    /** 采集策略（不可变冻结快照）：请求侧与响应侧两个闸门共用同一份，杜绝口径漂移。 */
    private final com.hsbc.cmb.hk.dbb.automation.framework.route.RouteCapturePolicy capturePolicy;

    /** 便捷构造：策略在本类内冻结一次（等价 {@code RouteCapturePolicy.resolveNow()}）。 */
    public CaptureSink(RouteIoExecutor io, int maxCaptured, long defaultTimeoutMs) {
        this(io, maxCaptured, defaultTimeoutMs,
                com.hsbc.cmb.hk.dbb.automation.framework.route.RouteCapturePolicy.resolveNow());
    }

    /**
     * 注入构造：策略由装配方（{@code RouteRuntimeImpl}）解析并冻结一次后注入，
     * 使请求侧与响应侧闸门判定恒为同一快照。
     *
     * @param io               IO 执行器
     * @param maxCaptured      采集队列上限
     * @param defaultTimeoutMs 未响应定案窗口（毫秒）
     * @param policy           已冻结的采集策略
     */
    public CaptureSink(RouteIoExecutor io, int maxCaptured, long defaultTimeoutMs,
                       com.hsbc.cmb.hk.dbb.automation.framework.route.RouteCapturePolicy policy) {
        this.io = io;
        this.maxCaptured = maxCaptured;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.capturePolicy = policy;
    }

    /**
     * 记录一次待采集请求（route 事件线程；capture 规则才记录）。
     *
     * @return true 表示已进入「等待响应」索引——调用方应据此启动响应轮询
     */
    public boolean recordRequest(Request request, ApiSpec spec) {
        // 【采集范围判定】默认【只采集主渠道为 MONITOR 的规则】；其余三类（DELAY / MODIFY / MOCK）
        // 需显式 .capture()，或把配置键 route.capture.all.type 置为 true 才采集（解析优先级：环境变量 → 系统属性 → 配置文件属性
        // → 默认值，见 {@code RouteCaptureSettings}）。
        // MONITOR 优先于 DELAY：.monitor().delay() 的主渠道是 MONITOR ⇒ 默认即被采到，而记录里仍带
        // 完整 channels（含 DELAY），故"被延迟的 api 信息"照样能查到（分渠道查询不受影响）。
        // captureBody() 语义不变：只控制"是否读响应体"，不决定是否入采集库。
        if (!spec.captureEnabled()
                && !ApiSpec.CHANNEL_MONITOR.equals(spec.primaryChannel())
                && !capturePolicy.allTypes()) {
            return false;
        }
        CapturedExchange snapshot = CapturedExchange.ofRequest(request, spec);
        pendingByPattern.computeIfAbsent(spec.pattern(), k -> new ConcurrentLinkedQueue<>()).add(snapshot);
        return true;
    }

    /**
     * 路由通道响应定案（按 spec 精确配对）——取代原 {@code context.onResponse} 事件订阅。
     *
     * <p>与请求侧 {@link #recordRequest} 使用同一 {@link ApiSpec} 实例，配对关系由调用方
     * （{@code RouteRuntimeImpl} 的响应轮询）保证，故<b>不再做 URL 匹配</b>。
     * 响应体采集（{@code captureBody}）交 IO 线程读取（截断 + 入队）；未开启则直接入队。
     *
     * @param spec     该请求在 dispatch 时命中的规则（与 recordRequest 同一实例）
     * @param response 已到达的真实响应（本地持有）；null 安全（忽略）
     */
    public void onResponseForSpec(ApiSpec spec, Response response) {
        // 与 recordRequest 完全同一口径：显式 capture() / 主渠道 MONITOR / route.capture.all.type=true
        // 三者之一才归档响应侧（spec 为空的场景仍先短路）。
        if (spec == null || response == null) {
            return;
        }
        if (!spec.captureEnabled()
                && !ApiSpec.CHANNEL_MONITOR.equals(spec.primaryChannel())
                && !capturePolicy.allTypes()) {
            return;
        }
        ConcurrentLinkedQueue<CapturedExchange> pending = pendingByPattern.get(spec.pattern());
        if (pending == null) {
            return;
        }
        CapturedExchange snapshot = pending.poll();
        if (snapshot == null) {
            return; // 无配对请求快照（响应早于采集规则注册 / 已 dump 定案）→ 忽略
        }
        snapshot.markResponse(response.status(), response.headers());
        if (spec.captureBodyEnabled()) {
            captureBodyNow(snapshot, spec, response);
        } else {
            enqueue(snapshot, null, null, null, false);
        }
    }

    /**
     * <b>就地</b>读取并快照响应体（响应轮询线程；<b>不再</b>交 IO 线程排队）。
     *
     * <p><b>为什么必须就地读（2026-09-28 修正）</b>：{@code Response.body()} 是协议调用，需要驱动侧
     * {@code response@<guid>} 句柄存活；旧实现把它丢给 IO 线程排队执行，实测在 IO 池繁忙（mock fetch 等）
     * 时 body 读晚数秒 ⇒ 句柄已被回收 ⇒ {@code Object doesn't exist: response@…} ⇒ 采集到的 body 恒为 null
     * （业务基于 body 的断言随之失真）。故改为"趁句柄存活时快照字节"，编码/截断仍在本次调用内完成
     * （纯 CPU，微秒级）。</p>
     *
     * <p>读取失败保持 fail-open：保留核心快照（body=null）并计数，绝不抛异常、绝不判场景失败。</p>
     */
    private void captureBodyNow(CapturedExchange snapshot, ApiSpec spec, Response response) {
        String contentType = null;
        byte[] bytes = null;
        try {
            contentType = response.headers().get("content-type");
            bytes = response.body();
        } catch (Throwable t) {
            bodyReadFailures.incrementAndGet();
            LOGGER.warn("[Route] capture body unavailable (driver handle reclaimed / page closed) "
                            + "pattern='{}' url='{}': {}",
                    spec.pattern(), snapshot.url(), t.toString());
            enqueue(snapshot, null, null, contentType, false);
            return;
        }
        try {
            // 【不截断】整响应保留 —— 供业务"取整响应 → 改指定节点 → 当 mock 回放"使用。
            // captureBodyLimitBytes 字段保留（兼容旧写法）但不再参与截断；截断标志恒为 false。
            // 原始字节同时透传：业务需要精确回放或自行解析时用 bytes，不必再经受字符串往返。
            String body = new String(bytes, MediaType.parse(contentType).charset());
            enqueue(snapshot, body, bytes, contentType, false);
        } catch (Throwable t) {
            // 编码失败（理论上不会）：同样 fail-open，保留核心快照 + 原始字节
            bodyReadFailures.incrementAndGet();
            enqueue(snapshot, null, bytes, contentType, false);
        }
    }

    /** 定案入队（队列满则丢弃并计数）；定案即打一行日志（请求/响应头 + 体 + 耗时，头已脱敏、体已截断）。 */
    private void enqueue(CapturedExchange snapshot, String body, byte[] bodyBytes, String contentType,
                         boolean truncated) {
        if (count.get() >= maxCaptured) {
            dropped.incrementAndGet();
            return;
        }
        CapturedApiCall call = toDto(snapshot, body, bodyBytes, contentType, truncated);
        captured.add(call);
        // 非消费式历史（peek 用）：与消费式队列并行维护，保证"用例收尾复核真实流量"不会被
        // 框架自身的断言结算（走 dump ⇒ 抽干 captured）抢走。上限同 maxCaptured，超出丢最旧。
        // 事件驱动、生产者无锁：历史写入走并发队列，随后仅发一次无阻塞信号（AQS 单次 CAS，不 park、不阻塞）
        history.add(call);
        while (history.size() > maxCaptured) {
            history.poll();
        }
        recordArrival.release();
        count.incrementAndGet();
        // 捕获结果 → Serenity 报告：报告要能回答"采到了什么、成功没"。**纯采集规则没有响应侧期望**，
        // 不进入 MonitorSink 的观测队列，其结果只能从这里出来，故不能省。
        // 明细渲染成本 = 头脱敏 + 体脱敏 + JSON pretty，**每次命中只渲染一遍**：
        // 原实现在同一次命中里渲染两遍（日志一遍 call.detail()、报告一遍 call.detailWith(...)，
        // 而 detailWith 内部又是 StringBuilder(detail())）。日志改用单行 summary —— 报告里有完整三段式，
        // 日志里再灌一份多 KB 的 pretty body 只是白烧 CPU 与磁盘。
        String evidence = null;
        try {
            evidence = call.detailWith(
                    "result    : CAPTURED (no assertion; 'response' section is what the browser actually received)");
        } catch (Throwable t) {
            // 展示是纯旁路：任何异常都吞掉（含非 JSON 体、脱敏/格式化失败），绝不影响采集与主流程
            LOGGER.debug("[Route] capture evidence render skipped (non-fatal): {}", t.toString());
        }
        try {
            LOGGER.info("[Route] captured api {}", call.summary());
        } catch (RuntimeException e) {
            LOGGER.debug("[Route] capture log skipped: {}", e.toString());
        }
        if (evidence != null) {
            try {
                RouteEvidenceRegistry.record("CAPTURE", call.url(), evidence);
            } catch (Throwable t) {
                LOGGER.debug("[Route] capture evidence report skipped (non-fatal): {}", t.toString());
            }
        }
    }

    /** 非消费式历史快照（{@link #peek()} 的数据源）：只增（按 {@code maxCaptured} 限量），不受 dump 影响。 */
    private final ConcurrentLinkedQueue<CapturedApiCall> history = new ConcurrentLinkedQueue<>();
    /**
     * 记录到达信号（无锁信号量）：入队点仅做一次无阻塞 {@code release()}，等待者
     * {@code tryAcquire(剩余时间)} —— 生产者不持任何锁、不被任何等待者阻塞
     * （对采集热路径<b>绝对零影响</b>），消费者无固定间隔轮询。
     */
    private final java.util.concurrent.Semaphore recordArrival = new java.util.concurrent.Semaphore(0);

    private static CapturedApiCall toDto(CapturedExchange snapshot, String body, byte[] bodyBytes,
                                         String contentType, boolean truncated) {
        // 分渠道透传：与 ofRequest 同源（channels=全部命中渠道，primaryChannel=优先级最高者）。
        return new CapturedApiCall(
                snapshot.pattern(), snapshot.method(), snapshot.url(),
                snapshot.requestHeaders(), snapshot.postDataPreview(),
                snapshot.responseStatus(), snapshot.responseHeaders(),
                body, truncated, snapshot.durationMs(), snapshot.responseTimedOut(),
                snapshot.recordedAtMillis(),
                snapshot.channels(), snapshot.primaryChannel(),
                contentType, bodyBytes, null);   // 载体（CapturedBody）由 ③ 接线时填入
    }

    /**
     * 取走全部已定案快照（消费式；幂等——第二次调用返回空列表）。
     *
     * <p>执行两件事：
     * <ol>
     *   <li><b>超时定案</b>：对仍在等待响应、且超过超时窗口的请求定案为 {@code timedOut}
     *       快照（{@code markTimedOut} + 入队 + 移出 pending），防止 pending 无限堆积；</li>
     *   <li><b>消费快照队列</b>：把已定案快照一次性返回并清空。</li>
     * </ol>
     *
     * @return 已定案快照（按定案顺序）；无记录时为空列表（永不返回 null）
     */
    public List<CapturedApiCall> dump() {
        sweepPendingTimeouts();
        List<CapturedApiCall> result = new ArrayList<>();
        CapturedApiCall call;
        while ((call = captured.poll()) != null) {
            count.decrementAndGet();
            result.add(call);
        }
        return result;
    }

    /**
     * 查看全部已定案快照（<b>非消费式</b>；幂等——重复调用返回同一批，直到被 {@link #dump()} 取走）。
     *
     * <p>数据源是<b>只增历史</b>（{@link #history}），而非消费式队列 {@code captured}：框架自身的断言结算
     * （走 {@link #dump()} ⇒ 抽干 captured）不会再让"用例收尾复核浏览器实际收到了什么"变成不可见 ——
     * 这正是本方法存在的理由（仅 peek 无法解决：共享队列被 dump 抽干后，谁都读不到）。清理仍以 dump 为准。</p>
     *
     * @return 已定案快照（按定案顺序）；无记录时为空列表（永不返回 null）
     */
    public List<CapturedApiCall> peek() {
        sweepPendingTimeouts();
        return new ArrayList<>(history);
    }

    /** 超时扫描（dump 内调用；弱一致遍历可接受）。 */
    private void sweepPendingTimeouts() {
        long now = System.nanoTime();
        for (ConcurrentLinkedQueue<CapturedExchange> pending : pendingByPattern.values()) {
            for (CapturedExchange snapshot : pending) {
                if (snapshot.responseStatus() != null || snapshot.responseTimedOut()) {
                    continue;
                }
                // 超时窗口恒定用本 sink 的默认值（横切语义：不受 monitor 断言超时影响）
                if (now - snapshot.recordedAtNanos() > defaultTimeoutMs * 1_000_000L) {
                    snapshot.markTimedOut();
                    timedOutCount.incrementAndGet();
                    pending.remove(snapshot);
                    enqueue(snapshot, null, null, null, false);
                }
            }
        }
    }

    /** 当前已定案未消费的快照数（可观测；消费后归零）。 */
    public int size() {
        return captured.size();
    }

    /** 超限丢弃总数（可观测）。 */
    public long droppedCount() {
        return dropped.get();
    }

    /** 超时定案总数（可观测）。 */
    public long timedOutCount() {
        return timedOutCount.get();
    }

    /** 响应体读取失败总数（可观测）。 */
    public long bodyReadFailureCount() {
        return bodyReadFailures.get();
    }

    // ==================== 索引查询 / 重置 / 跨模块视图（业务读取 API 采集信息） ====================

    /**
     * 清空该 sink 的全部采集数据（用例收尾调用；幂等）。
     *
     * <p>清三处：消费队列 {@code captured}、只增历史 {@code history}、在途等待 {@code pendingByPattern}，
     * 并把四个可观测计数归零 —— 这样即便同一 Context 被跨 scenario 复用，也不会残留上一条用例的采集数据。
     */
    public void reset() {
        captured.clear();
        history.clear();
        pendingByPattern.clear();
        count.set(0);
        dropped.set(0);
        timedOutCount.set(0);
        bodyReadFailures.set(0);
    }

    /** 全部采集记录（非消费式；数据源 = 只增 history）。 */
    public List<CapturedApiCall> all() {
        return new ArrayList<>(history);
    }

    /** 每个 pattern 的最近一次记录（非消费式；保持 pattern 首次出现顺序）。 */
    public List<CapturedApiCall> allLastPerPattern() {
        java.util.Map<String, CapturedApiCall> last = new java.util.LinkedHashMap<>();
        for (CapturedApiCall c : history) {
            if (c != null && c.pattern() != null) {
                last.put(c.pattern(), c);
            }
        }
        return new ArrayList<>(last.values());
    }

    /** 指定 endpoint 的最近一次记录：先按 pattern 精确匹配，再退化为 URL 前缀匹配；无则 null。 */
    public CapturedApiCall lastOf(String endpoint) {
        CapturedApiCall hit = null;
        for (CapturedApiCall c : history) {
            if (c == null || endpoint == null) {
                continue;
            }
            if (endpoint.equals(c.pattern()) || (c.url() != null && c.url().startsWith(endpoint))) {
                hit = c;
            }
        }
        return hit;
    }

    /** 指定 URL 的全部记录（非消费式）。 */
    public List<CapturedApiCall> byUrl(String url) {
        List<CapturedApiCall> out = new ArrayList<>();
        for (CapturedApiCall c : history) {
            if (c != null && url != null && url.equals(c.url())) {
                out.add(c);
            }
        }
        return out;
    }

    /** 指定渠道的全部记录（大小写不敏感；渠道名 MOCK / MODIFY / MONITOR / DELAY）。 */
    public List<CapturedApiCall> byChannel(String channel) {
        List<CapturedApiCall> out = new ArrayList<>();
        if (channel == null) {
            return out;
        }
        String want = channel.toUpperCase(java.util.Locale.ROOT);
        for (CapturedApiCall c : history) {
            if (c != null && c.channels().contains(want)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 等待首个满足条件的记录（非消费式；事件驱动，无固定间隔轮询）；超时或中断返回 null。 */
    public CapturedApiCall waitFor(java.util.function.Predicate<CapturedApiCall> predicate, long timeoutMs) {
        if (predicate == null) {
            return null;
        }
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            // 1) 丢弃陈旧许可：过期信号至多造成一次空转（许可可累积，故不会丢唤醒）
            recordArrival.drainPermits();
            // 2) 扫描（无锁；history 为并发队列，且生产者不持锁 ⇒ 无死锁/无锁竞争）
            for (CapturedApiCall c : history) {
                if (c != null && predicate.test(c)) {
                    return c;
                }
            }
            long remainingNs = deadline - System.nanoTime();
            if (remainingNs <= 0L) {
                return null;
            }
            // 3) 等待新记录信号：被唤醒后回到步骤 1 重新扫描
            //    （先扫描后等待 + 等待前排空许可 ⇒ 任意交错下都不会丢唤醒）
            boolean signalled;
            try {
                signalled = recordArrival.tryAcquire(remainingNs, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (!signalled && System.nanoTime() >= deadline) {
                return null;   // 等待超时且无新信号 ⇒ 无匹配记录
            }
        }
    }

    /** 转换为跨模块只读视图（业务侧读取；pw-core 的 View 内部已对集合做不可变拷贝）。 */
    public static com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView toView(
            CapturedApiCall c) {
        if (c == null) {
            return null;
        }
        return new com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView(
                c.pattern(), c.method(), c.url(),
                c.requestHeaders(), c.requestBodyPreview(), c.responseStatus(), c.responseHeaders(),
                c.responseBody(), c.responseBodyTruncated(), c.durationMs(), c.timedOut(),
                c.recordedAtMillis(), c.channels(), c.contentType(), c.responseBodyBytes());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.diag.OriginResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.route.diag.RequestOrigin;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.DriverErrors;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    /** 已定案快照队列（业务线程 dump 消费）。 */
    private final ConcurrentLinkedQueue<CapturedApiCall> captured = new ConcurrentLinkedQueue<>();
    /** pattern → 未收到响应的请求快照（响应配对索引）。 */
    private final ConcurrentMap<String, ConcurrentLinkedQueue<CapturedExchange>> pendingByPattern =
            new ConcurrentHashMap<>();
    /**
     * 「响应已到达、持有句柄、待业务线程读 body 后入队」的 exchange（轮询线程投递、业务线程消费）。
     *
     * <p>2026-10-11 定稿：轮询线程<b>零协议调用</b>——onResponseForSpec 只原子配对
     * （poll pendingByPattern 队头）+ 快照 status/headers（本地字段）+ 持有句柄，然后投递到本队列；
     * body()（协议调用）一律由业务线程（main）在 flush 时读取。队列顺序 = 响应到达顺序，
     * 保证 dump/查询的"定案顺序"近似请求顺序（稳定、无跨 pattern 乱序）。</p>
     */
    private final ConcurrentLinkedQueue<CapturedExchange> pendingMaterialization = new ConcurrentLinkedQueue<>();

    /** 队列上限计数（入队自增 / dump 消费递减；近似约束，防内存失控）。 */
    private final AtomicInteger count = new AtomicInteger();
    /** 超限丢弃数（可观测）。 */
    private final AtomicLong dropped = new AtomicLong();
    /** 超时定案数（可观测）。 */
    private final AtomicLong timedOutCount = new AtomicLong();
    /** 响应体读取失败数（可观测；失败仍保留核心快照）。 */
    private final AtomicLong bodyReadFailures = new AtomicLong();
    /** 3xx 重定向响应体不可用的次数（P0-2 / P0-3）：已知事实，单独计数，不计入 {@link #bodyReadFailures}。 */
    private final AtomicLong redirectNotAvailableBodyReads = new AtomicLong();
    /** 响应体读取因句柄<b>已被驱动回收</b>失败的次数（P0-3，框架/驱动竞态）。 */
    private final AtomicLong handleReclaimedBodyReads = new AtomicLong();
    /** 响应体读取因<b>真协议错误</b>（非句柄回收）失败的次数（P0-3）。 */
    private final AtomicLong protocolErrorBodyReads = new AtomicLong();

    /** 采集策略（不可变冻结快照）：请求侧与响应侧两个闸门共用同一份，杜绝口径漂移。 */
    private final com.hsbc.cmb.hk.dbb.automation.framework.route.RouteCapturePolicy capturePolicy;

    /** 便捷构造：策略在本类内冻结一次（等价 {@code RouteCapturePolicy.resolveNow()}）。 */
    public CaptureSink(int maxCaptured, long defaultTimeoutMs) {
        this(maxCaptured, defaultTimeoutMs,
                com.hsbc.cmb.hk.dbb.automation.framework.route.RouteCapturePolicy.resolveNow());
    }

    /**
     * 注入构造：策略由装配方（{@code RouteRuntimeImpl}）解析并冻结一次后注入，
     * 使请求侧与响应侧闸门判定恒为同一快照。
     *
     * <p><b>无 IO 执行器参数</b>：采集路径在响应轮询线程<b>就地</b>完成字节快照与留存
     * （{@code Response.body()} 是协议调用，必须趁句柄存活时读；提交到 IO 池排队会因句柄被回收
     * 而永远采不到 body），故本类不承载也不再接收 IO 执行器。
     *
     * @param maxCaptured      采集队列上限
     * @param defaultTimeoutMs 未响应定案窗口（毫秒）
     * @param policy           已冻结的采集策略
     */
    public CaptureSink(int maxCaptured, long defaultTimeoutMs,
                       com.hsbc.cmb.hk.dbb.automation.framework.route.RouteCapturePolicy policy) {
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
        // 需显式 .capture()，或把配置键 route.capture.all.type 置为 true 才采集（层序在 ConfigKeys 声明：
        // 环境变量 → 系统属性 → 配置文件属性 → 默认值；闸门用的是构造期冻结的 {@code RouteCapturePolicy}）。
        // MONITOR 优先于 DELAY：.monitor().delay() 的主渠道是 MONITOR ⇒ 默认即被采到，而记录里仍带
        // 完整 channels（含 DELAY），故"被延迟的 api 信息"照样能查到（分渠道查询不受影响）。
        // captureBody() 语义不变：只控制"是否读响应体"，不决定是否入采集库。
        // 采集优先级（高 → 低）：
        //   ① 逐规则显式关 .capture(false) ⇒ 不采（压过主渠道默认与下面的全局开关）；
        //   ② 逐规则显式开 / .monitor() 隐式（未 stopMonitor）⇒ 采；
        //   ③ 全局 route.capture.all.type=true ⇒ 兜底把非 MONITOR 主渠道的规则也纳入。
        if (spec.captureExplicitlyOff()) {
            return false;
        }
        if (!spec.captureActive()
                && !ApiSpec.CHANNEL_MONITOR.equals(spec.primaryChannel())
                && !capturePolicy.allTypes()) {
            return false;
        }
        CapturedExchange snapshot = CapturedExchange.ofRequest(request, spec);
        snapshot.holdSpec(spec); // 业务线程（dump）延迟读 body 需要规则引用
        // 归属三元组（所属 Page / Frame / 主文档导航代次）：只做本地字段读取，零协议调用；
        // 失败降级为 DEGRADED 而绝不丢观测（Service Worker / Frame 未创建/已 detach 均属此类）。
        snapshot.holdOrigin(origins.resolve(request));
        // 采集序号在**请求发起时**分配（而非响应物化入队时）：等待方要排除的是"动作前发起、
        // 动作后才完成"的请求 —— 若按入队顺序编号，这类请求会拿到更大编号从而再次满足门槛，
        // 正好复现我们要消灭的"过期命中"。volatile 写入对后续业务线程可见。
        snapshot.assignCaptureSeq(captureSequence.incrementAndGet());
        pendingByPattern.computeIfAbsent(spec.pattern(), k -> new ConcurrentLinkedQueue<>()).add(snapshot);
        return true;
    }

    /**
     * 路由通道响应定案（按 spec 精确配对）——取代原 {@code context.onResponse} 事件订阅。
     *
     * <p>与请求侧 {@link #recordRequest} 使用同一 {@link ApiSpec} 实例，配对关系由调用方
     * （{@code RouteRuntimeImpl} 的响应轮询）保证，故<b>不再做 URL 匹配</b>。
     * 响应体采集（{@code captureBody}）在本线程（响应轮询线程）<b>就地</b>读取（3xx 短路、
     * BodyStore 留存，见 {@link #captureBodyNow}）；未开启则直接入队。
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
        // 与 recordRequest 同一优先级阶梯：显式关最高（压过全局 route.capture.all.type=true）
        if (spec.captureExplicitlyOff()) {
            return;
        }
        if (!spec.captureActive()
                && !ApiSpec.CHANNEL_MONITOR.equals(spec.primaryChannel())
                && !capturePolicy.allTypes()) {
            return;
        }
        ConcurrentLinkedQueue<CapturedExchange> pending = pendingByPattern.get(spec.pattern());
        if (pending == null) {
            return;
        }
        // 配对：poll 队头（原子移除——并发下每条响应精确配对一条请求，不重复不遗漏；
        // FIFO 近似，与历史实现同口径：响应乱序时配对按请求到达顺序）。
        // 轮询线程到此为止只写本地字段（status/headers）+ 持有句柄，
        // **零协议调用**（body() 是协议调用，一律延后到业务线程 main 读取 —— 2026-10-11 定稿）。
        CapturedExchange snapshot = pending.poll();
        if (snapshot != null) {
            snapshot.markResponse(response.status(), response.headers());
            snapshot.holdResponse(response); // 持有句柄：业务线程（flush）延迟读 body
            pendingMaterialization.add(snapshot);
            // 唤醒所有等待者：记录已到达（待业务线程 flush 物化）——waitFor 循环会先 flush
            // 再扫描，唤醒后即可命中；与 enqueue 的 release 同款（queueLength+1 = 唤醒全部）。
            recordVersion.incrementAndGet();
            recordArrival.release(recordArrival.getQueueLength() + 1);
        }
    }

    /**
     * <b>业务线程（main）读取并快照响应体</b>——由 {@link #flushPendingResponses} 在 dump/peek/waitFor
     * 入口调用。<b>轮询线程不再触碰 body()</b>（body() 是协议调用；2026-10-11 定稿：轮询线程零协议调用，
     * 与 main 并发泵 Connection 会触发 HashMap 竞态 ⇒ 级联 Object doesn't exist，E2E run5 实证）。
     *
     * <p>读取失败保持 fail-open：保留核心快照（body=null）并计数，绝不抛异常、绝不判场景失败。</p>
     */
    private void captureBodyNow(CapturedExchange snapshot, ApiSpec spec) {
        Response response = snapshot.takeHeldResponse();
        if (response == null) {
            // 句柄已被回收（页面跳转 / 驱动清理）：body 不可读，保留 status/headers 快照
            bodyReadFailures.incrementAndGet();
            handleReclaimedBodyReads.incrementAndGet();
            LOGGER.warn("[Route] capture body unavailable (response handle reclaimed) "
                            + "pattern='{}' url='{}'",
                    spec.pattern(), snapshot.url());
            enqueue(snapshot, null, null, false);
            return;
        }
        String contentType = null;
        try {
            contentType = response.headers().get("content-type");
        } catch (Throwable ignored) {
            // headers() 为客户端本地快照（协议事件已携带），正常不抛；防御性兜底。
            LOGGER.trace("[Route] response.headers() defensive fallback: {}", DriverErrors.shortMessage(ignored));
        }
        // 3xx 重定向：响应体通常不可用（官方语义：重定向响应无 body 或 body 不可读）。
        // 显式短路、不发起 body() 协议调用 —— 避免无谓驱动往返 + 误计 bodyReadFailures；
        // location 等头已由 markResponse 保留，业务断言直接可用。
        int status = response.status(); // status() 为本地快照（事件已携带），零协议往返
        if (status >= 300 && status < 400) {
            // 已知事实（重定向响应无 body），单独计数，不计入 bodyReadFailures 竞态桶
            redirectNotAvailableBodyReads.incrementAndGet();
            enqueue(snapshot, null, contentType, false);
            return;
        }
        // SSE/流式（text/event-stream）：体永不结束，调 body() 会钉死线程直到 close 掐流
        // 才抛 TargetClosedError（E2E 实证：capture body unavailable WARN 即由此而来，2026-10-10）。
        // 刻意不读体，只按 status/headers 定案，证据侧由 CapturedExchange 标注「event-stream body not read」。
        if (isStreamingContentType(contentType)) {
            enqueue(snapshot, null, contentType, false);
            return;
        }
        byte[] bytes = null;
        try {
            bytes = response.body();
        } catch (Throwable t) {
            bodyReadFailures.incrementAndGet();
            if (DriverErrors.isHandleReclaimed(t)) {
                handleReclaimedBodyReads.incrementAndGet();
            } else {
                protocolErrorBodyReads.incrementAndGet();
            }
            LOGGER.warn("[Route] capture body unavailable (driver handle reclaimed / page closed) "
                            + "pattern='{}' url='{}': {}",
                    spec.pattern(), snapshot.url(), DriverErrors.shortMessage(t));
            enqueue(snapshot, null, contentType, false);
            return;
        }
        CapturedBody created = null;
        try {
            // 【不截断】整响应保留 —— 供业务"取整响应 → 改指定节点 → 当 mock 回放"使用。
            // captureBodyLimitBytes 字段保留（兼容旧写法）但不再参与截断；截断标志恒为 false。
            // 【阈值生效点】由装配方冻结的采集策略决定留存形态：≤inlineMax 内存原字节；
            // 文本型且 ≤gzipMax 内存 gzip；否则落盘临时文件（落盘失败 fail-open 退回内存）。
            // 本方法不再整体解码字符串：文本改由 CapturedBody.text(charset) 按需解码并缓存，
            // 编码口径仍由 contentType 决定（访问器内解析），故不再有"字符串往返"的堆峰值。
            created = BodyStore.of(bytes, contentType,
                    capturePolicy.inlineMaxBytes(), capturePolicy.gzipMaxBytes());
            enqueue(snapshot, created, contentType, false);
        } catch (Throwable t) {
            // of() 内部已 fail-open（gzip/落盘失败均退回内存）；此处兜住任何意外并退回内存原字节
            bodyReadFailures.incrementAndGet();
            LOGGER.debug("[Route] capture body retention failed, falling back to in-memory bytes: {}",
                    DriverErrors.shortMessage(t));
            if (created != null) {
                // 已建但未能交付（例如入队途中出错）⇒ 就地释放，避免"丢弃旧的 + 新建一个"双份泄漏
                created.close();
            }
            try {
                enqueue(snapshot, BodyStore.of(bytes, contentType, Integer.MAX_VALUE, 0), contentType, false);
            } catch (Throwable fallbackFailure) {
                enqueue(snapshot, null, contentType, false);
            }
        }
    }

    /**
     * <b>业务线程延迟物化</b>：把「响应已到达且持有句柄」的 exchange 读 body 后入队。
     *
     * <p>轮询线程（{@code onResponseForSpec}）只写 status/headers 本地字段并持有句柄，
     * <b>不入队</b>——body 读取（协议调用）一律延后到本方法（main 线程，串行安全）。
     * 本方法在 dump/peek/waitFor（业务消费入口）调用，保证：
     * ① 捕获结果只延迟到下一次业务查询（消费式 dump 前 flush 即完整）；
     * ② 轮询线程零协议调用（竞态根因消除）。</p>
     */
    private void flushPendingResponses() {
        // 按响应到达顺序（pendingMaterialization 队列顺序）逐个物化；顺序稳定，无跨 pattern 乱序。
        CapturedExchange snapshot;
        while ((snapshot = pendingMaterialization.poll()) != null) {
            ApiSpec spec = snapshot.spec();
            // 响应体读取判定：只要该规则进入了采集集合（recordRequest 已放行），
            // 就默认读取响应体（MONITOR 恒采集；all.type=true 时其它类型也一并采集）；
            // 仅逐规则 .captureBody(false) 显式关闭时例外（超大响应等场景的逃生阀）。
            boolean readBody = spec != null && !spec.captureBodyExplicitlyOff();
            if (readBody) {
                captureBodyNow(snapshot, spec); // 业务线程（main）：读 body 后入队
            } else {
                // 未读取响应体：只保留 status/headers（本地快照），不发起 body() 协议调用
                enqueue(snapshot, null, null, false);
            }
        }
    }

    /**
     * SSE/流式端点判断（与 {@code MonitorSink.isStreamingContentType} 同口径）：
     * 体不会结束的响应调 {@code response.body()} 会一直挂到流关闭，等于把观测线程钉死，
     * 故<b>刻意不读体</b>（只按 status/headers 定案，证据侧注明原因）。
     */
    private static boolean isStreamingContentType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
    }

    /** 定案入队（队列满则丢弃并计数）；定案即打一行日志（请求/响应头 + 体 + 耗时，头已脱敏、体已截断）。 */
    private void enqueue(CapturedExchange snapshot, CapturedBody body, String contentType,
                         boolean truncated) {
        if (count.get() >= maxCaptured) {
            dropped.incrementAndGet();
            if (body != null) {
                body.close();   // 丢弃即释放载体（文件型删临时文件），避免失去引用后泄漏
            }
            return;
        }
        CapturedApiCall call = toDto(snapshot, body, contentType, truncated, snapshot.captureSeq());
        captured.add(call);
        // 非消费式历史（peek 用）：与消费式队列并行维护，保证"用例收尾复核真实流量"不会被
        // 框架自身的断言结算（走 dump ⇒ 抽干 captured）抢走。上限同 maxCaptured，超出丢最旧。
        // 事件驱动、生产者无锁：历史写入走并发队列，随后仅发一次无阻塞信号（AQS 单次 CAS，不 park、不阻塞）
        history.add(call);
        historySize.incrementAndGet();
        while (historySize.get() > maxCaptured) {
            CapturedApiCall evicted = history.poll();
            if (evicted == null) {
                break;   // 理论不可达：并发下计数与队列短暂不一致时不空转
            }
            historySize.decrementAndGet();
            // 驱逐即释放，但必须排除"仍被消费队列持有"的对象：同一对象同处两队列，
            // 关掉它会让随后的 dump() 把已失效载体交给业务（use-after-close）。
            // 残留（本处不关、且此后无人关）由 reset() 与 JVM 退出兜底覆盖。
            if (evicted != call && !captured.contains(evicted) && evicted.body() != null) {
                evicted.body().close();
            }
        }
        // 通知等待者：版本前进 + 释放与"等待者数量 + 1"相当的许可。
        // 单次 release 只唤醒一个等待者，多等待者时其余会错过本应得到的唤醒；
        // 多释放的许可可累积，由等待者在"确无新记录"时清理，不会丢失。
        recordVersion.incrementAndGet();
        recordArrival.release(recordArrival.getQueueLength() + 1);
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
     * {@code history} 的近似计数：{@link ConcurrentLinkedQueue#size()} 是 O(n)，而入队路径每条记录都要
     * 判一次上限，故改用原子计数把长度判断降为 O(1)（近似约束口径与 {@code count} 一致）。
     */
    private final AtomicInteger historySize = new AtomicInteger();
    /**
     * 记录到达信号（无锁信号量）：入队点仅做一次无阻塞 {@code release()}，等待者
     * {@code tryAcquire(剩余时间)} —— 生产者不持任何锁、不被任何等待者阻塞
     * （对采集热路径<b>绝对零影响</b>），消费者无固定间隔轮询。
     */
    private final java.util.concurrent.Semaphore recordArrival = new java.util.concurrent.Semaphore(0);
    /**
     * 记录到达版本号：每次入队自增。等待者据此判断"自本次扫描以来是否已有新记录"，
     * 从而只在<b>确无新记录</b>时清理陈旧许可 —— 避免多等待者场景下清掉他人应得的唤醒（丢唤醒）。
     */
    private final java.util.concurrent.atomic.AtomicLong recordVersion =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * reset 代际戳：自增表示"历史已清空"，阻塞中的等待者据此立即返回 null 而非干等超时。
     *
     * <p>用 {@code AtomicLong} 而非 {@code volatile long}：并发 reset 下 {@code volatile++} 不是原子操作
     * （静态分析 {@code VO_VOLATILE_INCREMENT} 会直接拦下构建）。
     */
    private final java.util.concurrent.atomic.AtomicLong resetStamp =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * 采集序号分配器：每次 {@link #recordRequest}（即<b>请求发起</b>）取一个严格递增编号（首条为 1）。
     *
     * <p>用途：让等待器能表达"只要动作之后新产生的记录"（{@link #waitFor(java.util.function.Predicate, long, long)}），
     * 消除连续导航下"上一轮的迟到响应满足新一轮等待"的<b>过期命中</b>。
     * {@code recordVersion} 与之数值相近但语义不同：后者是"有无新记录"的唤醒判据，
     * 可能在<b>未实际入队</b>的情况下前进（如容量丢记录），故不能充当过滤门槛。
     */
    private final java.util.concurrent.atomic.AtomicLong captureSequence =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 请求归属解析器：派生每条记录的 {@code ownerPageId / frameId / navSeq}。
     *
     * <p>per-sink（= per BrowserContext）持有：代次表按 Page 划分，天然按 context 隔离，
     * 并行 scenario 互不污染。随 {@link #reset()} 清空，内存有界。
     */
    private final OriginResolver origins = new OriginResolver();

    private static CapturedApiCall toDto(CapturedExchange snapshot, CapturedBody body, String contentType,
                                         boolean truncated, long captureSeq) {
        // 分渠道透传：与 ofRequest 同源（channels=全部命中渠道，primaryChannel=优先级最高者）。
        return new CapturedApiCall(
                snapshot.pattern(), snapshot.method(), snapshot.url(),
                snapshot.requestHeaders(), snapshot.postDataPreview(),
                snapshot.responseStatus(), snapshot.responseHeaders(),
                null, truncated, snapshot.durationMs(), snapshot.responseTimedOut(),
                snapshot.recordedAtMillis(),
                snapshot.channels(), snapshot.primaryChannel(),
                contentType, null, body, captureSeq, snapshot.origin());
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
        flushPendingResponses(); // 业务线程（main）：延迟读 body 并入队（轮询线程零协议调用）
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
        flushPendingResponses(); // 业务线程（main）：延迟读 body 并入队（轮询线程零协议调用）
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
                    enqueue(snapshot, null, null, false);
                }
            }
        }
    }

    /** 当前已定案未消费的快照数（可观测；消费后归零）。 */
    public int size() {
        flushPendingResponses(); // 业务线程（main）：延迟读 body 并入队（轮询线程零协议调用）
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

    /** 3xx 重定向响应体不可用的次数（P0-2 / P0-3）：已知事实，单独计数。 */
    public long redirectNotAvailableBodyReads() {
        return redirectNotAvailableBodyReads.get();
    }

    /** 响应体读取因句柄已被驱动回收失败的次数（P0-3，框架/驱动竞态）。 */
    public long handleReclaimedBodyReads() {
        return handleReclaimedBodyReads.get();
    }

    /** 响应体读取因真协议错误（非句柄回收）失败的次数（P0-3）。 */
    public long protocolErrorBodyReads() {
        return protocolErrorBodyReads.get();
    }

    // ==================== 索引查询 / 重置 / 跨模块视图（业务读取 API 采集信息） ====================

    /**
     * 清空该 sink 的全部采集数据（用例收尾调用；幂等）。
     *
     * <p>清三处：消费队列 {@code captured}、只增历史 {@code history}、在途等待 {@code pendingByPattern}，
     * 并把四个可观测计数归零 —— 这样即便同一 Context 被跨 scenario 复用，也不会残留上一条用例的采集数据。
     */
    public void reset() {
        // 生命周期（契约见 CapturedBody）：sink 拥有响应体载体，reset 即释放（文件型删临时文件）。
        // captured 与 history 可能持有同一对象，close 幂等；pendingByPattern 只存请求侧快照，无载体。
        closeBodies(captured);
        closeBodies(history);
        captured.clear();
        history.clear();
        pendingByPattern.clear();
        pendingMaterialization.clear(); // 待物化句柄一并丢弃（响应体未读，无载体需释放）
        count.set(0);
        historySize.set(0);
        dropped.set(0);
        timedOutCount.set(0);
        bodyReadFailures.set(0);
        redirectNotAvailableBodyReads.set(0);
        handleReclaimedBodyReads.set(0);
        protocolErrorBodyReads.set(0);
        // 归属代次表随历史一并清空：新的 scenario 会用新的 Page 集合，旧代次已无意义。
        // 若不清，identityHashCode 派生的 owner id 在长生命周期 context 上存在极小概率撞号。
        origins.reset();
        // 结束所有等待者：resetStamp 自增让 waitFor 立即返回 null（历史已清空，再等无意义），
        // 并按等待者数量释放许可把阻塞中的线程叫醒（单次 release 只唤醒一个）。
        resetStamp.incrementAndGet();
        recordArrival.release(recordArrival.getQueueLength() + 1);
    }

    /** 关闭一批记录持有的响应体载体（幂等；文件型由此删除临时文件）。 */
    private static void closeBodies(java.util.Collection<CapturedApiCall> calls) {
        for (CapturedApiCall call : calls) {
            if (call != null && call.body() != null) {
                call.body().close();
            }
        }
    }

    /** 全部采集记录（非消费式；数据源 = 只增 history）。 */
    public List<CapturedApiCall> all() {
        flushPendingResponses(); // 业务线程（main）：延迟读 body 并入队（轮询线程零协议调用）
        return new ArrayList<>(history);
    }

    /** 每个 pattern 的最近一次记录（非消费式；保持 pattern 首次出现顺序）。 */
    public List<CapturedApiCall> allLastPerPattern() {
        flushPendingResponses(); // 业务线程（main）：延迟读 body 并入队（轮询线程零协议调用）
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
        flushPendingResponses(); // 业务线程（main）：延迟读 body 并入队（轮询线程零协议调用）
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
        flushPendingResponses(); // 业务线程（main）：延迟读 body 并入队（轮询线程零协议调用）
        List<CapturedApiCall> out = new ArrayList<>();
        for (CapturedApiCall c : history) {
            if (c != null && url != null && url.equals(c.url())) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 指定渠道的全部记录（大小写不敏感）。
     *
     * <p><b>匹配口径</b>：命中"记录<b>声明的</b> {@code channels()} 含该渠道" <b>或</b>
     * "{@code primaryChannel()} 等于该渠道"。故裸 {@code .monitor()} 规则（未声明额外渠道）
     * 也能被 {@code byChannel("MONITOR")} 查到 —— 与 {@link ApiSpec#CHANNEL_MONITOR} 在采集闸门
     * 中的判定口径（看 {@code primaryChannel()}）一致，避免"采集按主渠道、查询按声明渠道"的分裂。
     */
    public List<CapturedApiCall> byChannel(String channel) {
        flushPendingResponses(); // 业务线程（main）：延迟读 body 并入队（轮询线程零协议调用）
        List<CapturedApiCall> out = new ArrayList<>();
        if (channel == null) {
            return out;
        }
        String want = channel.toUpperCase(java.util.Locale.ROOT);
        for (CapturedApiCall c : history) {
            if (c != null && (c.channels().contains(want) || want.equalsIgnoreCase(c.primaryChannel()))) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 当前<b>已分配</b>到的采集序号（= 已记录的请求数，与是否已物化入队无关）。
     *
     * <p>业务在触发动作前取该值，然后把 {@code 值 + 1} 作为 {@code minCaptureSeq} 传给等待接口，
     * 即可只看本次动作之后新产生的采集 —— 这是"避免过期命中"的推荐用法。
     */
    public long captureSequence() {
        return captureSequence.get();
    }

    /** 等价于 {@link #waitFor(java.util.function.Predicate, long, long)}，{@code minCaptureSeq = 0}（不限代次）。 */
    public CapturedApiCall waitFor(java.util.function.Predicate<CapturedApiCall> predicate, long timeoutMs) {
        return waitFor(predicate, timeoutMs, 0L);
    }

    /**
     * 等待首个满足条件的记录（非消费式；事件驱动，无固定间隔轮询）；超时或中断返回 null。
     *
     * @param minCaptureSeq 只看 {@code captureSeq >= minCaptureSeq} 的记录。
     *                      <b>0 表示不限</b>（既有行为）；&gt;=1 表示"只要某个时间点之后新产生的采集" ——
     *                      典型用法是在触发业务动作<b>之前</b>取 {@link #captureSequence()}，把该值 +1 传进来，
     *                      从而杜绝"上一轮导航的迟到响应满足本轮等待"的过期命中。
     *                      {@link CapturedApiCall#UNSEQUENCED}（-1）的记录在门槛 &gt;=1 时不可见。
     */
    public CapturedApiCall waitFor(java.util.function.Predicate<CapturedApiCall> predicate, long timeoutMs,
                                   long minCaptureSeq) {
        if (predicate == null) {
            return null;
        }
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            // 0) 物化已到达响应的 body（业务线程 main，串行安全）——轮询线程零协议调用
            //    （2026-10-11 定稿：body() 是协议调用，不再在响应轮询线程读取）
            flushPendingResponses();
            long stampAtScan = resetStamp.get();
            long versionAtScan = recordVersion.get();
            // 1) 扫描（无锁；history 为并发队列，且生产者不持锁 ⇒ 无死锁、无锁竞争）
            for (CapturedApiCall c : history) {
                // minCaptureSeq <= 0 ⇒ 完全按既有行为（不做任何序号门槛，历史中的所有记录都可选）
                if (c != null && (minCaptureSeq <= 0L || c.captureSeq() >= minCaptureSeq)
                        && predicate.test(c)) {
                    return c;
                }
            }
            long remainingNs = deadline - System.nanoTime();
            if (remainingNs <= 0L) {
                return null;
            }
            // 2) 期间发生 reset（历史已清空）⇒ 立即返回，不干等超时
            if (resetStamp.get() != stampAtScan) {
                return null;
            }
            // 3) 自扫描以来已有新记录 ⇒ 立即重扫，无需等待
            if (recordVersion.get() != versionAtScan) {
                continue;
            }
            // 4) 确无新记录，此刻清理陈旧许可不会吞掉任何等待者应得的唤醒
            recordArrival.drainPermits();
            // 5) 等待新记录信号；被唤醒后回到步骤 1 重新扫描
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

    /**
     * 转换为跨模块只读视图（业务侧读取；pw-core 的 View 内部已对集合做不可变拷贝）。
     *
     * <p><b>内存口径（改造点）</b>：原先分别调用 {@code responseBody()}（把解码结果<b>永久缓存进共享载体</b>
     * 的 {@code cachedText}，被 history/captured 长期引用）与 {@code responseBodyBytes()}（再读一遍字节），
     * 大体单次查询既多一次全量读取、又把字符串长期钉在共享载体上。
     * 现在<b>只读一次字节</b>，文本由同一份字节一次性解码 —— 读取次数不增、载体不再被解码结果钉住，
     * 对外契约（两个字段都有值、字符集口径一致）完全不变。
     */
    public static com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView toView(
            CapturedApiCall c) {
        if (c == null) {
            return null;
        }
        byte[] responseBytes = c.responseBodyBytes();   // 单次读取（副本）
        String responseText = responseBytes == null ? null
                : new String(responseBytes, MediaType.parse(c.contentType()).charset());
        return new com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView(
                c.pattern(), c.method(), c.url(),
                c.requestHeaders(), c.requestBodyPreview(), c.responseStatus(), c.responseHeaders(),
                responseText, c.responseBodyTruncated(), c.durationMs(), c.timedOut(),
                c.recordedAtMillis(), c.channels(), c.contentType(), responseBytes,
                toOriginView(c.origin()));
    }

    /** 归属映射：pw-route 的 {@link RequestOrigin} → pw-core 的跨模块视图（null 安全）。 */
    private static com.hsbc.cmb.hk.dbb.automation.framework.common.route.RequestOriginView toOriginView(
            RequestOrigin origin) {
        if (origin == null) {
            return com.hsbc.cmb.hk.dbb.automation.framework.common.route.RequestOriginView.UNKNOWN;
        }
        if (origin == RequestOrigin.DEGRADED) {
            return com.hsbc.cmb.hk.dbb.automation.framework.common.route.RequestOriginView.DEGRADED;
        }
        return new com.hsbc.cmb.hk.dbb.automation.framework.common.route.RequestOriginView(
                origin.ownerPageId(), origin.frameId(), origin.navSeq(), origin.degraded());
    }
}

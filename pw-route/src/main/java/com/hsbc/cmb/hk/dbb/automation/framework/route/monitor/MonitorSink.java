package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.DriverErrors;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 观测汇聚器 —— MONITOR 能力的记录与响应断言。
 *
 * <p><b>响应侧获取方式：路由通道（绝不订阅 response 事件）</b>。
 * Playwright 客户端在 {@code BrowserContextImpl.handleEvent} 的 {@code "response"} 分支会为每个事件
 * 句柄调用 {@code Connection.getExistingObject(guid)}；一旦服务端已回收该 {@code response@} 句柄即抛
 * {@code PlaywrightException: Object doesn't exist}。该异常发生在业务 lambda <b>之前</b>、无法被回调内
 * try/catch 拦截，且因 {@code Connection} 为共享单连接，会以「此刻正在等结果的任意调用线程」为宿主抛出
 * （本仓库实测：会话校验导航被炸 → 误判会话失效 → 每轮全量重登）。故本类<b>不订阅</b>该事件：
 * 由 {@code RouteRuntimeImpl} 在 route 分发入口拿到 {@link Request} 后，于调度线程轮询
 * {@link Request#existingResponse()}（请求本地字段、零协议往返、不触碰对象表），命中即以
 * {@link #onResponseForSpec(ApiSpec, Request, Response)} 按<b>请求身份</b>精确配对投递
 * （而非 FIFO 队首，避免同 pattern 并发请求错配）。
 *
 * <p>并发模型（<b>快照-断言两阶段</b>，2026-09-28 修正 + 2026-10-10 同步化）：
 * <ul>
 *   <li>{@code recordRequest} 在 route 事件线程调用（入队，不阻塞），返回是否已进入等待响应索引；</li>
 *   <li>{@code onResponseForSpec} 在响应轮询线程调用：<b>就地</b>完成 {@code status}/{@code headers}/
 *       {@code body} 的<b>快照</b>（{@code body()} 是协议调用，必须趁句柄存活时读——旧实现把它交给 IO
 *       线程排队执行，实测在 IO 池繁忙时必失败 {@code Object doesn't exist: response@…}），
 *       body 断言（JSONPath/包含/正则/表单）在<b>本线程同步就地执行</b>（纯 CPU、微秒级）——
 *       快照后零句柄依赖，不再投 IO 线程（2026-10-10 简化：异步投递曾导致 IO 队列满时<b>静默丢断言</b>，
 *       即"业务以为断言了、实际没执行"的假绿风险）；</li>
 *   <li>{@code drain} 在业务线程调用（快照消费）。</li>
 * </ul>
 *
 * <p><b>句柄不可用 ⇒ 判 inconclusive（不判失败）</b>：若快照时响应句柄已被驱动回收（或页面已关闭），
 * 该交换记 {@code inconclusive}（WARN + {@link #inconclusiveBodyReads()} 计数）并只按 status 定案 ——
 * 这是框架/驱动竞态而非应用缺陷，绝不能把绿场景判红。
 * 线程之间只经 {@link ConcurrentLinkedQueue} 与 {@code volatile} 字段交换，无共享可变状态。
 *
 * <p>防泄漏：等不到响应的请求在 {@code drain} 时按 monitorTimeoutMs 标记超时并移出 pending 索引。
 */
public final class MonitorSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorSink.class);

    private final ConcurrentLinkedQueue<CapturedExchange> exchanges = new ConcurrentLinkedQueue<>();

    /**
     * 观测队列上限（默认）。
     *
     * <p><b>为什么必须有上界</b>：{@code exchanges} 在生产路径<b>只有</b>「超时扫描」（{@link
     * #drainSettledFailures()}）在读，定案后的条目不会再被移除，而 {@code drain()}（全量消费）仅测试调用 ——
     * 于是"观测过的请求"会随 context 生命周期无限累积（每条还挂着请求体预览与响应预览）。
     * 与 {@code CaptureSink.maxCaptured} 同款 fail-open 取舍：宁可少留快照，也不让观测设施无界吃内存。</p>
     */
    static final int DEFAULT_MAX_TRACKED_EXCHANGES = 1_000;

    /** 观测队列当前上限（可用 {@link #MonitorSink(java.util.function.Consumer, int)} 覆盖）。 */
    private final int maxTrackedExchanges;

    /** MONITOR 响应体<b>断言</b>上限（字节）：超限的 body 不再整体物化解析（降级仅 status/headers，inconclusive）。 */
    private final int assertBodyMaxBytes;

    /**
     * MONITOR 响应体断言<b>默认</b>上限（字节，2 MB；与 {@code RouteConfig.DEFAULT_ASSERT_BODY_MAX_BYTES}
     * 同值 —— 装配方（{@code RouteRuntimeImpl}）从冻结的 {@code RouteCapturePolicy} 注入，此处仅兜底）。
     */
    static final int DEFAULT_ASSERT_BODY_MAX_BYTES = 2_097_152;

    /** 因超出上限被丢弃的观测条目数（fail-open 可观测指标）。 */
    private final AtomicLong droppedExchanges = new AtomicLong(0);
    /** 已定案且断言失败的交换（上报队列；事件线程/调度线程入队，业务线程 drain 消费）。 */
    private final ConcurrentLinkedQueue<CapturedExchange> settledFailures = new ConcurrentLinkedQueue<>();
    /** pattern → 未收到响应的请求队列（响应断言索引，同一对象也存在于 exchanges）。 */
    private final ConcurrentMap<String, ConcurrentLinkedQueue<CapturedExchange>> pendingByPattern = new ConcurrentHashMap<>();
    /**
     * body 断言因响应句柄不可用（驱动已回收 / 页面关闭）而<b>未能判定</b>的次数（inconclusive）。
     *
     * <p>为何单独计数：这类失败是框架/驱动竞态，不是应用缺陷，故不判场景失败（见
     * {@link #onResponseForSpec}）。计数使"确实没断言上"仍可被观测（而不是静默放过）。</p>
     */
    private final AtomicLong inconclusiveBodyReads = new AtomicLong(0);
    /**
     * 3xx 重定向响应体不可用的次数（P0-2 / P0-3）：重定向响应体本就不可用（官方语义），
     * 属<b>已知事实</b>而非竞态，单独计数（不计入 {@link #inconclusiveBodyReads()} 竞态桶），
     * 避免把"正常 3xx"误计为 inconclusive。
     */
    private final AtomicLong redirectNotAvailableBodyReads = new AtomicLong(0);
    /**
     * body 断言因响应句柄<b>已被驱动回收</b>（navigate-away / context closed / 对象表摘除）而未能判定的次数（P0-3）。
     * 属框架/驱动竞态，与 {@link #protocolErrorBodyReads()} 区分计数。
     */
    private final AtomicLong handleReclaimedBodyReads = new AtomicLong(0);
    /**
     * body 断言因<b>真协议错误</b>（非句柄回收类竞态）而未能判定的次数（P0-3）。
     */
    private final AtomicLong protocolErrorBodyReads = new AtomicLong(0);
    /** body 断言因<b>超出断言体上限</b>（{@link #assertBodyMaxBytes}）而<b>被跳过</b>的次数（inconclusive）。 */
    private final AtomicLong skippedBodyAssertions = new AtomicLong(0);
    /** per-spec 命中计数（autoStopOnMatch + minMatches 对齐：达到后停止记录/断言）。 */
    private final ConcurrentMap<ApiSpec, AtomicInteger> hitCounts = new ConcurrentHashMap<>();

    /**
     * 目的驱动撤销（T2+，即"规则随目的生灭"取代"规则随用例生灭"）：带响应侧期望的 MONITOR 规则在<b>首个响应定案</b>后
     * 即撤销绑定 —— 不管断言成功还是失败（目的已达成，无需继续 armed）。这是<b>不可关闭的默认行为</b>，
     * 没有 kill-switch（{@code autoStopOnMatch} 场景走"达到 minMatches 才撤"，见 {@link #purposeSettles}）。
     */

    /** 已触发"目的达成"的规则实例（幂等键 = 规则实例，与请求/响应配对键一致）。 */
    private final Set<ApiSpec> purposeMetSpecs = ConcurrentHashMap.newKeySet();

    /**
     * 目的达成回调（由 {@code RouteRuntimeImpl} 注入 → 令牌化撤销）；{@code null} = 不驱动撤销。
     *
     * <p>入参是<b>注册时的规则实例</b>（令牌），而非 pattern 字符串：这样"重注册后的新规则"不会被
     * 旧规则的触发者误撤（见 {@code RouteRuntimeImpl.retireByPurpose(ApiSpec)} 的令牌判定）。</p>
     */
    private final java.util.function.Consumer<ApiSpec> onPurposeMet;

    /** 兼容构造（单测/无撤销驱动场景）：等价于不驱动目的撤销。 */
    public MonitorSink() {
        this(null);
    }

    /**
     * @param onPurposeMet "目的达成"回调，入参为<b>规则实例（令牌）</b>；实现必须非阻塞（提交式），可传 {@code null}
     */
    public MonitorSink(java.util.function.Consumer<ApiSpec> onPurposeMet) {
        this(onPurposeMet, DEFAULT_MAX_TRACKED_EXCHANGES);
    }

    /**
     * 完整构造（上限可覆盖：容量标定/单测用小上限验证"丢最旧"路径）。
     *
     * @param onPurposeMet        "目的达成"回调（可 {@code null}）
     * @param maxTrackedExchanges 观测队列上限；{@code <= 0} 视为 {@link #DEFAULT_MAX_TRACKED_EXCHANGES}
     */
    public MonitorSink(java.util.function.Consumer<ApiSpec> onPurposeMet,
                       int maxTrackedExchanges) {
        this(onPurposeMet, maxTrackedExchanges, DEFAULT_ASSERT_BODY_MAX_BYTES);
    }

    /**
     * 完整构造 + 断言体上限（装配方注入：{@code RouteRuntimeImpl} 从冻结的 {@code RouteCapturePolicy}
     * 传入 {@code assertBodyMaxBytes()}，单测可用小值验证"超限降级"路径）。
     *
     * @param onPurposeMet        "目的达成"回调（可 {@code null}）
     * @param maxTrackedExchanges 观测队列上限；{@code <= 0} 视为 {@link #DEFAULT_MAX_TRACKED_EXCHANGES}
     * @param assertBodyMaxBytes  响应体断言上限（字节）；{@code <= 0} 视为 {@link #DEFAULT_ASSERT_BODY_MAX_BYTES}
     */
    public MonitorSink(java.util.function.Consumer<ApiSpec> onPurposeMet,
                       int maxTrackedExchanges, int assertBodyMaxBytes) {
        this.onPurposeMet = onPurposeMet;
        this.maxTrackedExchanges = maxTrackedExchanges > 0 ? maxTrackedExchanges : DEFAULT_MAX_TRACKED_EXCHANGES;
        this.assertBodyMaxBytes = assertBodyMaxBytes > 0 ? assertBodyMaxBytes : DEFAULT_ASSERT_BODY_MAX_BYTES;
    }

    /**
     * 该规则是否"目的已达成、可以撤销"（2026-09-29 细化）：
     * <ul>
     *   <li>MONITOR + 至少一条响应侧期望（无期望的纯采集 MONITOR 无"定案"概念）；</li>
     *   <li><b>{@code autoStopOnMatch(true)} 规则：达到 {@code minMatches} 才撤销</b>（其目的是"观察 N 次"）；
     *       未达标不提前撤销 ⇒ 交由监控窗口到期（{@code monitorTimeoutMs}）或用例收尾兜底 flush 清理；</li>
     *   <li>其余带期望的 MONITOR 规则：首个响应定案即撤销（armed 窗口收敛为"首个响应"）。</li>
     * </ul>
     */
    private boolean purposeSettles(ApiSpec spec) {
        if (spec.capability() != RouteCapability.MONITOR) {
            return false;
        }
        if (spec.expectStatus() == null && !spec.hasBodyAssertions()) {
            return false;
        }
        if (spec.autoStopOnMatch()) {
            AtomicInteger hits = hitCounts.get(spec);
            return hits != null && hits.get() >= spec.minMatches();
        }
        return true;
    }

    /**
     * 目的达成即回调一次（幂等：同一规则实例只触发一次）。
     *
     * <p><b>顺序保证</b>：调用点必须位于"断言结算"之后（先结算失败证据，再撤销），否则会丢失败上报。
     * 回调异常绝不逃逸到事件/调度线程（只 WARN）。</p>
     */
    private void firePurposeMet(ApiSpec spec) {
        if (onPurposeMet == null || !purposeSettles(spec) || !purposeMetSpecs.add(spec)) {
            return;
        }
        try {
            onPurposeMet.accept(spec);
            LOGGER.debug("[Route] purpose met for '{}' → retire submitted", spec.pattern());
        } catch (Throwable t) {
            LOGGER.warn("[Route] purpose-met callback failed for '{}': {}", spec.pattern(), DriverErrors.shortMessage(t));
        }
    }

    /**
     * 记录一次被观测请求（route 分发入口调用，非阻塞）。
     *
     * <p><b>只记录「有响应侧期望」的 MONITOR 规则</b>（有 expectStatus 或 body 断言）。其余一律不记录：
     * MOCK / MODIFY / DELAY 能力（以及无任何断言的 MONITOR 规则）既无断言可做，也不该进入观测队列 ——
     * 它们永远等不到响应配对（{@link #onResponseForSpec} 对非 MONITOR 能力直接返回），却会被
     * {@link #drainSettledFailures()} 的超时扫描判成「监控超时」并<b>归因到场景失败</b>。
     *
     * <p>实测误报（2026-09-28）：MOCK 规则 {@code profile/list} 命中后，只要步骤继续存活超过
     * {@code monitorTimeoutMs}（默认 30s），就稳定产出
     * {@code [timeout] POST …/profile/list (pattern=profile/list, expect=null, timeoutMs=30000)}，
     * 把一个完全正常的 mock 请求变成场景失败。另外该队列在生产路径无消费者（{@code drain()} 仅测试调用），
     * 无条件记录还会随流量无界增长。
     *
     * @return true 表示该请求已进入「等待响应」索引——调用方应据此启动响应轮询；
     *         false 表示无需响应（未开启记录 / 非 MONITOR 能力 / 无任何响应侧期望 / auto-stop 已达标）
     */
    public boolean recordRequest(Request request, ApiSpec spec) {
        if (!spec.recordEnabled()) {
            return false; // record(false)：不记录请求/响应信息（对齐现有 RouteDsl.record）
        }
        // 能力与期望双重闸门：只有「MONITOR + 至少一条响应侧期望」才值得观测（见方法注释）
        if (spec.capability() != RouteCapability.MONITOR
                || (spec.expectStatus() == null && !spec.hasBodyAssertions())) {
            return false;
        }
        if (spec.autoStopOnMatch()) {
            AtomicInteger counter = hitCounts.computeIfAbsent(spec, k -> new AtomicInteger());
            if (counter.incrementAndGet() > spec.minMatches()) {
                // 已达最小匹配次数：停止后续记录与断言（对齐老版 auto-stop 的近似语义；
                // 旧实现为 unroute，当前为跳过处理——路由仍注册，重新命中不影响）
                return false;
            }
        }
        CapturedExchange exchange = CapturedExchange.ofRequest(request, spec);
        exchange.holdSpec(spec); // 延迟断言需要 spec（业务线程结算时消费期望与 body 断言配置）
        track(exchange);
        pendingByPattern.computeIfAbsent(spec.pattern(), k -> new ConcurrentLinkedQueue<>()).add(exchange);
        return true;
    }

    /**
     * 入队观测条目（带<b>上界</b>）：超限时先丢最旧，并把被丢的那条从「等待响应」索引一并移除。
     *
     * <p>先腾位置再入队（而非入队后裁剪），保证<b>刚记录的这条一定留得下</b> —— 否则在并发下
     * 可能把当前请求自己裁掉，导致调用方拿到 {@code true}（承诺会观测）却查不到条目。</p>
     *
     * <p>线程安全：{@link ConcurrentLinkedQueue} 弱一致遍历/poll 在多生产者下可能少丢或多丢一条，
     * 但"上界"语义与可观测计数不受影响（与 {@code CaptureSink.maxCaptured} 同款取舍）。</p>
     */
    private void track(CapturedExchange exchange) {
        if (exchanges.size() >= maxTrackedExchanges) {
            CapturedExchange oldest = exchanges.poll();
            if (oldest != null) {
                removeFromPending(oldest);
                long dropped = droppedExchanges.incrementAndGet();
                if (dropped == 1) {
                    LOGGER.warn("[Route] monitor observation queue reached its cap of {} entries -- dropping the oldest"
                            + " from now on (see droppedExchanges()); narrow the monitored patterns or raise the cap",
                            maxTrackedExchanges);
                }
            }
        }
        exchanges.add(exchange);
    }

    /**
     * 路由通道响应定案（按 spec 精确配对）——取代原 {@code context.onResponse} 事件订阅。
     *
     * <p><b>调用方</b>：{@code RouteRuntimeImpl} 在 route 分发入口拿到 {@link Request} 后，
     * 于调度线程轮询 {@link Request#existingResponse()}（请求本地字段、零协议往返）命中即投递。
     * 与请求侧 {@link #recordRequest} 使用同一 {@link ApiSpec} 实例，配对关系由调用方保证，
     * 故这里<b>不再做 URL 匹配</b>——也就<b>不再需要</b> Playwright 响应事件订阅
     * （订阅是驱动侧 {@code Object doesn't exist: response@…} 竞态的唯一触发源，见类注释）。
     *
     * <p>断言策略：
     * <ul>
     *   <li>无 body 断言 → 调度线程直接 volatile 写 status 断言（零阻塞）；</li>
     *   <li>有 body 断言（jsonPath / bodyContains / bodyRegex / formField）→ 先写 status，
     *       再在本线程（句柄存活期）<b>就地</b>快照 body（{@link #takeBodySnapshot}），
     *       并就地把「内容类型感知断言（{@link PayloadAssertor}）」同步执行（纯 CPU 解析，
     *       零等待、不可能因队列满被丢弃）；status 与断言结果一并写入证据。</li>
     * </ul>
     *
     * @param spec     该请求在 dispatch 时命中的规则（与 recordRequest 同一实例）
     * @param response 已到达的真实响应（本地持有）；null 安全（忽略）
     */
    public void onResponseForSpec(ApiSpec spec, Request request, Response response) {
        if (spec == null || response == null) {
            return;
        }
        if (spec.capability() != RouteCapability.MONITOR
                || (spec.expectStatus() == null && !spec.hasBodyAssertions())) {
            return;
        }
        ConcurrentLinkedQueue<CapturedExchange> pending = pendingByPattern.get(spec.pattern());
        if (pending == null) {
            return;
        }
        CapturedExchange exchange = matchByRequest(pending, request);
        if (exchange == null) {
            return; // 无配对请求快照（已被 drain 定案 / auto-stop 未记录 / 身份不匹配）→ 忽略
        }
        exchange.markResponse(response.status(), response.headers());
        // 结果证据要能展示响应体：即便本规则没有 body 断言，也在句柄存活时读一份前缀
        // （流式端点跳过 —— 见 takeBodySnapshot 的说明）。开关见 READ_BODY_FOR_EVIDENCE。

        //  T2+ 目的驱动撤销（"规则随目的生灭"）：响应已到达 ⇒ "断言这次调用"这一目的已达成，
        //  通知 runtime 撤销该规则（armed 窗口从"整个用例"收敛为"首个响应"；断言成败不影响是否撤销）。
        //  安全性：body 快照在紧接的下方**同步**读取、断言在 IO 线程完成，均不依赖"规则仍 armed"，
        //  故撤销不会丢证据（由 MonitorSinkPurposeRetirementTest 的失败用例钉住该语义）。
        firePurposeMet(spec);
        //  (ii) 线程边界（2026-10-11 定稿）：**轮询线程零协议调用** —— body() 是协议调用，
        //  从本轮起**不在响应轮询线程读取**（历史实测：轮询线程读 body 与 main 并发泵 Connection
        //  ⇒ HashMap 竞态 ⇒ Object doesn't exist ⇒ 级联污染后续场景；run5 实证）。
        //  status/headers 是事件 payload 本地字段（零协议往返），照常在本线程快照；
        //  body 由业务线程（main，串行安全）在 drain/结算时延迟读取（见 settlePendingBodyAssertions）。
        String deferredContentType = null;
        try {
            deferredContentType = response.headers().get("content-type");
        } catch (Throwable ignored) {
            LOGGER.trace("[Route] monitor response.headers() defensive fallback: {}", DriverErrors.shortMessage(ignored));
        }
        if (isStreamingContentType(deferredContentType) && spec.hasBodyAssertions()) {
            // 流式端点：体不读 ⇒ body 断言无法判定（inconclusive，绝不判失败），status 断言照常结算
            exchange.markBodyAssertionInconclusive("streaming response body not read (text/event-stream)");
            inconclusiveBodyReads.incrementAndGet();
            LOGGER.warn("[Route] monitor body assertion skipped for streaming response pattern='{}' url='{}'",
                    spec.pattern(), exchange.url());
            finishWithoutBodyAssertion(exchange);
            return;
        }
        if (!spec.hasBodyAssertions()) {
            // 无 body 断言：响应到达即定案。失败走 settleFailure（失败队列 + 结果上报）；
            // 成功也上报结果 —— 报告要能回答"成功没"，只在失败时可见是答不出来的。
            // 非流式：持有句柄，main 线程在失败结算时补读 body 供报告展示（流式不持有，避免阻塞）。
            if (!isStreamingContentType(deferredContentType)) {
                exchange.holdResponse(response);
            }
            finishWithoutBodyAssertion(exchange);
            return;
        }
        // 有 body 断言：轮询线程【不读 body、不断言】——持有句柄，延迟到业务线程结算
        // （读 body 的协议调用落在 main 线程，与 UI/断言串行，无并发泵 ⇒ 无竞态）。
        exchange.holdResponse(response);
        exchange.markBodyAssertionPending();
    }

    /**
     * 响应侧投递（向后兼容 / 防御性兜底：不传请求身份时退化为 FIFO 队首配对）。
     * 新调用方应使用 {@link #onResponseForSpec(ApiSpec, Request, Response)} 以按请求身份精确配对。
     */
    public void onResponseForSpec(ApiSpec spec, Response response) {
        onResponseForSpec(spec, null, response);
    }

    /**
     * 在 {@code pending} 队列中按<b>请求身份</b>精确取出交换；{@code request == null} 时退化为 FIFO {@code poll()}。
     *
     * <p>身份判定：遍历队列寻找 {@code CapturedExchange#request()} 引用与 {@code request} 相等者，
     * 用 {@link ConcurrentLinkedQueue#remove(Object)}（默认按对象身份）摘除同一实例；
     * 若已被 drain 并发移除（返回 false）则回退 null，本次投递跳过——绝不误结算他人交换。</p>
     */
    private CapturedExchange matchByRequest(ConcurrentLinkedQueue<CapturedExchange> pending, Request request) {
        if (request == null) {
            return pending.poll();
        }
        CapturedExchange matched = null;
        for (CapturedExchange e : pending) {
            if (e.request() != null && e.request() == request) {
                matched = e;
                break;
            }
        }
        if (matched == null) {
            return null; // 响应早于记录到达（理论不应发生）→ 跳过，交由超时定案
        }
        return pending.remove(matched) ? matched : null;
    }
    /**
     * 是否为"体不会结束"的流式响应（SSE 等）：这类端点调 {@code response.body()} 会一直挂到流关闭，
     * 等于把观测线程钉死，故<b>刻意不读体</b>（只按 status 定案，并在证据里注明原因）。
     */
    private static boolean isStreamingContentType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
    }

    /**
     * 证据是否读取响应体（默认 true）：报告要展示 API 的响应体，故即便规则没有 body 断言也读一份前缀。
     * 关闭方式：{@code -Droute.evidence.readResponseBody=false}（大量请求 + 大体积响应时可显著省开销）。
     */
    private static final boolean READ_BODY_FOR_EVIDENCE =
            !"false".equalsIgnoreCase(System.getProperty("route.evidence.readResponseBody", "true"));

    /** 证据展示的响应体前缀上限（字符；脱敏与最终截断仍由 {@code CapturedExchange} 完成）。 */
    private static final int PREVIEW_MAX_CHARS = 8192;

    /** 即时快照响应体与头；任何读取失败都归入 inconclusive（绝不向上抛）。 */
    private static BodySnapshot takeBodySnapshot(Response response) {
        List<String> names;
        String contentType = null;
        try {
            names = headerNames(response.headers());
            contentType = response.headers().get("content-type");
        } catch (Throwable t) {
            names = List.of("<header-read-failed>");
        }
        // P0-2：3xx 重定向响应体不可用（官方语义），显式短路、不发 body() 协议调用
        // —— 避免无谓驱动往返 + 把"正常 3xx"误计为 inconclusive；location 等头已由 markResponse 保留。
        int status = -1;
        try {
            status = response.status();
        } catch (Throwable ignored) {
            LOGGER.trace("[Route] monitor response.status() defensive fallback: {}",
                    DriverErrors.shortMessage(ignored));
        }
        if (status >= 300 && status < 400) {
            return new BodySnapshot(null, names, contentType,
                    "redirect response (status=" + status + "): body not available", null, false);
        }
        if (isStreamingContentType(contentType)) {
            return new BodySnapshot(null, names, contentType, null, null, true);
        }
        if (!READ_BODY_FOR_EVIDENCE) {
            return new BodySnapshot(null, names, contentType, null, null, false);
        }
        try {
            byte[] bytes = response.body();
            return new BodySnapshot(bytes, names, contentType, null, preview(bytes, contentType), false);
        } catch (Throwable t) {
            // P0-3：区分"句柄已被回收（框架/驱动竞态）"与"真协议错误"，供子计数分类
            String reason = (DriverErrors.isHandleReclaimed(t) ? "handle-reclaimed: " : "body-read-protocol-error: ")
                    + DriverErrors.shortMessage(t);
            return new BodySnapshot(null, names, contentType, reason, null, false);
        }
    }

    /** 体前缀（按字节多读一点再按字符截断，避免多字节字符被切在半路后长度失真）。 */
    private static String preview(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        int limit = Math.min(bytes.length, PREVIEW_MAX_CHARS * 2);
        String text = new String(bytes, 0, limit, MediaType.parse(contentType).charset());
        return text.length() > PREVIEW_MAX_CHARS ? text.substring(0, PREVIEW_MAX_CHARS) : text;
    }

    /** 无 body 断言（或体被跳过）时的定案：status 不符即结算失败，否则上报结果。 */
    private void finishWithoutBodyAssertion(CapturedExchange exchange) {
        if (Boolean.FALSE.equals(exchange.assertionPassed())) {
            settleFailure(exchange);
        } else {
            reportOutcome(exchange);
        }
    }

    /**
     * 就地同步执行 body 断言（响应轮询线程；2026-10-10 由"投递 IO 线程"改为同步）。
     *
     * <p><b>为什么同步</b>：快照（{@link BodySnapshot}）已在本线程取走，断言只消费快照——
     * 解码 + {@link PayloadAssertor} 是纯 CPU（微秒级），无任何句柄/协议依赖。异步投递的
     * 历史依据（读 body 曾是协议调用）已随 2026-09-28 就地快照修复消失；投 IO 反而带来
     * IO 队列满时<b>静默丢断言</b>（{@code rejectedBodyAssertions} 假绿风险）与结算延迟。
     * 同步后：断言命中即定案，失败立即入失败队列 → 步骤结束 seam 即可判红（"立即报错"）。
     *
     * <p>异常绝不逃逸：断言/解码失败记入失败明细（fail-open），不阻塞调度线程、不影响其它观测。
     */
    private void assertBodyInPlace(CapturedExchange exchange, ApiSpec spec, BodySnapshot snapshot) {
        if (exchange.responseTimedOut()) {
            return; // 已被 drain 标记超时，断言结果无意义
        }
        // 【断言体上限（P0，2026-10-11）】：超过上限的 body 不再整体物化 + JSONPath/包含/正则解析
        // （几十 MB base64 / 超长 JSON 的堆峰值与无意义解析）。语义与 streaming / handle-unavailable 同款：
        // inconclusive（不误判失败、不假绿、可观测计数），status/headers 断言照常结算。
        // 与采集留存无关：capture 侧整存不截断（BodyStore 三档），业务仍可取原始字节。
        if (snapshot.bytes() != null && snapshot.bytes().length > assertBodyMaxBytes) {
            exchange.markBodyAssertionInconclusive("body-assert-skipped: too-large ("
                    + snapshot.bytes().length + " bytes) > max " + assertBodyMaxBytes);
            skippedBodyAssertions.incrementAndGet();
            LOGGER.warn("[Route] monitor body assertion SKIPPED (over body assert limit) "
                            + "pattern='{}' url='{}' bytes={} max={} — degraded to status/headers only",
                    spec.pattern(), exchange.url(), snapshot.bytes().length, assertBodyMaxBytes);
            finishWithoutBodyAssertion(exchange);
            return;
        }
        try {
            String contentType = snapshot.contentType();
            Charset charset = MediaType.parse(contentType).charset();
            String body = new String(snapshot.bytes(), charset);
            List<String> failures = new ArrayList<>(PayloadAssertor.assertAll(spec, body, contentType));
            if (contentType == null && !failures.isEmpty()) {
                // content-type 缺失时补「响应形态」诊断：一次运行即可区分
                // 「服务端确实没发 Content-Type」与「空体/异常响应（如会话失效的空 200）」。
                // 只上报 header 名与字节数，绝不上报 header 值/响应体（防 session/凭据泄露）。
                failures.add("response diagnosis: headerNames=" + snapshot.headerNames()
                        + ", bodyBytes=" + snapshot.bytes().length);
            }
            exchange.markBodyAssertionFailures(failures);
            // body 断言（含 status 重算）定案：失败立即结算上报，成功同样上报结果（报告需要 PASS 可见）
            if (Boolean.FALSE.equals(exchange.assertionPassed())) {
                settleFailure(exchange);
            } else {
                reportOutcome(exchange);
            }
        } catch (Exception e) {
            // 断言/解码失败：记为失败明细（不允许异常逃逸调度线程）
            exchange.markBodyAssertionFailures(List.of("body-assert-error: " + e.getMessage()));
            settleFailure(exchange);
        }
    }

    /**
     * 响应 header <b>名</b>清单（小写、去重、字典序）；<b>只暴露名字，不暴露值</b>——
     * 用于 content-type 缺失时的形态诊断（如仅含 date/content-length 说明服务端确实没发该头）。
     */
    private static List<String> headerNames(Map<String, String> headers) {
        try {
            Set<String> names = new TreeSet<>();
            for (String name : headers.keySet()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
            return new ArrayList<>(names);
        } catch (Exception e) {
            return List.of("<header-read-failed>");
        }
    }

    /**
     * <b>业务线程延迟结算 body 断言</b>（2026-10-11 定稿：轮询线程零协议调用）。
     *
     * <p>响应到达时（轮询线程）只快照 status/headers（本地字段）并<b>持有句柄</b>；
     * body 读取（{@code Response.body()} 是协议调用）与断言延迟到<b>业务线程</b>
     * （main，与 UI/断言串行 ⇒ 无并发泵 Connection ⇒ 无竞态）在此结算。</p>
     *
     * <p>幂等：{@code markBodyAssertionSettled} 先行标记，drain 多轮/多入口不重复断言；
     * 句柄不可用（页面跳转/驱动回收）→ inconclusive（绝不判失败，与历史误报防线同源）。</p>
     *
     * <p>调用点：{@link #drain()} 与 {@link #drainSettledFailures()}（均为业务线程消费入口）。</p>
     */
    private void settlePendingBodyAssertions() {
        for (CapturedExchange exchange : exchanges) {
            if (!exchange.isBodyAssertionPending() || exchange.isBodyAssertionSettled()) {
                continue;
            }
            settleBodyAssertionNow(exchange);
        }
    }

    /**
     * <b>到达即读</b>（业务线程 main drain 收尾调用）：对"有 body 断言、响应已到达、未结算"的 exchange，
     * 就地读 body 成快照存入 exchange 并释放句柄——body 断言随后在 {@link #settleBodyAssertionNow}
     * 消费快照而非活句柄，消除"持有句柄→延迟读取"的驱逐窗口（对齐《网络API分层指南》"立即读、自己存"）。
     *
     * <p>与本类既有约定一致：<b>只读 body（{@link #takeBodySnapshot}），不在本方法跑断言</b>；
     * body() 是协议调用，故必须由业务线程调用（{@code RouteRuntimeImpl.drainMainThreadActions} 收尾）。
     * 幂等：已结算 / 已预取 / 非 body 断言 pending 的 exchange 一律跳过；句柄为空则留给结算回退判 inconclusive。
     * 3xx / 流式 / 读取失败由 {@code takeBodySnapshot} 归一为快照内的结论，计数仍在结算处发生（不重复计）。</p>
     */
    public void materializeArrivedBodies() {
        for (CapturedExchange exchange : exchanges) {
            if (!exchange.isBodyAssertionPending() || exchange.isBodyAssertionSettled()) {
                continue;
            }
            if (exchange.prefetchedBody() != null) {
                continue; // 已预取：幂等，避免重复读 body
            }
            Response held = exchange.takeHeldResponse();
            if (held == null) {
                continue; // 句柄已不在：留给 settleBodyAssertionNow 回退判 inconclusive
            }
            exchange.prefetchBody(takeBodySnapshot(held));
        }
    }

    /** 单条延迟结算（业务线程）：读 body → 快照 → 断言 → 结算/上报。异常绝不逃逸。 */
    private void settleBodyAssertionNow(CapturedExchange exchange) {
        exchange.markBodyAssertionSettled(); // 幂等：先标记，任何异常也不重复结算
        ApiSpec spec = exchange.spec();
        if (spec == null || exchange.responseStatus() == null || exchange.responseTimedOut()) {
            return; // 无规则 / 响应未到 / 已超时：不结算（超时由 drain 扫描路径处理）
        }
        // 到达即读：优先消费预取快照（drain 收尾已读入 body、句柄已释放）；无预取则回退现读持有句柄
        // （首轮 dispatch 线程交付未及预取等边缘路径——行为与历史一致，绝不因此漏判）。
        BodySnapshot snapshot = exchange.prefetchedBody();
        if (snapshot == null) {
            Response held = exchange.takeHeldResponse();
            if (held == null) {
                // 句柄已不可用（页面跳转 / 驱动回收）：body 无法判定 → inconclusive，绝不判失败
                String reason = "handle-reclaimed: response handle reclaimed (deferred read)";
                exchange.markBodyAssertionInconclusive(reason);
                inconclusiveBodyReads.incrementAndGet();
                handleReclaimedBodyReads.incrementAndGet();
                LOGGER.warn("[Route] monitor body assertion INCONCLUSIVE (response handle unavailable) "
                                + "pattern='{}' url='{}': {}",
                        spec.pattern(), exchange.url(), reason);
                reportOutcome(exchange); // 结果进报告（标注"未判定"，与"通过/失败"区分开）
                return;
            }
            snapshot = takeBodySnapshot(held);
        }
        if (snapshot.preview() != null) {
            exchange.markResponseBody(snapshot.preview()); // 结果证据展示用（内部再脱敏 + 截断）
        }
        if (snapshot.bodySkipped()) {
            // 流式端点：体不读 ⇒ body 断言无法判定（inconclusive，绝不判失败），status 断言照常结算
            exchange.markBodyAssertionInconclusive("streaming response body not read (text/event-stream)");
            inconclusiveBodyReads.incrementAndGet();
            LOGGER.warn("[Route] monitor body assertion skipped for streaming response pattern='{}' url='{}'",
                    spec.pattern(), exchange.url());
            finishWithoutBodyAssertion(exchange);
            return;
        }
        if (snapshot.inconclusiveReason() != null) {
            // 误报防线：句柄已回收 / 3xx 重定向 属框架/驱动竞态或已知语义（非应用缺陷）→ inconclusive，绝不判失败
            String reason = snapshot.inconclusiveReason();
            if (reason.startsWith("redirect response")) {
                // 3xx 重定向：响应体本就不可用，单独计数（不计入 inconclusive 竞态桶）
                redirectNotAvailableBodyReads.incrementAndGet();
            } else {
                inconclusiveBodyReads.incrementAndGet();
                if (reason.startsWith("handle-reclaimed")) {
                    handleReclaimedBodyReads.incrementAndGet();
                } else {
                    protocolErrorBodyReads.incrementAndGet();
                }
            }
            exchange.markBodyAssertionInconclusive(reason);
            LOGGER.warn("[Route] monitor body assertion INCONCLUSIVE (response handle unavailable) "
                            + "pattern='{}' url='{}': {}",
                    spec.pattern(), exchange.url(), reason);
            reportOutcome(exchange);
            return;
        }
        assertBodyInPlace(exchange, spec, snapshot);
    }

    /**
     * 消费全部观测记录（含未收到响应的超时标记）。
     *
     * <p>超时定案与 {@link #drainSettledFailures()} 走同一结算路径
     * （{@code settleFailure} + CAS 去重）：先 drain() 再 drainSettledFailures() 也不会
     * 漏掉超时失败（drain 内 markTimedOut 后即结算入队）。
     *
     * @return 从最早到最新的全部快照
     */
    public List<CapturedExchange> drain() {
        settlePendingBodyAssertions(); // 业务线程：延迟结算 body 断言（读 body 在 main，串行安全）
        List<CapturedExchange> result = new ArrayList<>();
        long now = System.nanoTime();
        CapturedExchange exchange;
        while ((exchange = exchanges.poll()) != null) {
            if (exchange.responseStatus() == null && !exchange.responseTimedOut()) {
                long timeoutMs = exchange.monitorTimeoutMs();
                if (timeoutMs != 0 && now - requestTimeNanos(exchange) > timeoutMs * 1_000_000L) {
                    exchange.markTimedOut();
                    settleFailure(exchange);
                    removeFromPending(exchange);
                }
            }
            result.add(exchange);
        }
        return result;
    }

    /** 当前在途观测数（含 pending）。 */
    public int size() {
        return exchanges.size();
    }

    /**
     * 因超出观测队列上限被丢弃的条目数（fail-open 指标；上限见 {@link #DEFAULT_MAX_TRACKED_EXCHANGES}）。
     *
     * <p>{@code > 0} 说明该上下文"话痨"到超出留存能力，最旧的观测快照会缺失 ——
     * <b>断言结算不受影响</b>（结算走响应配对路径，不依赖本队列），
     * 丢的只是快照留存与超时扫描的可见性。</p>
     */
    public long droppedExchanges() {
        return droppedExchanges.get();
    }

    /** body 断言因响应句柄不可用而未能判定的次数（inconclusive；可观测，不判失败）。 */
    public long inconclusiveBodyReads() {
        return inconclusiveBodyReads.get();
    }

    /** 3xx 重定向响应体不可用的次数（P0-2 / P0-3）：已知事实，单独计数，不计入 {@link #inconclusiveBodyReads()}。 */
    public long redirectNotAvailableBodyReads() {
        return redirectNotAvailableBodyReads.get();
    }

    /** body 断言因响应句柄<b>已被驱动回收</b>而未能判定的次数（P0-3，框架/驱动竞态）。 */
    public long handleReclaimedBodyReads() {
        return handleReclaimedBodyReads.get();
    }

    /** body 断言因<b>真协议错误</b>（非句柄回收）而未能判定的次数（P0-3）。 */
    public long protocolErrorBodyReads() {
        return protocolErrorBodyReads.get();
    }

    /** body 断言因<b>超出断言体上限</b>而被跳过的次数（inconclusive；与采集留存无关，capture 侧仍整存）。 */
    public long skippedBodyAssertions() {
        return skippedBodyAssertions.get();
    }

    /**
     * 结算一条断言失败的交换（幂等：每条失败只入队一次）。
     *
     * <p>调用方：响应轮询线程（status / body 断言定案）、{@link #drainSettledFailures()}（超时定案）。
     * CAS 保证多路径并发下不重复上报。
     */
    private void settleFailure(CapturedExchange exchange) {
        if (exchange.tryMarkSettled()) {
            // 失败结算时补读响应体供报告展示（status-only 规则无 body 断言路径，
            // 轮询线程已 holdResponse 但未读 body；此时在 main 线程串行安全读取）。
            if (exchange.responseBodyPreview() == null) {
                try {
                    Response held = exchange.takeHeldResponse();
                    if (held != null && !isStreamingContentType(held.headers().get("content-type"))) {
                        BodySnapshot snap = takeBodySnapshot(held);
                        if (snap.preview() != null) {
                            exchange.markResponseBody(snap.preview());
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.debug("[Route] failed to read body for failure evidence: {}", DriverErrors.shortMessage(t));
                }
            }
            settledFailures.add(exchange);
            reportOutcome(exchange); // 失败结果进报告（CAS 保证只上报一次）
        }
    }

    /**
     * 上报一次观测<b>结果</b>（成功 / 失败 / 超时 / 未判定都上报）。
     *
     * <p><b>为什么成功也要上报</b>：报告里原先只有命中时刻写下的"规则声明 + 请求"
     * （expectStatus=200 timeout=60s…），读者无法回答"到底成功没、实际拿到什么"。
     * 定案时刻补一条结果证据（实际 status / 期望 status / PASS-FAIL-TIMEOUT / 耗时 / 失败明细），
     * 与 CAPTURE 的 {@code CapturedApiCall.detail()} 同款三段式。</p>
     *
     * <p>旁路语义：上报异常绝不影响断言链路（只 DEBUG）；URL/明细在 {@code resultDetail()} 内已脱敏。</p>
     */
    private void reportOutcome(CapturedExchange exchange) {
        try {
            RouteEvidenceRegistry.record("MONITOR RESULT", exchange.url(), exchange.resultDetail());
        } catch (Throwable t) {
            LOGGER.debug("[Route] monitor outcome report skipped (non-fatal): {}", DriverErrors.shortMessage(t));
        }
    }

    /**
     * 取走全部已定案且断言失败的交换（消费式；幂等——第二次调用返回空列表）。
     *
     * <p>执行两件事：
     * <ol>
     *   <li><b>超时扫描</b>：对仍在等待响应、且超过 monitor 超时窗口的请求做超时定案
     *       （{@code markTimedOut} + 结算），保证"请求发出但迟迟无响应"也会被上报；</li>
     *   <li><b>消费失败队列</b>：把已结算的失败交换一次性返回并清空。</li>
     * </ol>
     *
     * <p>线程安全：遍历 {@link ConcurrentLinkedQueue} 弱一致（超时定案可能滞后一拍，可接受）；
     * 消费队列无锁。未定案的请求（有期望但响应未到、未超时）保留在观测队列继续等待，
     * 不会被本方法误取，符合 timeout 窗口语义。
     *
     * @return 已定案失败交换快照（按结算顺序）；无失败时为空列表（永不返回 null）
     */
    public List<CapturedExchange> drainSettledFailures() {
        settlePendingBodyAssertions(); // 业务线程：延迟结算 body 断言（读 body 在 main，串行安全）
        long now = System.nanoTime();
        for (CapturedExchange exchange : exchanges) {
            if (exchange.responseStatus() == null && !exchange.responseTimedOut()) {
                long timeoutMs = exchange.monitorTimeoutMs();
                if (timeoutMs != 0 && now - exchange.recordedAtNanos() > timeoutMs * 1_000_000L) {
                    exchange.markTimedOut();
                    settleFailure(exchange);
                    removeFromPending(exchange);
                }
            }
        }
        List<CapturedExchange> result = new ArrayList<>();
        CapturedExchange exchange;
        while ((exchange = settledFailures.poll()) != null) {
            // 失败补读 body（main 线程协议调用，安全）：无 body 断言的规则也持有句柄，
            // 失败时读一份预览供报告展示；成功路径不取句柄（不读、不阻塞）。
            if (exchange.responseBodyPreview() == null) {
                Response held = exchange.takeHeldResponse();
                if (held != null) {
                    try {
                        BodySnapshot snap = takeBodySnapshot(held);
                        if (snap.preview() != null) {
                            exchange.markResponseBody(snap.preview());
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("[Route] monitor failure body read failed (non-fatal): {}", DriverErrors.shortMessage(t));
                    }
                }
            }
            result.add(exchange);
        }
        return result;
    }

    /** 当前已结算未消费的失败数（可观测性；消费后归零）。 */
    public int settledFailureCount() {
        return settledFailures.size();
    }

    private long requestTimeNanos(CapturedExchange exchange) {
        return exchange.recordedAtNanos();
    }

    private void removeFromPending(CapturedExchange exchange) {
        ConcurrentLinkedQueue<CapturedExchange> pending = pendingByPattern.get(exchange.pattern());
        if (pending != null) {
            pending.remove(exchange);
        }
    }
}

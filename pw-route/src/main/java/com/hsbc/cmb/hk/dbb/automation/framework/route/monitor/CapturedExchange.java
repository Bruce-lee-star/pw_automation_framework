package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import com.hsbc.cmb.hk.dbb.automation.framework.route.diag.RequestOrigin;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.microsoft.playwright.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次被观测请求的快照（含响应期望与断言结果）。
 *
 * <p>并发安全：请求侧字段在构造时固定（不可变）；响应侧字段由 response 事件线程
 * 通过 {@code volatile} 单次写入，drain 线程读取——volatile 写读保证可见性，无撕裂读。
 */
public final class CapturedExchange {

    private static final int POST_DATA_PREVIEW_MAX = 2048;

    /** 结果证据里的响应体预览上限（已脱敏后再截断）。 */
    private static final int RESPONSE_BODY_PREVIEW_MAX = 4096;

    private final String method;
    private final String url;
    private final String pattern;
    private final Map<String, String> requestHeaders;
    private final String postDataPreview;
    private final long requestTimeNanos;
    private final Integer expectStatus;
    private final long monitorTimeoutMs;
    /** 记录创建时刻（epoch millis，供 CAPTURE 快照的 recordedAt 使用）。 */
    private final long recordedAtMillis;
    /** 命中规则的能力渠道集合（MOCK/MODIFY/MONITOR/DELAY）——仅用于采集记录的分渠道标注。 */
    private final java.util.Set<String> channels;
    /** 命中规则的主渠道（优先级 MONITOR > DELAY > MODIFY > MOCK）；无渠道时为 null。 */
    private final String primaryChannel;

    private volatile Integer responseStatus;
    private volatile Map<String, String> responseHeaders;
    private volatile long responseTimeNanos;
    private volatile Boolean assertionPassed;
    private volatile boolean responseTimedOut;
    private volatile List<String> bodyAssertionFailures = Collections.emptyList();
    /** body 断言未能判定的原因（inconclusive；null=已判定或未配置）。绝不参与失败判定。 */
    private volatile String bodyAssertionInconclusive;
    /** 响应体预览（已脱敏 + 截断）。仅"有 body 断言"的规则读了体，其余刻意不读（见 resultDetail 的说明）。 */
    private volatile String responseBodyPreview;
    private volatile boolean responseBodyTruncated;
    /** 断言失败是否已结算（入失败队列）；CAS 保证每条失败只结算一次（防多路径重复上报）。 */
    private final AtomicBoolean settled = new AtomicBoolean(false);

    /**
     * 已到达的真实响应句柄（响应轮询线程写入、业务线程消费时读取 body）。
     * <b>绝不跨线程做协议调用</b>：body() 是协议调用，必须由业务线程（main，串行安全）在结算时读取。
     */
    private volatile Response heldResponse;
    /** body 断言是否待业务线程结算（轮询线程只写本地字段，不读 body）。 */
    private volatile boolean bodyAssertionPending;
    /** body 断言是否已在业务线程结算（幂等保护：drain 多轮/多入口不重复断言）。 */
    private volatile boolean bodyAssertionSettled;
    /**
     * 采集序号：由 {@link CaptureSink} 在<b>记录请求时</b>（{@code recordRequest}，即请求发起顺序）
     * 分配，供等待方表达"只要某个动作之后发起的请求"。
     *
     * <p><b>为什么必须在 recordRequest 而不是入队时分配</b>：响应体是业务线程延迟物化的，
     * 入队发生在之后。若按入队顺序编号，一条<b>动作前发起、动作后才完成</b>的请求会拿到更大的编号，
     * 从而仍满足门槛 —— 恰好复现要消灭的"过期命中"。按请求发起顺序编号才能把这类请求正确排除。
     *
     * <p>volatile：写发生在 route 事件线程、读发生在业务线程（入队/结算），需要跨线程可见。
     * 未分配的（如派发链证据路径）为 {@link CapturedApiCall#UNSEQUENCED}。
     */
    private volatile long captureSeq = CapturedApiCall.UNSEQUENCED;

    /**
     * 请求归属三元组（所属 Page / Frame / 主文档导航代次），由 {@link com.hsbc.cmb.hk.dbb.automation
     * .framework.route.diag.OriginResolver} 在记录请求时解析。
     * 默认 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.diag.RequestOrigin#UNKNOWN}。
     */
    private volatile RequestOrigin origin = RequestOrigin.UNKNOWN;

    /** 命中规则引用（业务线程延迟断言需要：PayloadAssertor 消费 spec 的期望与 body 断言配置）。 */
    private volatile ApiSpec heldSpec;

    /** 兼容构造（旧 8 参签名）：不携带渠道信息（channels 为空集、primaryChannel 为 null）。 */
    CapturedExchange(String method, String url, String pattern, Map<String, String> requestHeaders,
                     String postDataPreview, long requestTimeNanos, Integer expectStatus, long monitorTimeoutMs) {
        this(method, url, pattern, requestHeaders, postDataPreview, requestTimeNanos, expectStatus,
                monitorTimeoutMs, null, null);
    }

    CapturedExchange(String method, String url, String pattern, Map<String, String> requestHeaders,
                     String postDataPreview, long requestTimeNanos, Integer expectStatus, long monitorTimeoutMs,
                     java.util.Set<String> channels, String primaryChannel) {
        this.method = method;
        this.url = url;
        this.pattern = pattern;
        this.requestHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(requestHeaders));
        this.postDataPreview = postDataPreview;
        this.requestTimeNanos = requestTimeNanos;
        this.expectStatus = expectStatus;
        this.monitorTimeoutMs = monitorTimeoutMs;
        this.recordedAtMillis = System.currentTimeMillis();
        this.channels = channels == null ? java.util.Set.of()
                : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(channels));
        this.primaryChannel = primaryChannel;
    }

    /** 命中的能力渠道（不可变；静态分析要求不直接返回可变字段）。 */
    java.util.Set<String> channels() {
        return Collections.unmodifiableSet(channels);
    }

    /** 主渠道（优先级最高者）；无渠道时为 null。 */
    String primaryChannel() {
        return primaryChannel;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public String pattern() {
        return pattern;
    }

    public Map<String, String> requestHeaders() {
        return requestHeaders;
    }

    public String postDataPreview() {
        return postDataPreview;
    }

    public Integer expectStatus() {
        return expectStatus;
    }

    public long monitorTimeoutMs() {
        return monitorTimeoutMs;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public Map<String, String> responseHeaders() {
        return responseHeaders;
    }

    /** 记录创建时刻（epoch millis）。 */
    public long recordedAtMillis() {
        return recordedAtMillis;
    }

    /** 请求到响应的耗时（毫秒）；无响应时为 -1。 */
    public long durationMs() {
        return responseTimeNanos == 0 ? -1 : (responseTimeNanos - requestTimeNanos) / 1_000_000L;
    }

    /** 断言结果：null=未断言（无期望或响应未到达）；true/false=已断言。 */
    public Boolean assertionPassed() {
        return assertionPassed;
    }

    /** body 断言失败明细（jsonPath / bodyContains / bodyRegex / formField 的失败）；空列表 = 通过或未配置。 */
    public List<String> bodyAssertionFailures() {
        return bodyAssertionFailures;
    }

    /** 是否在 monitorTimeoutMs 内未等到响应。 */
    public boolean responseTimedOut() {
        return responseTimedOut;
    }

    // ── 仅 MonitorSink 调用（包内可见）──

    /** 持有已到达的真实响应句柄（轮询线程写入；业务线程 take 后读 body —— 单线程串行）。 */
    void holdResponse(Response response) {
        this.heldResponse = response;
    }

    /** 取走持有的响应句柄（业务线程调用；取走后本 exchange 不再持有句柄引用）。 */
    Response takeHeldResponse() {
        Response r = heldResponse;
        heldResponse = null;
        return r;
    }

    /** 标记 body 断言待业务线程结算（有 body 断言且响应已到达；轮询线程不读 body）。 */
    void markBodyAssertionPending() {
        this.bodyAssertionPending = true;
    }

    boolean isBodyAssertionPending() {
        return bodyAssertionPending;
    }

    /** 标记 body 断言已在业务线程结算（幂等：drain 多轮/多入口不重复断言）。 */
    void markBodyAssertionSettled() {
        this.bodyAssertionSettled = true;
    }

    boolean isBodyAssertionSettled() {
        return bodyAssertionSettled;
    }

    /** 保存命中规则引用（延迟断言时消费期望与 body 断言配置）。 */
    void holdSpec(ApiSpec spec) {
        this.heldSpec = spec;
    }

    ApiSpec spec() {
        return heldSpec;
    }

    /** 写入请求归属（一次性；重复写入保留首次，避免下游重入覆盖）。 */
    void holdOrigin(RequestOrigin resolved) {
        if (resolved != null && this.origin == RequestOrigin.UNKNOWN) {
            this.origin = resolved;
        }
    }

    /** 请求归属三元组；未解析时为 {@link RequestOrigin#UNKNOWN}。 */
    RequestOrigin origin() {
        return origin;
    }

    /**
     * 写入采集序号（由 {@link CaptureSink} 在 {@code recordRequest} 时调用一次）。
     *
     * @param seq 严格单调序号（&gt; 0）；重复写入以首次为准，避免下游重入把序号打回去
     */
    void assignCaptureSeq(long seq) {
        if (this.captureSeq == CapturedApiCall.UNSEQUENCED) {
            this.captureSeq = seq;
        }
    }

    /** 采集序号；未分配时为 {@link CapturedApiCall#UNSEQUENCED}。 */
    long captureSeq() {
        return captureSeq;
    }

    void markResponse(int status, Map<String, String> headers) {
        this.responseStatus = status;
        this.responseHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.responseTimeNanos = System.nanoTime();
        this.assertionPassed = expectStatus == null || expectStatus == status;
    }

    /**
     * 记录 body 断言结果（IO 线程调用，晚于事件线程的 markResponse）。
     * 合并后重算整体断言结果：status 与全部 body 断言同时通过才算通过。
     */
    void markBodyAssertionFailures(List<String> failures) {
        List<String> immutable = Collections.unmodifiableList(new ArrayList<>(failures));
        this.bodyAssertionFailures = immutable;
        // 注意：expectStatus 与 responseStatus 均为 Integer，必须用 Objects.equals（== 是引用比较）
        boolean statusOk = expectStatus == null || Objects.equals(expectStatus, responseStatus);
        this.assertionPassed = statusOk && immutable.isEmpty();
    }

    void markTimedOut() {
        this.responseTimedOut = true;
        this.assertionPassed = false;
    }

    /**
     * 标记 body 断言<b>未能判定</b>（inconclusive）：响应句柄不可用（驱动已回收 / 页面关闭）——
     * 属框架/驱动竞态，非应用缺陷。
     *
     * <p>语义：只按 status 定案（status 来自客户端本地快照，恒可读），<b>不</b>因 body 未判定而判失败；
     * 调用方（{@link MonitorSink}）也不得结算失败。这样"框架自身限制"不会把绿场景判红。</p>
     */
    void markBodyAssertionInconclusive(String reason) {
        this.bodyAssertionInconclusive = reason;
        boolean statusOk = expectStatus == null || Objects.equals(expectStatus, responseStatus);
        this.assertionPassed = statusOk;
    }

    /** body 断言未能判定的原因；null 表示已判定（或未配置 body 断言）。 */
    public String bodyAssertionInconclusiveReason() {
        return bodyAssertionInconclusive;
    }

    /** 响应体预览（已脱敏 + 截断）；无 body 断言或未读体时为 null。 */
    public String responseBodyPreview() {
        return responseBodyPreview;
    }

    /**
     * 记录响应体预览（{@link MonitorSink} 在断言前调用，此刻句柄仍存活）。
     * 内部先经 {@code sanitizeBody} 脱敏、再按上限截断 —— 存进来的永远是"可出域"的文本。
     */
    void markResponseBody(String body) {
        if (body == null) {
            return;
        }
        String sanitized = SensitiveDataSanitizer.sanitizeBody(body);
        if (sanitized == null) {
            return;
        }
        boolean truncated = sanitized.length() > RESPONSE_BODY_PREVIEW_MAX;
        this.responseBodyPreview = truncated
                ? sanitized.substring(0, RESPONSE_BODY_PREVIEW_MAX) + "...[truncated]"
                : sanitized;
        this.responseBodyTruncated = truncated;
    }

    /**
     * 命中<b>结果</b>的可读明细（供路由证据上报进 Serenity 报告）。
     *
     * <p><b>为什么需要它</b>：命中时刻上报的那条证据只是「规则声明 + 请求」（expectStatus=200 timeout=60s…），
     * 报告里看不到"实际拿到什么、断言过没过"。本方法在交换<b>定案</b>时渲染结果 ——
     * 与 CAPTURE 的 {@code CapturedApiCall.detail()} 同款三段式，便于在报告里并读。</p>
     *
     * <p>安全：URL 走 {@code sanitizeUrl}；失败明细与 inconclusive 原因走 {@code sanitizeFreeText}
     * （它们可能夹带响应体片段）。</p>
     */
    public String resultDetail() {
        // 与 CAPTURE 同款三段式：请求侧完整信息（方法/URL/头/体）+ 实际响应（状态/头/体）+ result 行 ——
        // 报告要能"一个块看清这次 API 调用发生了什么、成功没"。
        CapturedApiCall snapshot = CapturedApiCall.of(pattern, method, url, requestHeaders,
                postDataPreview, responseStatus, responseHeaders, responseBodyPreview,
                responseBodyTruncated, durationMs(), responseTimedOut());
        StringBuilder tail = new StringBuilder(220);
        tail.append("result    : ").append(resultLabel());
        if (responseStatus != null) {
            tail.append(" actual=").append(responseStatus);
        }
        if (expectStatus != null) {
            tail.append(" expect=").append(expectStatus);
        }
        if (durationMs() >= 0) {
            tail.append(" elapsed=").append(durationMs()).append("ms");
        }
        if (responseStatus == null && !responseTimedOut()) {
            tail.append(" (response not settled yet)");
        } else if (responseBodyPreview == null && bodyAssertionInconclusive == null) {
            // 刻意不读体（流式端点读体会挂住观测线程，或被开关关掉）——如实标注，而不是留空让人误以为"没有响应"
            tail.append('\n').append("respBody  : (not read — event-stream bodies are deliberately not read to keep")
                    .append(" the observer unblocked, or reading is disabled via")
                    .append(" -Droute.evidence.readResponseBody=false)");
        }
        for (String failure : bodyAssertionFailures) {
            tail.append('\n').append("failure   : ")
                    .append(SensitiveDataSanitizer.sanitizeFreeText(failure));
        }
        if (bodyAssertionInconclusive != null) {
            tail.append('\n').append("note      : body assertion inconclusive (response handle reclaimed; "
                            + "settled on status only): ")
                    .append(SensitiveDataSanitizer.sanitizeFreeText(bodyAssertionInconclusive));
        }
        return snapshot.detailWith(tail.toString());
    }

    /** 结果标签：TIMEOUT / PASS / FAIL / PENDING（尚未定案）。 */
    private String resultLabel() {
        if (responseTimedOut) {
            return "TIMEOUT";
        }
        Boolean passed = assertionPassed;
        if (passed == null) {
            return "PENDING";
        }
        return passed ? "PASS" : "FAIL";
    }

    /**
     * 幂等标记"断言失败已结算"（仅 MonitorSink 调用）。
     *
     * @return true=本次调用成功标记（此前未结算）；false=此前已结算（去重）
     */
    boolean tryMarkSettled() {
        return settled.compareAndSet(false, true);
    }

    /** 记录创建时刻（nanoTime，供超时判断）。 */
    long recordedAtNanos() {
        return requestTimeNanos;
    }

    /** 供 DSL / 断言使用：匹配 ApiSpec 的 monitor 期望。 */
    static CapturedExchange ofRequest(com.microsoft.playwright.Request request, ApiSpec spec) {
        String postData = request.postData();
        String preview = postData == null ? null
                : (postData.length() > POST_DATA_PREVIEW_MAX ? postData.substring(0, POST_DATA_PREVIEW_MAX) + "...[truncated]" : postData);
        // 分渠道标注：把命中规则的渠道集合与主渠道一次带进快照（后续 dump/peek 与 View 转换直接可用）。
        return new CapturedExchange(request.method(), request.url(), spec.pattern(),
                request.headers(), preview, System.nanoTime(), spec.expectStatus(), spec.monitorTimeoutMs(),
                spec.activeChannels(), spec.primaryChannel());
    }
}

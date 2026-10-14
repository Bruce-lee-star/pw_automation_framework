package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.ApiCaptureQuery;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.ApiCaptureQueryRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

import java.util.List;
import java.util.function.Predicate;

/**
 * 业务读取 API 采集信息的门面 —— 与 master 分支同名同调用形态：
 *
 * <pre>{@code
 * var calls = ApiCaptureContext.getCurrent().getAllApiCalls();
 * var last  = ApiCaptureContext.getCurrent().getLastApiCall("/api/login");
 * var mocks = ApiCaptureContext.getCurrent().getCallsByChannel("MOCK");
 * }</pre>
 *
 * <p><b>与 master 的关键差别（也是"并行不互相污染"的保证）</b>：master 的 {@code getCurrent()} 返回
 * <b>全局静态单例</b>，场景开始/收尾各自 reset/remove 同一实例，并行下会清掉并存用例的数据；
 * 本实现的 {@code getCurrent()} 只是门面，每次查询都按<b>当前线程的 BrowserContext</b>解析数据
 * （经 {@code PlaywrightManager.getContext()} 取上下文，再交给 pw-core 的 SPI），因此隔离维度 = Context，
 * 并行天然安全。
 *
 * <p>本类只依赖 pw-core 的 SPI 与同模块的 {@code PlaywrightManager}，<b>不依赖 pw-route</b>
 * （pw-web-ui 的模块边界）；pw-route 不在 classpath 时各查询方法安全返回空结果。
 */
public final class ApiCaptureContext {

    /** 无状态门面：单例即可（真正的数据按 Context 维度存放）。 */
    private static final ApiCaptureContext CURRENT = new ApiCaptureContext();

    private ApiCaptureContext() {
    }

    /** 取当前用例的采集门面（与 master 同调用形态）。 */
    public static ApiCaptureContext getCurrent() {
        return CURRENT;
    }

    /** SPI 实现；pw-route 不在 classpath 时为 null，各方法据此降级为空结果。 */
    private static ApiCaptureQuery query() {
        return ApiCaptureQueryRegistry.get();
    }

    /** 当前线程的 BrowserContext（可能为 null：未创建上下文时）。 */
    private static Object context() {
        return PlaywrightManager.getContext();
    }

    /** 全部采集记录。 */
    public List<CapturedApiCallView> getAllApiCalls() {
        ApiCaptureQuery q = query();
        return q == null ? List.of() : q.getAllApiCalls(context());
    }

    /** 每个 endpoint 的最近一次记录。 */
    public List<CapturedApiCallView> getAllLastApiCalls() {
        ApiCaptureQuery q = query();
        return q == null ? List.of() : q.getAllLastApiCalls(context());
    }

    /** 指定 endpoint 的最近一次记录；无则 null。 */
    public CapturedApiCallView getLastApiCall(String endpoint) {
        ApiCaptureQuery q = query();
        return q == null ? null : q.getLastApiCall(context(), endpoint);
    }

    /** 指定 URL 的全部记录。 */
    public List<CapturedApiCallView> getCallsByUrl(String url) {
        ApiCaptureQuery q = query();
        return q == null ? List.of() : q.getCallsByUrl(context(), url);
    }

    /** 指定渠道的全部记录（MOCK / MODIFY / MONITOR / DELAY，大小写不敏感）。 */
    public List<CapturedApiCallView> getCallsByChannel(String channel) {
        ApiCaptureQuery q = query();
        return q == null ? List.of() : q.getCallsByChannel(context(), channel);
    }

    /** 等待首个满足条件的记录；超时返回 null（轮询在实现层，非消费式）。 */
    public CapturedApiCallView waitForApi(Predicate<CapturedApiCallView> predicate, long timeoutMs) {
        ApiCaptureQuery q = query();
        return q == null ? null : q.waitForApi(context(), predicate, timeoutMs);
    }

    /**
     * 当前已分配的采集序号（= 已进入历史队列的记录数）；无实现时返回 -1。
     *
     * <p>推荐用法（消除"过期命中"）：触发业务动作<b>之前</b>取一次该值，动作之后调用
     * {@link #waitForApiSince(long, Predicate, long)} 并传入 {@code 序号 + 1}，
     * 即可只匹配本次动作之后新产生的采集记录。
     */
    public long capturedApiSequence() {
        ApiCaptureQuery q = query();
        return q == null ? -1L : q.capturedApiSequence(context());
    }

    /**
     * 等待首个满足条件的记录，且<b>只</b>匹配采集序号 &gt;= {@code minCaptureSeq} 的记录；超时返回 null。
     *
     * <p>为什么需要：历史是不区分导航代次的<b>只增队列</b>。点击 → 页面连发两次同一 URL、
     * 或连续两次导航时，上一轮的迟到响应会被新一次 {@code waitForApi} 命中，
     * 测试因此"通过"却根本没有验证本轮流量。用序号把等待限定在动作之后即可避免。
     */
    public CapturedApiCallView waitForApiSince(long minCaptureSeq, Predicate<CapturedApiCallView> predicate,
                                               long timeoutMs) {
        ApiCaptureQuery q = query();
        return q == null ? null : q.waitForApi(context(), predicate, timeoutMs, minCaptureSeq);
    }
}

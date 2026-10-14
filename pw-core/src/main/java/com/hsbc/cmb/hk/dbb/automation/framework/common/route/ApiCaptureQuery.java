package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.util.List;
import java.util.function.Predicate;

/**
 * API 信息采集查询 SPI。
 *
 * <p>由 pw-route 实现（{@code route.lifecycle.ApiCaptureQueryImpl}）并通过
 * {@link ApiCaptureQueryRegistry} 暴露给上层；pw-web-ui 的业务门面
 * （{@code framework.web.route.core.ApiCaptureContext}）只依赖本接口，从而保持
 * "pw-web-ui 不依赖 pw-route"的模块方向。
 *
 * <p><b>隔离维度</b>：所有方法首参为 {@code context}（实现内按 {@code BrowserContext} 解析）——
 * 采集数据按 BrowserContext 归属，故并行执行互不污染；用 {@code Object} 声明以避免 pw-core
 * 反向依赖 Playwright 类型（与既有 {@code EngineControl#stopContextEngine(Object)} 同风格）。
 *
 * <p>查询语义与既有 {@code RouteDsl.dumpCaptured / peekCaptured} 一致：{@code dump*} 为消费式，
 * 本接口的 {@code get*} 系列为<b>非消费式</b>（只读快照，不取走也不清空）。
 */
public interface ApiCaptureQuery {

    /** 全部采集记录（非消费式；按 endpoint/pattern 归组的视图由调用方自行聚合）。 */
    List<CapturedApiCallView> getAllApiCalls(Object context);

    /** 每个 endpoint 的最近一次采集记录。 */
    List<CapturedApiCallView> getAllLastApiCalls(Object context);

    /** 指定 endpoint（pattern 或 URL 前缀口径，由实现定义）的最近一次记录；无则 null。 */
    CapturedApiCallView getLastApiCall(Object context, String endpoint);

    /** 指定 URL 的全部采集记录。 */
    List<CapturedApiCallView> getCallsByUrl(Object context, String url);

    /**
     * 指定渠道的全部采集记录。
     *
     * @param channel {@code MOCK} / {@code MODIFY} / {@code MONITOR} / {@code DELAY}（大小写不敏感）
     */
    List<CapturedApiCallView> getCallsByChannel(Object context, String channel);

    /** 等待首个满足条件的记录（非消费式）；超时返回 null。 */
    CapturedApiCallView waitForApi(Object context, Predicate<CapturedApiCallView> predicate, long timeoutMs);

    /**
     * 当前已分配的采集序号（= 已进入历史队列的记录数）；实现不支持时返回 -1。
     *
     * <p>配合 {@link #waitForApi(Object, Predicate, long, long)} 使用：动作前取该值，
     * 把 {@code 值+1} 当门槛，即可只看本次动作之后新产生的采集。
     */
    default long capturedApiSequence(Object context) {
        return -1L;
    }

    /**
     * 等待首个满足条件的记录（非消费式），且只接受指定序号及之后的记录；超时返回 null。
     *
     * <p><b>为什么需要</b>：{@code get*}/{@code waitForApi} 扫描的是<b>只增历史</b>，不区分导航代次。
     * 连续导航 / 重复轮询同一 URL 时，上一轮的迟到响应会满足新一轮等待（"过期命中"）。
     * 用序号把等待限定在"动作之后产生的记录"，即可把这条失效模式挡掉。
     *
     * <p>默认实现退化为二参 {@code waitForApi}（不支持序号的实现不会因此报错，但也就没有上述保护）。
     */
    default CapturedApiCallView waitForApi(Object context, Predicate<CapturedApiCallView> predicate,
                                           long timeoutMs, long minCaptureSeq) {
        return waitForApi(context, predicate, timeoutMs);
    }

    /** 清空该 context 的采集数据（用例收尾清理；幂等）。 */
    void resetCapturedApis(Object context);
}

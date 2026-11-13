package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * API 采集记录的生命周期阶段（route captured body 状态分层）。
 *
 * <p>一次被采集的请求按下面顺序单向推进，<b>不回退</b>：
 * <pre>
 *   MATCHED            请求命中采集规则、已登记 pending，等待响应到达
 *        │  响应到达（status/headers 已快照，响应句柄持有）
 *        ▼
 *   ARRIVED            响应已到，body 尚未在业务线程物化（body() 是协议调用）
 *        │  业务线程 drain / 采集查询入口完成 body 读取与留存
 *        ▼
 *   BODY_MATERIALIZED  body 已按采集策略完成处理并留存，记录对业务可见
 * </pre>
 *
 * <p><b>未推进到终态的定案记录</b>：超时停在 {@code MATCHED}；3xx / 流式 / 句柄被回收 /
 * 协议读取失败停在 {@code ARRIVED}。这些记录仍进入日志与路由证据（含
 * {@code bodyUnavailableReason} 说明原因），但<b>不进入业务只读查询</b>——业务拿不到
 * 响应体时不应看到一条"看似完整"的记录，避免断言误判。
 *
 * <p><b>{@code captureBody(false)} 的归属</b>：规则显式声明不需要体，属于"按配置完成物化流程"，
 * 推进到 {@code BODY_MATERIALIZED}（记录的 {@code responseBody} 为 null 是预期语义），
 * 否则该规则会整体从业务查询中消失，破坏既有行为。
 *
 * <p>本类型定义在 pw-core：pw-route 负责推进阶段，pw-web-ui 的业务只读视图
 * （{@link CapturedApiCallView}）携带它，保持"pw-web-ui 不依赖 pw-route"的模块方向。
 */
public enum CaptureStage {

    /** 请求已命中采集规则并登记，尚未等到响应。 */
    MATCHED,

    /** 响应已到达并持有句柄，响应体尚未在业务线程物化。 */
    ARRIVED,

    /** 响应体已按采集策略完成处理并留存（含按配置显式不读体）。 */
    BODY_MATERIALIZED;

    /** 业务只读查询的唯一可见判据（框架内部诊断/报告通道不受此限制）。 */
    public boolean isBusinessVisible() {
        return this == BODY_MATERIALIZED;
    }
}

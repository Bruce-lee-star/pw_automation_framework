package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

/**
 * AgentTrace 上报 SPI（R9 审计轨迹）。接口定义于 pw-core，实现由 pw-reports 经
 * {@code META-INF/services} 注册，打破 reporting ↔ agent 执行的循环依赖
 * （与 {@link LocatorHealthReportSink} 同构）。
 *
 * <p>无实现时由 {@link AgentTraceRegistry} 回落 no-op，执行链埋点永不因审计缺失而失败。</p>
 */
public interface AgentTraceSink {
    void record(AgentTraceEvent event);
}

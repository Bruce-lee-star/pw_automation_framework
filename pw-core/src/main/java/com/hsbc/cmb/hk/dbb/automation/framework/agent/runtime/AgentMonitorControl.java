package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

/**
 * <b>MonitorSink 角色</b>（T5）：执行链的监控失败汇聚 / 结算能力契约。
 *
 * <p>下游适配器把 {@code MonitorFailureReportSink}（pw-core SPI）与 route 侧在途动作排空包装为
 * 实例角色，使执行链的退出边界与业务链共享同一份监控归集状态，避免「执行链已结束但失败未结算」。</p>
 */
public interface AgentMonitorControl extends AgentRuntimeParticipant {

    /** 无监控实现时的 no-op（默认装配，零回归）。 */
    AgentMonitorControl NO_OP = new AgentMonitorControl() { };
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

/**
 * <b>Route 角色</b>（T5）：执行链的路由/采集生命周期能力契约。
 *
 * <p>下游适配器把 {@code RouteLifecycleRegistry}（pw-core SPI 桥，编译期零 route 依赖）包装为实例角色，
 * 使「执行链」与「业务链」在<b>同一 JVM 内</b>驱动同一个路由注册/采集状态，而非各自持有一套孤岛状态。</p>
 */
public interface AgentRouteControl extends AgentRuntimeParticipant {

    /** 无路由实现时的 no-op（默认装配，零回归）。 */
    AgentRouteControl NO_OP = new AgentRouteControl() { };
}

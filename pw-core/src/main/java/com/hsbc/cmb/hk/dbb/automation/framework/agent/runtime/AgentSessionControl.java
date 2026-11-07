package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

/**
 * <b>SessionStore 角色</b>（T5）：执行链的登录会话复用/复位能力契约。
 *
 * <p>下游适配器把 {@code SessionManager} / {@code SessionStore} 的会话门禁包装为实例角色；
 * 身份以 {@link AgentRuntimeScope#sessionKey()} 传递（{@code env + "_" + username}，
 * profile 不进键），与业务链会话闸门同源。</p>
 */
public interface AgentSessionControl extends AgentRuntimeParticipant {

    /** 无会话组件时的 no-op（默认装配，零回归）。 */
    AgentSessionControl NO_OP = new AgentSessionControl() { };
}

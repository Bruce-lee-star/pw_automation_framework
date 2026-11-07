package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

/**
 * <b>ConcurrencyGate 角色</b>（T5）：执行链的身份互斥闸门能力契约。
 *
 * <p>下游适配器把 {@code ConcurrencyGate} 包装为实例角色；进入作用域时按 sessionKey 取闸门，
 * 退出作用域时<b>无条件</b>释放本线程持有，避免许可泄漏（与业务链场景收口同一语义）。</p>
 */
public interface AgentConcurrencyControl extends AgentRuntimeParticipant {

    /** 闸门未启用时的 no-op（默认装配，零回归）。 */
    AgentConcurrencyControl NO_OP = new AgentConcurrencyControl() { };
}

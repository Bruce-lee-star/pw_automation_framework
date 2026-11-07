package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

/**
 * 执行链运行时参与者的统一生命周期契约（T5 / I-06）。
 *
 * <p>四个角色槽位（Route / Session / Concurrency / Monitor）都实现本接口，使
 * {@link AgentRuntimeContext} 能以固定顺序编排「进入 → 执行 → 退出」，并在部分进入失败时
 * 精确回滚已成功进入的槽位。方法默认 no-op，适配器只需覆写真正关心的钩子。</p>
 *
 * <p><b>依赖方向铁律</b>：本接口及其实现方所在的 {@code framework.agent.runtime} 包属于
 * pw-core，<b>不得</b>依赖 {@code framework.web} / {@code framework.route} / Playwright 任何类型；
 * 业务链组件由下游模块（pw-web-ui）以适配器形式包装为实例角色，经 SPI 或显式构造注入。
 * 由 pw-web-ui 的 {@code AgentRuntimeDependencyTest} 以 ArchUnit 固化。</p>
 */
public interface AgentRuntimeParticipant {

    /**
     * 进入一次执行作用域（在 {@code IsolationHarness.acquire} <b>之前</b>调用）。
     *
     * @param scope 本次执行不可变作用域信息
     */
    default void onScopeOpen(AgentRuntimeScope scope) {
    }

    /**
     * 退出执行作用域（在通道 release <b>之后</b>、按进入的<b>逆序</b>调用）。
     *
     * @param scope   与 {@link #onScopeOpen} 同一实例
     * @param failure 执行体抛出的异常；正常完成为 {@code null}
     */
    default void onScopeClose(AgentRuntimeScope scope, Throwable failure) {
    }
}

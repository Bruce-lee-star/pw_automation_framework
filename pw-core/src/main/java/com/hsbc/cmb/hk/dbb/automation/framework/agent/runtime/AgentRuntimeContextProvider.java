package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

/**
 * Agent 执行链运行时角色的 SPI 贡献者（T5 / I-06）。
 *
 * <p>接口定义于 pw-core，实现由下游模块经 {@code META-INF/services} 注册。由于
 * Route / MonitorSink（pw-route）与 SessionStore / ConcurrencyGate（pw-web-ui）分属
 * <b>互不依赖的兄弟模块</b>，单个模块无法独立提供全部四个角色：故允许多个贡献者各提供
 * 自己拥有的槽位（其余槽位填对应 {@code NO_OP}），由 {@link AgentRuntimeContexts}
 * 按槽位聚合，实现「不改 pom 依赖图」的接线。</p>
 *
 * <p>无贡献者、或贡献者加载失败时，执行链回落到 {@link AgentRuntimeContext#empty()}，
 * 行为与 T5 之前逐字一致（全量回归零回归）。</p>
 */
public interface AgentRuntimeContextProvider {

    /**
     * 贡献一个（可能是部分的）运行时上下文。
     *
     * @return 非 {@code null}；未提供的槽位须用 {@link AgentRouteControl#NO_OP} 等常量填充
     */
    AgentRuntimeContext contribute();
}

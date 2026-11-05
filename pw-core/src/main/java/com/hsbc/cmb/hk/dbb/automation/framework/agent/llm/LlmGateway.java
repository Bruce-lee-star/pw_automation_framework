package com.hsbc.cmb.hk.dbb.automation.framework.agent.llm;

/**
 * LLM 网关 SPI（设计 §3.1.3 / §4.6 ③ / §10.4 LLM 网关 SPI：Ollama/云端可插拔）。
 *
 * <p><b>契约</b>：本接口仅定义稳定的请求/响应协议；网络客户端、供应商配置与 secret 注入由<b>可选适配层</b>
 * 提供，<b>不放 pw-core</b>（R6）。网关实现经 {@link LlmGateways#resolveOrNull()} 的 {@code ServiceLoader}
 * 可选发现，无实现即视为不可用（fail-open）。</p>
 *
 * <p><b>铁律（R6 / §4.6）</b>：本网关<b>只用于生成期</b>（意图翻译 / 候选提出），
 * <b>绝不</b>进入回归运行路径；回归期 CI 零 LLM 依赖。引擎调用方必须在 {@link #available()} 为
 * {@code false} 时降级，不得让模型故障影响既有回归链路。</p>
 *
 * @see LlmGateways 可选发现与可用性解析
 */
public interface LlmGateway {

    /**
     * 网关当前是否可用（供应商可达 / 凭据就位 / 未超时）。
     * 实现抛出的任何异常都应由调用方（{@link LlmGateways}）捕获并视为不可用。
     */
    boolean available();

    /**
     * 同步完成一次补全。
     *
     * @param request 补全请求（prompt 已脱敏，R1/R7）
     * @return 补全响应（含模型归因）
     * @throws LlmUnavailableException 网关不可用（预期内降级信号）
     * @throws LlmGatewayException     其它网关错误
     */
    LlmResponse complete(LlmRequest request) throws LlmGatewayException;
}

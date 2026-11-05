package com.hsbc.cmb.hk.dbb.automation.framework.agent.llm;

/**
 * LLM 网关不可用异常（设计 §4.6 / §9 验收：模型不可用降级）。
 *
 * <p>当 classpath 上无 LLM 网关实现、或已注册网关 {@link LlmGateway#available()} 返回
 * {@code false}（供应商不可达 / 凭据缺失 / 超时）时抛出。这是<b>预期内的降级信号</b>，
 * 调用方应回退到确定性规则引擎或录制/手写资产，而非中断。</p>
 */
public class LlmUnavailableException extends LlmGatewayException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

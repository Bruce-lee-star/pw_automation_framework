package com.hsbc.cmb.hk.dbb.automation.framework.agent.llm;

/**
 * LLM 网关异常基类（设计 §10.4 LLM 网关 SPI）。
 *
 * <p>网关任何失败（网络/超时/供应商错误/解析失败）均以本异常或其子类抛出；调用方据此降级，
 * 不得让模型故障影响既有回归链路（§4.6 / §9 验收：模型不可用时不得阻塞）。</p>
 */
public class LlmGatewayException extends RuntimeException {

    public LlmGatewayException(String message) {
        super(message);
    }

    public LlmGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.llm;

/**
 * LLM 补全响应协议（设计 §3.1.3 / §4.6 / §10.4 LLM 网关 SPI）。
 *
 * <p>纯数据载体；{@code modelId} 用于<b>归因</b>（记录实际使用的供应商与模型版本，满足供应商治理 R9/§8.1），
 * 不回传任何凭据或原始观测。</p>
 */
public record LlmResponse(
        /** 补全内容（可能为受 schema 约束的 JSON 文本），必填。 */
        String content,
        /** 实际使用的模型标识（归因），必填。 */
        String modelId,
        /** 结束原因（stop/length/tool_calls…，可空）。 */
        String finishReason,
        /** 提示 token 用量（可空）。 */
        Long promptTokens,
        /** 补全 token 用量（可空）。 */
        Long completionTokens) {

    public LlmResponse {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("LlmResponse.content must not be blank");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("LlmResponse.modelId must not be blank");
        }
    }
}

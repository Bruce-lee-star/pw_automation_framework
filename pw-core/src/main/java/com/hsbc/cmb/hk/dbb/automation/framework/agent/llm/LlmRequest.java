package com.hsbc.cmb.hk.dbb.automation.framework.agent.llm;

/**
 * LLM 补全请求协议（设计 §3.1.3 / §4.6 / §10.4 LLM 网关 SPI）。
 *
 * <p>pw-core 只定义稳定的请求/响应协议；网络客户端、供应商配置与 secret 注入由<b>可选适配层</b>提供，
 * 不进核心（R6）。本类型为纯数据载体，不含任何网络/凭据逻辑。</p>
 *
 * <p>约定：调用方（如 LLM 理解引擎）必须把<b>需求上下文与脱敏观测</b>收敛为 system/user prompt，
 * 且 user prompt 不得含有原始敏感值（R1/R7）；{@code outputSchema} 用于约束模型产出为可校验结构。</p>
 */
public record LlmRequest(
        /** 供应商/模型提示（如 ollama/llama3、云端 gpt-4o）；{@code null} 表示由网关默认。 */
        String modelHint,
        /** 系统提示（角色与约束），必填。 */
        String systemPrompt,
        /** 用户提示（需求 + 脱敏观测），必填。 */
        String userPrompt,
        /** 输出 JSON-Schema 约束（可空）；用于把模型产出限制为可校验结构。 */
        String outputSchema,
        /** 采样温度（可空）。 */
        Double temperature,
        /** 最大 token（可空）。 */
        Integer maxTokens,
        /** 单次补全超时（毫秒），必须为正。 */
        long timeoutMs) {

    public LlmRequest {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("LlmRequest.systemPrompt must not be blank");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("LlmRequest.userPrompt must not be blank");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("LlmRequest.timeoutMs must be positive");
        }
    }
}

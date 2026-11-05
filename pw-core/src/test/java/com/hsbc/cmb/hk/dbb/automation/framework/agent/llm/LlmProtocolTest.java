package com.hsbc.cmb.hk.dbb.automation.framework.agent.llm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * LLM 网关协议层单测（设计 §10.4 LLM 网关 SPI；v3.16）。
 *
 * <p>锁定：① 请求/响应协议的参数校验（防裸 NPE，企业级健壮性）；② 异常层级（降级信号可辨识）；
 * ③ <b>无 SPI 实现时 fail-open 降级</b>：pw-core 测试类路径不含任何 {@link LlmGateway} 实现，
 * 故 {@link LlmGateways#resolveOrNull()} 必须返回 {@code null}、{@link LlmGateways#requireAvailable()}
 * 必须抛 {@link LlmUnavailableException}，绝不因可选组件缺失中断核心。若在 pw-core 引入默认实现，
 * 本用例会失败以提示「运行时零 LLM 依赖」契约被破坏。</p>
 */
public class LlmProtocolTest {

    @Test
    public void requestRejectsBlankSystemPrompt() {
        try {
            new LlmRequest(null, " ", "user", null, null, null, 1000);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("systemPrompt"));
        }
    }

    @Test
    public void requestRejectsBlankUserPrompt() {
        try {
            new LlmRequest(null, "sys", "", null, null, null, 1000);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("userPrompt"));
        }
    }

    @Test
    public void requestRejectsNonPositiveTimeout() {
        try {
            new LlmRequest(null, "sys", "user", null, null, null, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("timeoutMs"));
        }
    }

    @Test
    public void requestAcceptsOptionalKnobsAndCarriesValues() {
        LlmRequest r = new LlmRequest("ollama/llama3", "sys", "user", "{\"type\":\"object\"}",
                0.2, 512, 1500);
        assertEquals("ollama/llama3", r.modelHint());
        assertEquals("{\"type\":\"object\"}", r.outputSchema());
        assertEquals(Double.valueOf(0.2), r.temperature());
        assertEquals(Integer.valueOf(512), r.maxTokens());
        assertEquals(1500, r.timeoutMs());
    }

    @Test
    public void responseRejectsBlankContent() {
        try {
            new LlmResponse(" ", "model-1", "stop", 1L, 2L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("content"));
        }
    }

    @Test
    public void responseRejectsBlankModelId() {
        try {
            new LlmResponse("{}", "", "stop", 1L, 2L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("modelId"));
        }
    }

    @Test
    public void unavailableIsGatewayExceptionSubtype() {
        assertTrue(new LlmGatewayException("base") instanceof RuntimeException);
        assertTrue(new LlmUnavailableException("down") instanceof LlmGatewayException);
    }

    @Test
    public void resolveOrNullFailOpenWhenNoImplementation() {
        assertNull("pw-core 无 LlmGateway 实现时应 fail-open 返回 null", LlmGateways.resolveOrNull());
    }

    @Test
    public void requireAvailableThrowsUnavailableWhenNoImplementation() {
        try {
            LlmGateways.requireAvailable();
            fail("expected LlmUnavailableException");
        } catch (LlmUnavailableException expected) {
            assertTrue(expected.getMessage().contains("no available LLM gateway"));
        }
    }
}

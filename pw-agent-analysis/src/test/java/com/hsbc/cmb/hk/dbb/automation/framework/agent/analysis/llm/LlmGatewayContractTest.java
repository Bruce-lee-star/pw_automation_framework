package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.llm;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmGateway;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmGateways;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmRequest;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmResponse;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmUnavailableException;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * LLM 网关 SPI 契约测试（设计 §10.4 LLM 网关 SPI / §4.6 / §9 验收）。
 *
 * <p>锁定 {@link LlmGateways} 的：① 可选发现并可用网关；② 无可用网关时 fail-open 返回 null，
 * 且 {@link LlmGateways#requireAvailable()} 抛 {@link LlmUnavailableException}（降级信号）；
 * ③ 供应商异常被捕获并降级为 null（fail-open）。</p>
 */
public class LlmGatewayContractTest {

    @After
    public void resetStub() {
        StubLlmGateway.reset();
    }

    @Test
    public void discoversAndUsesAvailableGateway() {
        StubLlmGateway.setAvailable(true);
        LlmGateway gateway = LlmGateways.resolveOrNull();
        assertNotNull("ServiceLoader 应发现测试桩网关", gateway);
        org.junit.Assert.assertTrue(gateway.available());

        LlmRequest request = new LlmRequest(null, "sys", "user", null, null, null, 1000);
        LlmResponse response = gateway.complete(request);
        org.junit.Assert.assertTrue(response.content().contains("draft"));
        assertEquals("stub-llm/1", response.modelId());
    }

    @Test
    public void degradesToNullWhenNoGatewayAvailable() {
        StubLlmGateway.setAvailable(false);
        assertNull("无可用网关时应 fail-open 返回 null", LlmGateways.resolveOrNull());
        try {
            LlmGateways.requireAvailable();
            fail("应当抛出 LlmUnavailableException");
        } catch (LlmUnavailableException expected) {
            // 预期内降级信号
        }
    }

    @Test
    public void failOpenWhenProviderErrors() {
        StubLlmGateway.setThrowOnAvailable(true);
        // available() 抛异常 → 解析器捕获并降级为 null，不向上传播
        assertNull(LlmGateways.resolveOrNull());
    }
}

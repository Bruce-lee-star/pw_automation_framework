package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.llm;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmGateway;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmRequest;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmResponse;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmUnavailableException;

/**
 * 测试用 LLM 网关桩（仅测试类路径可见，不进生产 jar）。
 *
 * <p>用于锁定 {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmGateways} 的
 * 可选发现 / 可用性降级 / fail-open 契约。行为由静态开关控制，供契约测试在「可用 / 不可用 /
 * 供应商异常」三种形态间切换。</p>
 */
public final class StubLlmGateway implements LlmGateway {

    private static volatile boolean available = true;
    private static volatile boolean throwOnAvailable = false;
    private static final String MODEL_ID = "stub-llm/1";

    public static void setAvailable(boolean value) {
        available = value;
    }

    public static void setThrowOnAvailable(boolean value) {
        throwOnAvailable = value;
    }

    /** 测试隔离：复位到默认（可用、不抛）。 */
    public static void reset() {
        available = true;
        throwOnAvailable = false;
    }

    @Override
    public boolean available() {
        if (throwOnAvailable) {
            throw new IllegalStateException("stub gateway forced unavailable");
        }
        return available;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!available()) {
            throw new LlmUnavailableException("stub gateway not available");
        }
        return new LlmResponse("{\"draft\":true}", MODEL_ID, "stop", 10L, 5L);
    }
}

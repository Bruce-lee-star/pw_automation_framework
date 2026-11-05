package com.hsbc.cmb.hk.dbb.automation.framework.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * LLM 网关解析器（设计 §10.4 LLM 网关 SPI）。
 *
 * <p>经 {@code ServiceLoader} 可选发现 {@link LlmGateway} 实现；<b>无实现或发现异常一律 fail-open 返回
 * {@code null}</b>，绝不因可选组件缺失/损坏而中断核心或回归链路（R6 / §4.6）。</p>
 *
 * <p>解析语义：返回<b>第一个可用</b>的网关；若所有已注册网关均不可用，返回 {@code null}。
 * 调用方据此降级（回退规则引擎 / 录制 / 手写资产），不阻塞。</p>
 */
public final class LlmGateways {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmGateways.class);

    private LlmGateways() {
    }

    /**
     * 发现并返回第一个可用的 LLM 网关；无实现 / 全部不可用 / 发现异常均返回 {@code null}。
     */
    public static LlmGateway resolveOrNull() {
        try {
            for (LlmGateway gateway : ServiceLoader.load(LlmGateway.class)) {
                try {
                    if (gateway.available()) {
                        return gateway;
                    }
                    LOGGER.debug("LLM gateway reported unavailable: {}", gateway.getClass().getName());
                } catch (Throwable t) {
                    // 单个供应商探测失败不影响其它供应商的发现（fail-open，D7-3：必须留可观测痕迹）
                    LOGGER.debug("LLM gateway availability probe failed: {}", t.toString());
                }
            }
        } catch (Throwable t) {
            // ServiceConfigurationError（Error 子类）等：可选组件发现失败即视为无网关
            LOGGER.debug("LLM gateway discovery failed: {}", t.toString());
        }
        return null;
    }

    /**
     * 解析可用网关；不存在或不可用抛 {@link LlmUnavailableException}（预期内降级信号）。
     */
    public static LlmGateway requireAvailable() throws LlmUnavailableException {
        LlmGateway gateway = resolveOrNull();
        if (gateway == null) {
            throw new LlmUnavailableException("no available LLM gateway on classpath");
        }
        return gateway;
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;

/**
 * 非功能采集开关与灰度采样率（T9 / I-08）。
 *
 * <p><b>采集开关默认关</b>（{@link #ENABLED_KEY} 默认 {@code false}）。关闭时
 * {@link AgentPerfProbe} 不取高精度时间、不解析 SPI、不产生任何样本，
 * 因此既有执行链的性能与行为逐字不变。</p>
 *
 * <p><b>为什么不登记进 {@code ConfigRegistry}</b>：该枚举是业务配置的唯一事实来源，
 * 其键集由下游独立项目 {@code test-automation} 的 {@code config-keys-golden.txt} 双向锁定；
 * 本组键属于框架内部诊断开关（与既有 {@code agent.step.verify.settle.window.ms}、
 * {@code pw.agent.trace.dir} 同类），走 {@link ConfigReader#resolve(String, String)}
 * 的未登记键默认层序即可，避免为诊断开关改动下游键集基线。</p>
 *
 * <p><b>生产灰度（canary）策略</b>：{@code off}（默认，什么都不设）→
 * {@code canary}（{@code -Dagent.perf.enabled=true -Dagent.perf.sample.rate=0.01}，
 * 仅预发/CI 1% 抽样）→ {@code on}（采样率提到 1.0）。任一时点设回 {@code false}
 * 即完全零开销回滚。此 canary 指<b>采集灰度</b>，与 R1/R7 的「敏感令牌泄漏 canary」
 * 是两套不同语义，不可混淆。</p>
 */
public final class AgentPerfConfig {

    /** 采集总开关，默认 {@code false}。 */
    public static final String ENABLED_KEY = "agent.perf.enabled";
    /** 采样率 {@code [0,1]}，默认 {@code 1.0}；非法值回落默认值。 */
    public static final String SAMPLE_RATE_KEY = "agent.perf.sample.rate";

    private static final double DEFAULT_SAMPLE_RATE = 1.0;

    private AgentPerfConfig() {
    }

    /** 实时解析开关（每次执行计划刷新一次，步骤级只读缓存，见 {@link AgentPerfProbe#refresh()}）。 */
    public static boolean isEnabled() {
        String raw = ConfigReader.resolve(ENABLED_KEY, "false");
        return raw != null && Boolean.parseBoolean(raw.trim());
    }

    /**
     * 采样率；越界 / 非数字 / NaN / Inf 一律回落 {@code 1.0}
     * （配置写错时保持「开启即全采」的显式语义，不静默变成半采或不采）。
     */
    public static double sampleRate() {
        String raw = ConfigReader.resolve(SAMPLE_RATE_KEY, "1.0");
        if (raw == null) {
            return DEFAULT_SAMPLE_RATE;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0 || value > 1.0) {
                return DEFAULT_SAMPLE_RATE;
            }
            return value;
        } catch (NumberFormatException e) {
            return DEFAULT_SAMPLE_RATE;
        }
    }
}

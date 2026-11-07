package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 采集开关与采样率配置契约（T9）。
 *
 * <p><b>复位纪律</b>：一律显式 {@code System.setProperty} 复位为原值或默认值，
 * <b>绝不用 clearProperty</b>——本框架的 ConfigResolver 是启动期系统属性快照 + 静态缓存，
 * clear 不保证回落到文件/默认值，会把开启状态泄漏给同 JVM 的后续测试。</p>
 */
public class AgentPerfConfigTest {

    private String previousEnabled;
    private String previousRate;

    @Before
    public void capture() {
        previousEnabled = System.getProperty(AgentPerfConfig.ENABLED_KEY);
        previousRate = System.getProperty(AgentPerfConfig.SAMPLE_RATE_KEY);
    }

    @After
    public void restore() {
        System.setProperty(AgentPerfConfig.ENABLED_KEY,
                previousEnabled == null ? "false" : previousEnabled);
        System.setProperty(AgentPerfConfig.SAMPLE_RATE_KEY,
                previousRate == null ? "1.0" : previousRate);
    }

    @Test
    public void switchIsOffByDefaultAndCanBeEnabledExplicitly() {
        System.setProperty(AgentPerfConfig.ENABLED_KEY, "false");
        assertFalse(AgentPerfConfig.isEnabled());
        System.setProperty(AgentPerfConfig.ENABLED_KEY, "true");
        assertTrue(AgentPerfConfig.isEnabled());
        System.setProperty(AgentPerfConfig.ENABLED_KEY, "TRUE");
        assertTrue(AgentPerfConfig.isEnabled());
    }

    @Test
    public void sampleRateDefaultsToOneAndAcceptsBoundaries() {
        System.setProperty(AgentPerfConfig.SAMPLE_RATE_KEY, "1.0");
        assertEquals(1.0, AgentPerfConfig.sampleRate(), 0.0001);
        System.setProperty(AgentPerfConfig.SAMPLE_RATE_KEY, "0");
        assertEquals(0.0, AgentPerfConfig.sampleRate(), 0.0001);
        System.setProperty(AgentPerfConfig.SAMPLE_RATE_KEY, "0.01");
        assertEquals(0.01, AgentPerfConfig.sampleRate(), 0.0001);
    }

    @Test
    public void invalidSampleRateFallsBackToDefault() {
        for (String raw : new String[]{"abc", "-1", "2", "NaN", "Infinity", "  "}) {
            System.setProperty(AgentPerfConfig.SAMPLE_RATE_KEY, raw);
            assertEquals("illegal value must fall back to 1.0: " + raw,
                    1.0, AgentPerfConfig.sampleRate(), 0.0001);
        }
    }
}

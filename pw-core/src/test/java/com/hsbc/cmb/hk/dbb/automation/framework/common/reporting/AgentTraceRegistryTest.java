package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R9 审计 SPI 解耦验证：无实现时回落 no-op（执行链埋点永不失败），install 可注入替身。
 */
public class AgentTraceRegistryTest {

    @Test
    public void noProviderResolvesToNoOpAndDoesNotThrow() {
        AgentTraceSink sink = AgentTraceRegistry.get();
        assertNotNull(sink);
        // 即使没有任何 AgentTraceSink 实现，埋点也必须安全 no-op
        sink.record(new AgentTraceEvent("s1", "1", "CLICK", null, 5, true, null, null));
    }

    @Test
    public void installOverridesResolutionAndRestores() {
        final boolean[] called = {false};
        AgentTraceSink previous = AgentTraceRegistry.get();
        AgentTraceRegistry.install(e -> called[0] = true);
        try {
            AgentTraceRegistry.get().record(new AgentTraceEvent("s", "1", "CLICK", null, 1, true, null, null));
            assertTrue("installed stub should have been invoked", called[0]);
        } finally {
            AgentTraceRegistry.install(previous);
        }
        // 复位后恢复初始解析结果，不再走 stub
        called[0] = false;
        AgentTraceRegistry.get().record(new AgentTraceEvent("s", "1", "CLICK", null, 1, true, null, null));
        assertFalse("stub must not be invoked after reset", called[0]);
    }
}

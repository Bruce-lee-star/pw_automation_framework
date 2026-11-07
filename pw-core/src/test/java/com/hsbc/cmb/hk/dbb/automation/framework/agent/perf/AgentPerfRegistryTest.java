package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** 基线 SPI 注册表：no-op 回落 / 注入 seam / 复位（T9）。 */
public class AgentPerfRegistryTest {

    @After
    public void reset() {
        AgentPerfRegistry.reset();
    }

    @Test
    public void fallsBackToNoOpWhenNoProviderIsPresent() {
        AgentPerfRegistry.reset();
        assertSame(AgentPerfSink.NO_OP, AgentPerfRegistry.get());
    }

    @Test
    public void installedSinkReceivesSamples() {
        List<AgentPerfSample> captured = new ArrayList<>();
        AgentPerfRegistry.install(captured::add);
        AgentPerfSample sample = sample();
        AgentPerfRegistry.get().record(sample);
        assertEquals(List.of(sample), captured);
    }

    @Test
    public void resetRestoresNoOp() {
        List<AgentPerfSample> captured = new ArrayList<>();
        AgentPerfRegistry.install(captured::add);
        AgentPerfRegistry.reset();
        AgentPerfRegistry.get().record(sample());
        assertSame(AgentPerfSink.NO_OP, AgentPerfRegistry.get());
        assertEquals(List.of(), captured);
    }

    private static AgentPerfSample sample() {
        return new AgentPerfSample(AgentPerfSample.STEP_BUCKET, 1, 5L, true, null, 1, 0L);
    }
}

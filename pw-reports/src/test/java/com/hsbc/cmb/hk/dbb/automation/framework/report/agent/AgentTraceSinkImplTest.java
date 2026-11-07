package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.AgentTraceEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.AgentTraceRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.AgentTraceSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R9 审计接线验证：AgentTraceSinkImpl 组装 / 脱敏 / 落盘，且绝不泄漏凭据原文。
 */
class AgentTraceSinkImplTest {

    @AfterEach
    void reset() {
        AgentTraceRegistry.install(null);
        AgentTraceSinkImpl.store().clear();
    }

    @Test
    void recordAssemblesAndStoresTrace() {
        AgentTraceRegistry.install(new AgentTraceSinkImpl());
        AgentTraceSink sink = AgentTraceRegistry.get();
        sink.record(new AgentTraceEvent("p#0", "1", "NAVIGATE", "https://x.com/login", 10, true, "stepplan-v1abc", null));

        var traces = AgentTraceSinkImpl.store().snapshot();
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).actionCategory()).isEqualTo("NAVIGATE");
        assertThat(traces.get(0).outcome()).isEqualTo(AgentTrace.Outcome.SUCCESS);
        assertThat(traces.get(0).targetHost()).isEqualTo("x.com");
        assertThat(traces.get(0).approvalRef()).isEqualTo("stepplan-v1abc");
    }

    @Test
    void rawUrlIsStrippedToHostOnly_secretNotLeaked(@TempDir Path dir) throws Exception {
        AgentTraceRegistry.install(new AgentTraceSinkImpl());
        AgentTraceSink sink = AgentTraceRegistry.get();
        sink.record(new AgentTraceEvent("p#0", "1", "NAVIGATE",
                "https://user:secret@host.com/path?token=xyz", 1, true, null, null));

        var trace = AgentTraceSinkImpl.store().snapshot().get(0);
        assertThat(trace.targetHost()).isEqualTo("host.com");
        String json = trace.toMap().toString();
        assertThat(json).doesNotContain("secret").doesNotContain("xyz").doesNotContain("token=xyz");
    }

    @Test
    void writeDefaultIfAnyWritesJsonFile(@TempDir Path dir) throws Exception {
        System.setProperty("pw.agent.trace.dir", dir.toAbsolutePath().toString());
        AgentTraceRegistry.install(new AgentTraceSinkImpl());
        AgentTraceSink sink = AgentTraceRegistry.get();
        sink.record(new AgentTraceEvent("p#0", "1", "CLICK", null, 1, true, null, null));
        AgentTraceSinkImpl.writeDefaultIfAny();

        Path file = dir.resolve("agent-trace.json");
        assertThat(file).exists();
        String content = Files.readString(file);
        assertThat(content).contains("actionCategory").contains("CLICK");
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R9 AgentTraceWriter 测试：导出为脱敏 JSON，不含原始 secret，且不可重执行。
 */
class AgentTraceWriterTest {

    @TempDir
    Path dir;

    @Test
    void exportContainsOnlyMetadataAndNoRawSecret() throws Exception {
        AgentTraceStore store = new AgentTraceStore(100, null);
        store.record(AgentTrace.forAction("step-1", "v3.15", "NAVIGATE",
                "https://api.example.com/login?token=supersecret&user=bob", 12L, true, "AP-1", null));
        store.record(AgentTrace.forAction("step-2", "v3.15", "FILL",
                "https://form.example.com", 4L, false, null,
                new IllegalStateException("boom token=abc")));

        Path file = AgentTraceWriter.writeTo(dir, "agent-trace.json", store);
        String json = Files.readString(file);

        assertThat(json).startsWith("[\n").endsWith("]\n");
        // 最小元数据字段存在
        assertThat(json).contains("\"traceId\"").contains("\"targetHost\"")
                .contains("\"actionCategory\"").contains("\"outcome\"");
        // 原始 secret 绝不出现
        assertThat(json).doesNotContain("supersecret");
        assertThat(json).doesNotContain("token=supersecret");
        assertThat(json).doesNotContain("token=abc");
        assertThat(json).doesNotContain("boom token");
        // host-only
        assertThat(json).contains("\"targetHost\": \"api.example.com\"");
        assertThat(json).contains("\"errorClass\": \"IllegalStateException\"");
    }

    @Test
    void exportIsNotReplayable() throws Exception {
        AgentTraceStore store = new AgentTraceStore(100, null);
        store.record(AgentTrace.forAction("s", "v3.15", "NAVIGATE",
                "https://x.com", 1L, true, null, null));
        Path file = AgentTraceWriter.writeTo(dir, "agent-trace.json", store);
        String json = Files.readString(file);
        // 导出物不含任何可执行/回放指令关键词
        assertThat(json.toLowerCase()).doesNotContain("curl")
                .doesNotContain("execute").doesNotContain("replay")
                .doesNotContain("playwright").doesNotContain("navigate(");
    }

    @Test
    void emptyStoreProducesEmptyArray() throws Exception {
        AgentTraceStore store = new AgentTraceStore(100, null);
        Path file = AgentTraceWriter.writeTo(dir, "agent-trace.json", store);
        assertThat(Files.readString(file)).isEqualTo("[\n]\n");
    }

    @Test
    void fileResolvesUnderProvidedDir() throws Exception {
        AgentTraceStore store = new AgentTraceStore(100, null);
        store.record(AgentTrace.forAction("s", "v3.15", "CLICK", "https://x.com", 1L, true, null, null));
        Path nested = dir.resolve("sub");
        Path file = AgentTraceWriter.writeTo(nested, "agent-trace.json", store);
        assertThat(Files.exists(file)).isTrue();
    }
}

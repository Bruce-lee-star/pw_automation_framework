package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfSample;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 基线 SPI 实现的发现 / 聚合 / 落盘 / 空数据不生成文件（T9）。 */
class AgentPerfSinkImplTest {

    private String previousDir;

    @BeforeEach
    void capture() {
        previousDir = System.getProperty(AgentPerfSinkImpl.OUTPUT_DIR_KEY);
        AgentPerfSinkImpl.clear();
    }

    @AfterEach
    void reset() {
        AgentPerfRegistry.install(null);
        AgentPerfSinkImpl.clear();
        System.setProperty(AgentPerfSinkImpl.OUTPUT_DIR_KEY,
                previousDir == null ? "target/agent-perf" : previousDir);
    }

    @Test
    void spiProviderIsDiscoverableAndReceivesSamples() {
        previousDir = System.getProperty(AgentPerfSinkImpl.OUTPUT_DIR_KEY);
        AgentPerfRegistry.reset();

        AgentPerfSink sink = AgentPerfRegistry.get();
        assertThat(sink).isInstanceOf(AgentPerfSinkImpl.class);
        sink.record(sample(3L, true));

        assertThat(AgentPerfSinkImpl.samples()).hasSize(1);
        assertThat(AgentPerfSinkImpl.samples().get(0).bucket())
                .isEqualTo(AgentPerfSample.STEP_BUCKET);
    }

    @Test
    void flushWritesOnePageBaselineToConfiguredDirectory(@TempDir Path dir) throws Exception {
        previousDir = System.getProperty(AgentPerfSinkImpl.OUTPUT_DIR_KEY);
        System.setProperty(AgentPerfSinkImpl.OUTPUT_DIR_KEY, dir.toString());

        AgentPerfSinkImpl sink = new AgentPerfSinkImpl();
        sink.record(sample(10L, true));
        sink.record(sample(20L, false));

        Path markdown = AgentPerfSinkImpl.flush();

        assertThat(markdown).isNotNull().exists();
        assertThat(markdown.getFileName().toString())
                .isEqualTo(AgentPerfBaseline.MARKDOWN_FILE);
        Path json = dir.resolve(AgentPerfBaseline.JSON_FILE);
        assertThat(json).exists();
        assertThat(Files.readString(markdown)).contains("# Agent 非功能基线");
        Map<String, Object> model = new ObjectMapper().readValue(Files.readString(json),
                new TypeReference<Map<String, Object>>() {
                });
        assertThat(model).containsEntry("sampleCount", 2);
    }

    @Test
    void flushWithoutSamplesWritesNothing(@TempDir Path dir) {
        previousDir = System.getProperty(AgentPerfSinkImpl.OUTPUT_DIR_KEY);
        System.setProperty(AgentPerfSinkImpl.OUTPUT_DIR_KEY, dir.toString());
        AgentPerfSinkImpl.clear();

        assertThat(AgentPerfSinkImpl.flush()).isNull();
        assertThat(dir.resolve(AgentPerfBaseline.MARKDOWN_FILE)).doesNotExist();
        assertThat(dir.resolve(AgentPerfBaseline.JSON_FILE)).doesNotExist();
    }

    private static AgentPerfSample sample(long durationMs, boolean success) {
        return new AgentPerfSample(AgentPerfSample.STEP_BUCKET, 1, durationMs, success,
                success ? null : AgentPerfSample.FailureKind.UNKNOWN, 1, 0L);
    }
}

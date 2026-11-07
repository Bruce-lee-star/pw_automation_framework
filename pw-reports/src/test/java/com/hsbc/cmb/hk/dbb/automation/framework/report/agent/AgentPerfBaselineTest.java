package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfSample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 一页基线数据的聚合 / 渲染 / 落盘契约（T9 / I-08）。 */
class AgentPerfBaselineTest {

    private static final String GENERATED_AT = "2026-11-07T00:00:00Z";

    @Test
    void onePageBaselineContainsPercentilesBucketsConcurrencyFailuresAndAlerts() throws Exception {
        List<AgentPerfSample> samples = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            samples.add(step(i, true));
        }
        samples.add(plan(1, 20L, true, null));
        samples.add(plan(10, 900L, false, AgentPerfSample.FailureKind.TIMEOUT));
        samples.add(plan(100, 5000L, true, null));
        // 并发观测：单条样本 inFlight=7，用于断言峰值
        samples.add(new AgentPerfSample("step", 1, 9L, true, null, 7, 0L));

        AgentPerfBaseline baseline = AgentPerfBaseline.from(samples, "8", GENERATED_AT);
        String md = baseline.toMarkdown();

        assertThat(md).contains("# Agent 非功能基线（本次运行）")
                .contains("nearest-rank")
                .contains("| step | 101 | 50 | 95 | 100 | 100.00% |")
                .contains("plan-1").contains("plan-10").contains("plan-100")
                .contains("观测峰值 in-flight | 7")
                .contains("| 配置上限 `framework.playwright.concurrent.max` | 8 |")
                .contains("TIMEOUT | 1")
                .contains("告警项")
                .contains("不宣称达标")
                .contains("只做基线测量");

        Map<String, Object> json = parseJson(baseline.toJson());
        assertThat(json).containsEntry("sampleCount", 104)
                .containsEntry("percentileMethod", "nearest-rank")
                .containsEntry("thresholdsConfirmed", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> concurrency = (Map<String, Object>) json.get("concurrency");
        assertThat(concurrency).containsEntry("observedPeakInFlight", 7)
                .containsEntry("configuredCap", "8");
    }

    @Test
    void emptyBaselineRendersNoDataRowsAndNeverClaimsPass() throws Exception {
        AgentPerfBaseline baseline = AgentPerfBaseline.from(List.of(), "n/a", GENERATED_AT);
        assertThat(baseline.isEmpty()).isTrue();
        assertThat(baseline.sampleCount()).isZero();
        assertThat(baseline.toMarkdown())
                .contains("| n/a | 0 | n/a | n/a | n/a | n/a |")
                .contains("只做基线测量，不宣称达标");
        assertThat(parseJson(baseline.toJson())).containsEntry("sampleCount", 0);
    }

    @Test
    void floatingPointRenderingIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            List<AgentPerfSample> samples = List.of(step(1L, true), step(2L, false));
            String md = AgentPerfBaseline.from(samples, "8", GENERATED_AT).toMarkdown();
            assertThat(md).contains("50.00%").doesNotContain("50,00%");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void writeToProducesMarkdownAndMachineReadableJson(@TempDir Path dir) throws Exception {
        AgentPerfBaseline baseline = AgentPerfBaseline.from(List.of(step(3L, true)), "8", GENERATED_AT);
        Path markdown = baseline.writeTo(dir);

        assertThat(markdown).isEqualTo(dir.resolve(AgentPerfBaseline.MARKDOWN_FILE)).exists();
        assertThat(dir.resolve(AgentPerfBaseline.JSON_FILE)).exists();
        assertThat(Files.readString(markdown)).contains("# Agent 非功能基线");
        assertThat(parseJson(Files.readString(dir.resolve(AgentPerfBaseline.JSON_FILE))))
                .containsEntry("sampleCount", 1);
    }

    private static Map<String, Object> parseJson(String json) throws Exception {
        return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private static AgentPerfSample step(long durationMs, boolean success) {
        return new AgentPerfSample(AgentPerfSample.STEP_BUCKET, 1, durationMs, success,
                success ? null : AgentPerfSample.FailureKind.UNKNOWN, 1, 0L);
    }

    private static AgentPerfSample plan(int steps, long durationMs, boolean success,
                                        AgentPerfSample.FailureKind kind) {
        return new AgentPerfSample(AgentPerfSample.planBucket(steps), steps, durationMs,
                success, kind, 1, 0L);
    }
}

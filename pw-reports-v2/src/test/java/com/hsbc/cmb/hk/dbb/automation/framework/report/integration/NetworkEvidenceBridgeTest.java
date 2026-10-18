package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网络证据接缝（Tier2 #6）：{@link DefaultNetworkEvidenceBridge} 把一次含敏感参数的
 * 网络调用汇入当前步骤证据，且经总线统一脱敏——原始 token 不得出现在产物里。
 */
class NetworkEvidenceBridgeTest {

    @TempDir
    Path dir;

    private ReportBus bus;

    @BeforeEach
    void setUp() {
        bus = new ReportBus(PwReportsConfig.builder().outputDir(dir).build());
        bus.startRun();
    }

    @AfterEach
    void tearDown() {
        bus.finishRun();
    }

    @Test
    @DisplayName("网络调用汇入步骤证据，URL query 敏感参数被脱敏（原始 token 不落盘）")
    void scrubsSensitiveParamsIntoStepEvidence() throws Exception {
        StepHandle caseHandle = bus.startCase("net-1-call", "features/api", "calls endpoint", List.of());
        StepHandle step = bus.stepStarted("send request");
        StepHandle captured = bus.captureHandle();

        NetworkCall call = NetworkCall.builder()
                .method("GET")
                .url("https://api.example.com/session?token=SUPER_SECRET_TOKEN")
                .status(200)
                .durationMs(7)
                .requestHeaders(Map.of("Authorization", "Bearer xyz789"))
                .requestDigest("sha256:abc")
                .build();
        new DefaultNetworkEvidenceBridge(bus).record(captured, call);

        bus.stepFinished(step, TestResult.SUCCESS, null);
        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 7);
        bus.finishRun();

        TestOutcome o = JsonWriter.mapper().readValue(dir.resolve("net-1-call.json").toFile(), TestOutcome.class);
        String evidence = o.getTestSteps().stream()
                .flatMap(s -> s.getEvidence().stream())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        assertThat(evidence).contains("GET").contains("api.example.com").contains("200");
        assertThat(evidence).doesNotContain("SUPER_SECRET_TOKEN");
        assertThat(evidence).doesNotContain("Bearer xyz789");

        // 同时进 apiOperations 列表（双口径）
        assertThat(o.getApiOperations()).anyMatch(line -> line.contains("NET") && !line.contains("SUPER_SECRET_TOKEN"));
    }

    @Test
    @DisplayName("异常网络调用（status<0）也记入证据，携带错误描述")
    void recordsFailedCallWithError() throws Exception {
        StepHandle caseHandle = bus.startCase("net-2-fail", "features/api", "fails", List.of());
        StepHandle step = bus.stepStarted("send");
        NetworkCall call = NetworkCall.builder()
                .method("POST").url("https://api/x").status(-1)
                .errorMessage("java.net.ConnectException: timeout").build();
        new DefaultNetworkEvidenceBridge(bus).record(bus.captureHandle(), call);
        bus.stepFinished(step, TestResult.ERROR, null);
        bus.finishCase(caseHandle, TestResult.ERROR, null, 1);
        bus.finishRun();

        TestOutcome o = JsonWriter.mapper().readValue(dir.resolve("net-2-fail.json").toFile(), TestOutcome.class);
        assertThat(o.getTestSteps().stream().flatMap(s -> s.getEvidence().stream()))
                .anyMatch(line -> line.contains("ERR:") && line.contains("ConnectException"));
    }
}

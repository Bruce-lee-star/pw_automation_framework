package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Surefire {@code rerunFailingTestsCount} 重跑轮次的会话保留契约。
 *
 * <p>回归背景：首轮跑完全部用例后，rerun 会再次触发 Cucumber {@code TestRunStarted}
 * （→ {@code ReportBus.startRun()}）。早先 {@code startRun()} 无条件 {@code sessions.clear()}，
 * 导致首轮通过、未参与重跑的用例被清掉，最终 {@code RunFinishedEvent} 只剩重跑子集
 * （实测 218 → 103）。现契约：rerun 轮次保留既有会话，重跑用例按身份覆盖为最新结果。
 */
class ReportBusRerunTest {

    private ReportBus bus;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("pw-rerun-");
        bus = new ReportBus(PwReportsConfig.builder().outputDir(tempDir).build());
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    @DisplayName("rerun：保留首轮全部用例，重跑用例按身份覆盖为最新结果")
    void rerunKeepsFirstRoundAndOverwritesRerunCase() {
        String idA = "20261111-100000-" + sha("A");
        String idB = "20261111-100000-" + sha("B");
        String idC = "20261111-100000-" + sha("C");

        //  首轮：A 通过、B 失败、C 通过
        bus.startRun();
        pass(bus.startCase(idA, "features/login", "A", List.of()));
        fail(bus.startCase(idB, "features/login", "B", List.of()));
        pass(bus.startCase(idC, "features/login", "C", List.of()));
        bus.finishRun();

        assertThat(bus.outcomeSnapshots()).hasSize(3);

        //  记录首轮 B 的开始时间：重跑后 firstStartTime 必须仍是它，
        //  否则 index 的运行窗口 Started 会退化成 rerun 时刻（2026-11-11 用户反馈）。
        String firstRoundBStart = bus.outcomeSnapshots().stream()
                .filter(o -> "B".equals(o.getName())).findFirst().orElseThrow().getStartTime();

        //  rerun：只重跑 B（新时间戳，身份不变），本轮通过
        bus.startRun();
        pass(bus.startCase("20261111-101500-" + sha("B"), "features/login", "B", List.of()));
        bus.finishRun();

        //  首轮 A/C 不丢，B 只有一条且为最新结果
        assertThat(bus.outcomeSnapshots()).hasSize(3);
        TestOutcome rerunB = bus.outcomeSnapshots().stream()
                .filter(o -> "B".equals(o.getName())).findFirst().orElseThrow();
        assertThat(rerunB.getResult()).isEqualTo(TestResult.SUCCESS);
        assertThat(rerunB.getFirstStartTime()).isEqualTo(firstRoundBStart);
        assertThat(rerunB.getAttempt()).isEqualTo(2);
        //  未参与重跑的用例时间线不受影响
        assertThat(bus.outcomeSnapshots())
                .filteredOn(o -> !"B".equals(o.getName()))
                .allSatisfy(o -> {
                    assertThat(o.getFirstStartTime()).isEqualTo(o.getStartTime());
                    assertThat(o.getAttempt()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("rerun：重跑用例仍失败时保留最新失败结果，不重复计数")
    void rerunKeepsLatestFailure() {
        String idB = "20261111-100000-" + sha("B");

        bus.startRun();
        fail(bus.startCase(idB, "features/login", "B", List.of()));
        bus.finishRun();

        bus.startRun();
        fail(bus.startCase("20261111-101500-" + sha("B"), "features/login", "B", List.of()));
        bus.finishRun();

        assertThat(bus.outcomeSnapshots()).hasSize(1);
        assertThat(bus.outcomeSnapshots().get(0).getResult()).isEqualTo(TestResult.FAILURE);
    }

    private void pass(StepHandle handle) {
        bus.finishCase(handle, TestResult.SUCCESS, null, 1);
    }

    private void fail(StepHandle handle) {
        bus.finishCase(handle, TestResult.FAILURE, new AssertionError("boom"), 1);
    }

    private static String sha(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }
}

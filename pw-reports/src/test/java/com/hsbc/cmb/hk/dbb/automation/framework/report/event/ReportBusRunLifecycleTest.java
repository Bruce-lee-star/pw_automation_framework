package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 总线运行级生命周期（P1-5 / P1-9 回归门禁）。
 *
 * <ul>
 *   <li>用例归属只对绑定了 ThreadLocal 句柄的线程可见；其它线程不得读取最近活动用例。</li>
 *   <li><b>P1-9</b>：{@code startRun} 触发 {@code onRunStarted}，清掉 {@code LocalReportSink} 的
 *       {@code writtenIds}——否则同 JVM 第二轮 run 出现相同 outcomeId 时会被判"已写"而跳过 per-case
 *       JSON 真相源。</li>
 * </ul>
 */
class ReportBusRunLifecycleTest {

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
    @DisplayName("其他线程始终无法读取当前线程的活动用例归属")
    void activeOutcomeIsNotExposedToOtherThreads() throws Exception {
        bus.startCase("prev-case", "f", "s", List.of());
        assertThat(bus.activeOutcomeId()).isEqualTo("prev-case");
        assertThat(readActiveOutcomeOnForeignThread()).isNull();
        bus.startRun();
        assertThat(readActiveOutcomeOnForeignThread()).isNull();
    }

    @Test
    @DisplayName("P1-9：startRun 触发 onRunStarted，清 LocalReportSink.writtenIds，同 JVM 第二轮相同 outcomeId 不被跳过")
    void startRunResetsLocalSinkAcrossRuns() throws Exception {
        //  Run 1：写 dup 用例（SUCCESS）
        bus.startCase("dup", "f", "s", List.of());
        bus.finishCase(bus.captureHandle(), TestResult.SUCCESS, null, 1);
        bus.finishRun();

        //  Run 2：startRun 必须清掉 writtenIds，否则 dup 会被判"已写"跳过，导致本轮 per-case JSON 缺失
        bus.startRun();
        bus.startCase("dup", "f", "s", List.of());
        bus.finishCase(bus.captureHandle(), TestResult.FAILURE, null, 1);
        bus.finishRun();

        TestOutcome o = JsonWriter.mapper()
                .readValue(dir.resolve("dup").resolve("dup.json").toFile(), TestOutcome.class);
        //  若 writtenIds 未清，第二次 finishCase 会跳过写盘 → JSON 仍反映 Run1 的 SUCCESS
        assertThat(o.getResult()).isEqualTo(TestResult.FAILURE);
    }

    private String readActiveOutcomeOnForeignThread() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(bus.activeOutcomeId()), "foreign-close-guard");
        t.start();
        t.join(5_000);
        return result.get();
    }
}

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
 *   <li><b>P1-5</b>：{@link ReportBus#startRun()} 必须复位<b>跨线程可见</b>的活动用例指针
 *       （{@code activeOutcomeId} / {@code activeStep}）——否则上一轮收尾之后、本轮刚开始的
 *       跨线程事件（route 命中线程 / CloseGuard 收尾线程的 trace/video/证据）会挂到上一轮的 outcomeId 上。</li>
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
    @DisplayName("P1-5：startRun 复位跨线程活动用例指针，防止上一轮残留挂到本轮")
    void startRunResetsActiveOutcomeForOtherThreads() throws Exception {
        //  主线 startCase 设定活动用例（同时写入 volatile activeOutcomeId 与 ThreadLocal）
        bus.startCase("prev-case", "f", "s", List.of());
        //  另一线程（模拟 CloseGuard 收尾线程，无 ThreadLocal 关联）读到的活动用例应为 prev-case
        assertThat(readActiveOutcomeOnForeignThread()).isEqualTo("prev-case");
        //  新一轮开始：必须复位跨线程指针，否则上一轮收尾后的跨线程事件会挂到 prev-case
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

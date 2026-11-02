package com.hsbc.cmb.hk.dbb.automation.framework.report.sink;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.RunFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalReportSink 渲染等价性回归（P1-9 并行渲染改造）。
 * 锁死「finishAll 在并行分片 + 有界超时后，仍产出与旧串行实现完全一致的全部产物」。
 */
class LocalReportSinkRenderTest {

    @TempDir
    Path dir;

    @Test
    void finishAllRendersAllArtifactsAfterParallelSharding() {
        PwReportsConfig config = PwReportsConfig.builder().outputDir(dir).build();
        LocalReportSink sink = new LocalReportSink(config);
        sink.onRunStarted();

        TestOutcome a = outcome("case-a", TestResult.SUCCESS);
        TestOutcome b = outcome("case-b", TestResult.FAILURE);
        // 实时真相源（CaseFinished 落盘）—— 与 finishAll 的 sweep 互补
        sink.accept(new CaseFinishedEvent(a));
        sink.accept(new CaseFinishedEvent(b));

        // 触发并行分片渲染
        sink.accept(new RunFinishedEvent(List.of(a, b)));

        // 等价性回归：并行渲染后全部产物就位
        assertThat(dir.resolve("index.html")).exists();
        assertThat(dir.resolve("pw-summary.json")).exists();
        assertThat(dir.resolve("pw-summary.csv")).exists();
        assertThat(dir.resolve("pw-junit.xml")).exists();
        assertThat(dir.resolve("email-report.html")).exists();
        assertThat(dir.resolve("case-a").resolve("case-a.html")).exists();
        assertThat(dir.resolve("case-b").resolve("case-b.html")).exists();
        // JSON 真相源（实时落 + sweep）
        assertThat(dir.resolve("case-a").resolve("case-a.json")).exists();
        assertThat(dir.resolve("case-b").resolve("case-b.json")).exists();
        // 无渲染错误：所有期望产物已落盘（writtenIds 覆盖全部 outcomeId）
        assertThat(sink.writtenIds()).contains("case-a", "case-b");
    }

    @Test
    void finishAllToleratesZeroOutcomes() {
        PwReportsConfig config = PwReportsConfig.builder().outputDir(dir).build();
        LocalReportSink sink = new LocalReportSink(config);
        sink.onRunStarted();
        sink.accept(new RunFinishedEvent(List.of()));
        //  零用例不得抛、不得产生错误；index/summary 仍应生成（空运行）
        assertThat(dir.resolve("index.html")).exists();
        assertThat(dir.resolve("pw-summary.json")).exists();
        assertThat(sink.writtenIds()).isEmpty();
    }

    private static TestOutcome outcome(String id, TestResult result) {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId(id);
        o.setName(id);
        o.setResult(result);
        o.setStartTime("2026-01-01T00:00:00Z");
        o.setEndTime("2026-01-01T00:00:01Z");
        o.setDuration(1000L);
        return o;
    }
}

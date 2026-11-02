package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告顺序确定化（{@link ReportBus#orderForReport}）——同 feature 聚合、feature 间与 feature 内均按
 * 执行时间（{@code startTime}），消除 {@code ConcurrentHashMap} hash 序导致的跨运行不稳定。
 *
 * <p>契约：输入列表不被修改；{@code startTime} 为 {@code null} 的兜底场景沉底；feature 名缺失归一为
 * {@code ""}（相邻聚合，按首场景时间参与排序）。
 */
class ReportBusOrderingTest {

    private static TestOutcome outcome(String id, String feature, String scenario, String startTime) {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId(id);
        o.setName(scenario);
        o.setTitle(scenario);
        o.setUserStory(new UserStory(feature, feature, feature + ".feature"));
        o.setStartTime(startTime);
        return o;
    }

    @Test
    @DisplayName("同 feature 场景聚合：feature 间按首场景执行时间、feature 内按执行时间")
    void groupedByFeatureThenTime() {
        // 执行流（故意交错两个 feature）：F2.t1 → F1.t1 → F1.t3 → F2.t2
        List<TestOutcome> in = List.of(
                outcome("f1-3", "Feature One", "t3", "2026-10-19T10:00:03Z"),
                outcome("f2-1", "Feature Two", "t1", "2026-10-19T10:00:01Z"),
                outcome("f1-1", "Feature One", "t1", "2026-10-19T10:00:02Z"),
                outcome("f2-2", "Feature Two", "t2", "2026-10-19T10:00:04Z"));

        List<TestOutcome> ordered = ReportBus.orderForReport(in);

        // Feature Two 首场景 10:00:01 早于 Feature One 的 10:00:02 → Feature Two 整组在前，组内按时间
        assertThat(ordered).extracting(TestOutcome::getOutcomeId)
                .containsExactly("f2-1", "f2-2", "f1-1", "f1-3");
    }

    @Test
    @DisplayName("startTime 为 null 的兜底场景沉底；输入列表不被修改")
    void nullStartTimeSinksAndInputUntouched() {
        List<TestOutcome> in = new ArrayList<>();
        in.add(outcome("a", "F", "a", null));
        in.add(outcome("b", "F", "b", "2026-10-19T10:00:01Z"));
        in.add(outcome("c", "F", "c", "2026-10-19T10:00:02Z"));

        List<TestOutcome> ordered = ReportBus.orderForReport(in);

        assertThat(ordered).extracting(TestOutcome::getOutcomeId).containsExactly("b", "c", "a");
        assertThat(in).extracting(TestOutcome::getOutcomeId).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("feature 名缺失归一为空键，相邻聚合并按首场景时间参与排序")
    void missingFeatureGrouped() {
        TestOutcome noStory = new TestOutcome();
        noStory.setOutcomeId("x");
        noStory.setName("x");
        noStory.setStartTime("2026-10-19T10:00:09Z");

        List<TestOutcome> in = List.of(noStory, outcome("y", "F", "y", "2026-10-19T10:00:01Z"));

        assertThat(ReportBus.orderForReport(in)).extracting(TestOutcome::getOutcomeId)
                .containsExactly("y", "x");
    }
}

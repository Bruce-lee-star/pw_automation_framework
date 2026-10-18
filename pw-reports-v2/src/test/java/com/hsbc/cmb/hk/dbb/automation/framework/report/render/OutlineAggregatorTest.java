package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3：{@link OutlineAggregator} 把同一 Scenario Outline 的多行折叠为 1 个父 outcome（Serenity 对齐）。
 */
class OutlineAggregatorTest {

    private static TestOutcome row(String id, String outlineKey, int rowNum, TestResult result, long duration) {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId(id);
        o.setName("Login with <data>");
        o.setUserStory(new UserStory("Authentication", "Authentication", "features/login.feature"));
        o.setScenarioOutline(Boolean.TRUE);
        o.setOutlineKey(outlineKey);
        o.setExampleRowNumber(rowNum);
        o.setResult(result);
        o.setDuration(duration);
        o.setStartTime("2026-10-17T10:00:00.000+08:00");
        o.setEndTime("2026-10-17T10:00:01.000+08:00");
        return o;
    }

    @Test
    @DisplayName("同一 outlineKey 的多行折叠为 1 个父 outcome，examples 挂各行、按行号排序")
    void foldsRowsIntoOneParent() {
        List<TestOutcome> in = new ArrayList<>(List.of(
                row("login-10-login-2", "Authentication/Login with <data>", 2, TestResult.SUCCESS, 100),
                row("login-10-login", "Authentication/Login with <data>", 1, TestResult.FAILURE, 200),
                row("login-10-login-3", "Authentication/Login with <data>", 3, TestResult.SUCCESS, 150)
        ));
        List<TestOutcome> out = OutlineAggregator.aggregate(in);
        assertThat(out).hasSize(1);
        TestOutcome parent = out.get(0);
        assertThat(parent.getScenarioOutline()).isTrue();
        assertThat(parent.getResult()).isEqualTo(TestResult.FAILURE); // 任一行失败 → FAILURE
        assertThat(parent.getExamples()).hasSize(3);
        assertThat(parent.getExamples().get(0).getExampleRowNumber()).isEqualTo(1);
        assertThat(parent.getExamples().get(1).getExampleRowNumber()).isEqualTo(2);
        assertThat(parent.getExamples().get(2).getExampleRowNumber()).isEqualTo(3);
        assertThat(parent.getDuration()).isEqualTo(450); // 行时长求和
    }

    @Test
    @DisplayName("全绿 outline → 父 SUCCESS，时长求和")
    void allPassOutlineParentSuccess() {
        List<TestOutcome> in = new ArrayList<>(List.of(
                row("a-1", "F/O", 1, TestResult.SUCCESS, 10),
                row("a-2", "F/O", 2, TestResult.SUCCESS, 20)
        ));
        List<TestOutcome> out = OutlineAggregator.aggregate(in);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getResult()).isEqualTo(TestResult.SUCCESS);
        assertThat(out.get(0).getDuration()).isEqualTo(30);
    }

    @Test
    @DisplayName("单行 outline 退化为普通用例（不生成父，原对象保留）")
    void singleRowOutlineStaysPlain() {
        TestOutcome only = row("solo-1", "F/O", 1, TestResult.SUCCESS, 5);
        List<TestOutcome> out = OutlineAggregator.aggregate(new ArrayList<>(List.of(only)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isSameAs(only);
        assertThat(out.get(0).getExamples()).isNull();
    }

    @Test
    @DisplayName("普通用例与 outline 混排：普通用例原样保留，outline 折叠为父")
    void mixedKeepsStandaloneAndFoldsOutline() {
        TestOutcome normal = new TestOutcome();
        normal.setOutcomeId("plain-1");
        normal.setName("Normal scenario");
        normal.setResult(TestResult.SUCCESS);
        normal.setDuration(1);
        List<TestOutcome> in = new ArrayList<>(List.of(
                row("o-1", "F/O", 1, TestResult.SUCCESS, 1),
                normal,
                row("o-2", "F/O", 2, TestResult.FAILURE, 1)
        ));
        List<TestOutcome> out = OutlineAggregator.aggregate(in);
        assertThat(out).hasSize(2);
        assertThat(out).contains(normal);
        assertThat(out).anyMatch(o -> Boolean.TRUE.equals(o.getScenarioOutline())
                && o.getExamples() != null && o.getExamples().size() == 2);
    }

    @Test
    @DisplayName("无 outline 输入 → 原列表引用零成本返回")
    void noOutlineReturnsSameReference() {
        TestOutcome normal = new TestOutcome();
        normal.setOutcomeId("n-1");
        normal.setName("N");
        normal.setResult(TestResult.SUCCESS);
        List<TestOutcome> in = new ArrayList<>(List.of(normal));
        assertThat(OutlineAggregator.aggregate(in)).isSameAs(in);
    }
}

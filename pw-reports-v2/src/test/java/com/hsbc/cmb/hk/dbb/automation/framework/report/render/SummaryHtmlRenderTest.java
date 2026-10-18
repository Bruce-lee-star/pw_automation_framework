package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.FailureCause;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * serenity-summary.html 渲染门禁——对齐旧 SummaryReportGenerator 的信息结构
 * （alert 条 / 统计行 / feature 分组场景表 / 失败概览 / 文件名常量不变）。
 */
class SummaryHtmlRenderTest {

    @TempDir
    Path dir;

    private static TestOutcome outcome(String id, String feature, String scenario,
                                       TestResult result, String failureMessage) {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId(id);
        o.setName(scenario);
        o.setTitle(scenario);
        o.setUserStory(new UserStory(feature, feature, null));
        o.setResult(result);
        o.setDuration(123L);
        if (failureMessage != null) {
            FailureCause cause = new FailureCause();
            cause.setMessage(failureMessage);
            cause.setErrorType("FAILURE");
            o.setTestFailureCause(cause);
            o.setTestFailureMessage(failureMessage);
        }
        return o;
    }

    @Test
    void writesSerenitySummaryFileWithLegacyName() throws Exception {
        List<TestOutcome> outcomes = List.of(
                outcome("login-10-ok", "Login", "ok", TestResult.SUCCESS, null),
                outcome("login-11-bad", "Login", "bad", TestResult.FAILURE, "expected 200 but was 500"));
        Path out = SummaryHtmlRenderer.writeSummary(dir, outcomes);
        assertThat(out).isEqualTo(dir.resolve("serenity-summary.html"));
        assertThat(out).exists();
        String html = Files.readString(out);
        // 新设计信息结构：KPI 行 / 场景表（带 per-case 链接）/ 结果徽章 / 失败概览
        assertThat(html).contains("<div class=\"kpis\">");
        assertThat(html).contains("<a href=\"login-10-ok.html\">ok</a>");
        assertThat(html).contains("<a href=\"login-11-bad.html\">bad</a>");
        assertThat(html).contains("badge b-PASS").contains("badge b-FAILURE");
        assertThat(html).contains("Failures (1)");
        assertThat(html).contains("expected 200 but was 500");
    }

    @Test
    void allGreenShowsPassAlertAndNoFailureOverview() throws Exception {
        List<TestOutcome> outcomes = List.of(
                outcome("a-1-s", "Feature A", "s", TestResult.SUCCESS, null));
        SummaryHtmlRenderer.writeSummary(dir, outcomes);
        String html = Files.readString(dir.resolve("serenity-summary.html"));
        assertThat(html).contains("1 scenarios");
        assertThat(html).doesNotContain("Failures (");
    }

    @Test
    void emptyRunStillRendersZeroTotals() throws Exception {
        SummaryHtmlRenderer.writeSummary(dir, List.of());
        String html = Files.readString(dir.resolve("serenity-summary.html"));
        assertThat(html).contains("0 scenarios");
        assertThat(html).contains("0 features");
    }

    @Test
    void scenariosGroupedByFeatureInFirstSeenOrder() throws Exception {
        List<TestOutcome> outcomes = List.of(
                outcome("b-1-x", "Feature B", "x", TestResult.SUCCESS, null),
                outcome("a-2-y", "Feature A", "y", TestResult.SUCCESS, null),
                outcome("b-3-z", "Feature B", "z", TestResult.SKIPPED, null));
        List<Map<String, Object>> groups = SummaryHtmlRenderer.featureGroups(outcomes);
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("feature")).isEqualTo("Feature B");
        assertThat(groups.get(1).get("feature")).isEqualTo("Feature A");
        assertThat(groups.get(0).get("failed")).isEqualTo(false);
        assertThat(groups.get(1).get("failed")).isEqualTo(false);
    }

    @Test
    void failureRowTruncatesLongMessageToFirstLine() {
        String longMessage = "boom line1\nboom line2 " + "x".repeat(400);
        List<Map<String, Object>> rows = SummaryHtmlRenderer.failureRows(List.of(
                outcome("c-1-f", "C", "f", TestResult.ERROR, longMessage)));
        assertThat(rows).hasSize(1);
        String msg = (String) rows.get(0).get("message");
        assertThat(msg).startsWith("boom line1");
        assertThat(msg).doesNotContain("\n");
        assertThat(msg.length()).isLessThan(320);
        assertThat(rows.get(0).get("result")).isEqualTo("ERROR");
    }

    @Test
    void minimalSummaryFallbackContainsLinksEvenWithoutTemplate() {
        String html = SummaryHtmlRenderer.minimalSummary(List.of(
                outcome("d-1-m&x", "D", "m&b", TestResult.FAILURE, "msg")));
        assertThat(html).contains("<a href=\"d-1-m&amp;x.html\">m&amp;b</a>");
        assertThat(html).contains("FAILURE");
    }
}

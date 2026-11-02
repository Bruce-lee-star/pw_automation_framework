package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.FailureCause;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 汇总模型门禁——{@link SummaryHtmlRenderer} 的 feature 分组 / 失败概览口径。
 *
 * <p><b>2026-10-22 变更</b>：{@code serenity-summary.html}（模板 + {@code writeSummary} +
 * {@code SUMMARY_FILE} + {@code minimalSummary}）已整页删除——它与 {@code index.html} +
 * {@code feature-*.html} 信息完全重复。原「写文件 / 兜底页 / 空跑渲染」等针对该 HTML 的
 * 用例随之移除，只保留仍被 {@link EmailReportRenderer} 复用的模型层契约。
 */
class SummaryHtmlRenderTest {

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
    @DisplayName("serenity-summary.html 已删除：渲染器不再暴露写文件入口")
    void serenitySummaryPageIsGone() throws Exception {
        //  编译期门禁：方法已删，任何残留调用都会在此编译失败
        assertThat(SummaryHtmlRenderer.class.getDeclaredMethods())
                .noneMatch(m -> m.getName().equals("writeSummary")
                        || m.getName().equals("minimalSummary"));
        assertThat(SummaryHtmlRenderer.class.getDeclaredFields())
                .noneMatch(f -> f.getName().equals("SUMMARY_FILE"));
    }

    @Test
    @DisplayName("feature 分组保持首现顺序，失败标记不误报")
    void scenariosGroupedByFeatureInFirstSeenOrder() {
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
    @DisplayName("失败概览行：超长消息截到首行且限长")
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
}

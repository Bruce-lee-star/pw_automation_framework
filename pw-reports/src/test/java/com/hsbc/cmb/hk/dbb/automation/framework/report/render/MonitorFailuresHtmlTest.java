package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

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
 * monitor-failures.html 渲染门禁——对齐旧版三条硬语义：
 * <ol>
 *   <li><b>无数据不落文件</b>（文件存在即有失败，CI 收集逻辑零改动）；</li>
 *   <li>独立于 summary（monitor-failures-by-owner.json 时代是独立通道，这里是独立字段）；</li>
 *   <li>feature 分组（无 owner 维度，2026-10-16 决策）+ 可折叠条目 + 场景回链。</li>
 * </ol>
 */
class MonitorFailuresHtmlTest {

    @TempDir
    Path dir;

    private static TestOutcome outcome(String id, String feature, String scenario,
                                       TestResult result, List<String> monitorFailures) {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId(id);
        o.setName(scenario);
        o.setTitle(scenario);
        o.setUserStory(new UserStory(feature, feature, null));
        o.setResult(result);
        o.setDuration(50L);
        if (monitorFailures != null) {
            o.setApiMonitorFailures(monitorFailures);
        }
        return o;
    }

    @Test
    void noMonitorFailuresWritesNothing() {
        Path out = SummaryHtmlRenderer.writeMonitorFailures(dir, List.of(
                outcome("a-1-ok", "A", "ok", TestResult.SUCCESS, List.of()),
                outcome("b-2-ok", "B", "ok2", TestResult.FAILURE, List.of())));
        assertThat(out).isNull();
        assertThat(dir.resolve("monitor-failures.html")).doesNotExist();
    }

    @Test
    void writesFileWithStatsAndFeatureGroupsAndCaseLinks() throws Exception {
        List<TestOutcome> outcomes = List.of(
                outcome("pay-10-f", "Payment", "pay failed", TestResult.FAILURE,
                        List.of("MONITOR FAIL POST /api/pay status=500", "MONITOR FAIL GET /api/pay/retry timeout")),
                outcome("pay-11-ok", "Payment", "pay ok", TestResult.SUCCESS, List.of()),
                outcome("auth-12-f", "Auth", "auth failed", TestResult.ERROR,
                        List.of("MONITOR FAIL POST /api/auth token=invalid")));
        Path out = SummaryHtmlRenderer.writeMonitorFailures(dir, outcomes);
        assertThat(out).isEqualTo(dir.resolve("monitor-failures.html"));
        assertThat(out).exists();
        String html = Files.readString(out);
        // 统计条：3 条失败 / 2 个场景受影响
        assertThat(html).contains("API Monitor Failure Report");
        assertThat(html).contains("MONITOR FAIL POST /api/pay status=500");
        assertThat(html).contains("MONITOR FAIL GET /api/pay/retry timeout");
        assertThat(html).contains("MONITOR FAIL POST /api/auth token=invalid");
        // feature 分组且首现顺序
        int payIdx = html.indexOf("Payment");
        int authIdx = html.indexOf("Auth");
        assertThat(payIdx).isBetween(0, authIdx);
        // 场景回链（per-case 嵌套目录）
        assertThat(html).contains("<a class=\"scenario\" href=\"pay-10-f/pay-10-f.html\">pay failed</a>");
        assertThat(html).contains("href=\"auth-12-f/auth-12-f.html\"");
        // 成功用例不出现在条目里
        assertThat(html).doesNotContain("pay-11-ok.html");
    }

    @Test
    void monitorEntriesFlattenAcrossFeaturesInOutcomeOrder() {
        List<SummaryHtmlRenderer.MonitorEntry> entries = SummaryHtmlRenderer.monitorEntries(List.of(
                outcome("x-1-a", "X", "a", TestResult.FAILURE, List.of("f1", "f2")),
                outcome("y-2-b", "Y", "b", TestResult.SUCCESS, List.of("f3"))));
        assertThat(entries).extracting(SummaryHtmlRenderer.MonitorEntry::line)
                .containsExactly("f1", "f2", "f3");
        assertThat(entries).extracting(SummaryHtmlRenderer.MonitorEntry::feature)
                .containsExactly("X", "X", "Y");
        assertThat(entries).extracting(SummaryHtmlRenderer.MonitorEntry::scenario)
                .containsExactly("a", "a", "b");
    }

    @Test
    void monitorGroupsPreserveFirstSeenFeatureOrder() {
        List<SummaryHtmlRenderer.MonitorEntry> entries = SummaryHtmlRenderer.monitorEntries(List.of(
                outcome("y-1-b", "Y", "b", TestResult.FAILURE, List.of("f-y")),
                outcome("x-2-a", "X", "a", TestResult.FAILURE, List.of("f-x"))));
        List<Map<String, Object>> groups = SummaryHtmlRenderer.monitorGroups(entries);
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("feature")).isEqualTo("Y");
        assertThat(groups.get(1).get("feature")).isEqualTo("X");
    }

    @Test
    void nullMonitorFieldTreatedAsEmpty() {
        // 旧 JSON 回读（readTree→readValue）可能缺字段：不炸、不落文件
        TestOutcome o = outcome("z-1-n", "Z", "n", TestResult.SUCCESS, null);
        assertThat(SummaryHtmlRenderer.monitorEntries(List.of(o))).isEmpty();
    }
}

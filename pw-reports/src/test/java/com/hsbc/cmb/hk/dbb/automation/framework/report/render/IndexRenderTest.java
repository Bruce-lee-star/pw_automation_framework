package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * index.html 渲染门禁：footer 的 reportVersion 必须数据驱动（从用例取），
 * 不得硬编码字面量（历史缺陷：曾写死 "reportVersion 1" 导致 schema 演进两处漂移）。
 */
class IndexRenderTest {

    @Test
    @DisplayName("index footer 的 reportVersion 来自用例数据而非字面量")
    void footerReportVersionIsDataDriven() throws Exception {
        // 用非默认值（2）证明 footer 取的是用例数据而非模板字面量
        TestOutcome o = OutcomeFixtures.full();
        o.setReportVersion(2);
        String html = AggregateBuilder.renderIndexToString(List.of(o));
        assertThat(html)
                .contains("reportVersion 2")
                .doesNotContain("reportVersion 1</footer>")
                .contains("<footer>");
    }

    @Test
    @DisplayName("空用例列表时 footer 回退 reportVersion 1 且不抛异常")
    void footerFallsBackOnEmptyList() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of());
        assertThat(html).contains("reportVersion 1");
    }

    @Test
    @DisplayName("统计信息合并为一个模块：构成/结果/耗时同卡，耗时用直方图")
    void statisticsAreGroupedInOneModule() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .contains("Statistics")
                .contains("stats-grid")
                .contains("Automated vs Manual")
                .contains("Result distribution")
                .contains("Duration distribution")
                .contains("hist2-bar");
        // 旧版“独立耗时卡 + flex 直方图”已移除
        assertThat(html).doesNotContain("class=\"hist\"").doesNotContain("hist-col");
    }

    @Test
    @DisplayName("统计卡底部含执行耗时汇总：Total / Clock / Average / Max / Min（2026-10-22 用户反馈）")
    void statisticsShowExecutionTimings() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .contains("class=\"timing-row\"")
                .contains("class=\"timing-cell\"")
                .contains(">Total<")
                .contains(">Clock<")
                .contains(">Average<")
                .contains(">Max<")
                .contains(">Min<");
    }

    @Test
    @DisplayName("报告名三栏布局：与 per-case 同款 topbar-title（不再是 \"Test Report · name\"）")
    void topbarReportNameAlignsWithPerCase() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));
        assertThat(html)
                .contains("class=\"topbar-title\">")
                .contains("class=\"topbar-side is-right\"");
        // 旧写法会把报告名拼成 "Test Report · <name>"，与 per-case 的居中标题不一致 —— 已移除
        assertThat(html).doesNotContain("<h1>Test Report");
    }

    @Test
    @DisplayName("顶栏时间信息放最右边；左侧为空占位（三栏结构保证报告名居中）")
    void topbarShowsRunWindowOnTheRight() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .contains("Generated at ")
                .contains("Started ")
                .contains(" · Ended ");
        //  2026-10-22 用户要求"放在最右边"：右侧承载时间栈，左侧留空占位
        assertThat(html).contains("<div class=\"topbar-side\"></div>");
        assertThat(html).doesNotContain("<div class=\"topbar-side is-right\"></div>");
    }

    @Test
    @DisplayName("feature 统计并入顶部 KPI 卡（新增 Features 卡），Feature overview 下不再有统计卡")
    void featureStatsLiveInKpiRowNotUnderFeatureOverview() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        //  KPI 行第一张即 Features 卡（6 卡：Features / Scenarios / Automated / Manual / Pass rate / Failed）
        assertThat(html)
                .contains("<div class=\"label\">Features</div>")
                .contains("<div class=\"pct\">executed in this run</div>");
        //  Feature overview 卡内不再有 fo-stats 统计磁贴（2026-10-22 用户要求"去除"）
        assertThat(html).doesNotContain("class=\"fo-stats\"");
    }

    @Test
    @DisplayName("Environment 为 Statistics 卡内全宽横带（不再挤占图表列）")
    void environmentIsFullWidthBandUnderCharts() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        //  2026-10-22：四列并置被用户反馈"不协调"，改为图表下方全宽横带（label 上 / value 下）
        assertThat(html)
                .contains("class=\"env-band\"")
                .contains("class=\"env-items\"")
                .contains("class=\"env-item\"");
        //  不再是统计栅格里的第四列，也不是独立卡片
        assertThat(html).doesNotContain("<div class=\"stat-title\">Environment</div>");
        assertThat(html).doesNotContain("<h3><span class=\"icon\"></span>Environment</h3>");
        //  有浏览器数据时才把 Browsers 作为环境行末项（单测环境无浏览器数据，故条件断言）
        if (html.contains("browser-bar")) {
            assertThat(html).contains("class=\"env-item-browsers\"");
        }
    }

    @Test
    @DisplayName("不再展示 All scenarios 明细表；Feature overview 提供名称/问题/类型/通过率筛选")
    void featureOverviewReplacesAllScenariosAndIsFilterable() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        // case 多时 All scenarios 信息密度过低（2026-10-22 用户要求移除）
        assertThat(html)
                .doesNotContain("All scenarios (")
                .doesNotContain("id=\"sc-body\"");
        // Feature overview 承担唯一定位入口 → 必须有筛选控件与行级数据属性
        assertThat(html)
                .contains("id=\"fo-body\"")
                .contains("id=\"fo-name\"")
                .contains("id=\"fo-issues\"")
                .contains("id=\"fo-type\"")
                .contains("id=\"fo-rate\"")
                .contains("class=\"filter-row\"")
                .contains("class=\"fo-row clickable\"")
                .contains("data-issues=")
                .contains("data-manual=")
                .contains("data-rate=");
        // Feature 筛选是「列出全部 feature 名字供选择」的下拉，不是输入框；并提供 Reset
        assertThat(html)
                .contains("<select id=\"fo-name\"")
                .contains("All features</option>")
                .contains("id=\"fo-reset\"")
                .doesNotContain("<input type=\"search\" id=\"fo-name\"");
    }

    @Test
    @DisplayName("Automated vs Manual 与 Result distribution 同排版：dist-list 行 + stack-bar 进度条")
    void automatedVsManualMatchesResultDistribution() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        // 两块都必须用 dist-list + stack-bar（此前 Automated vs Manual 用 legend 且无进度条）
        assertThat(html).doesNotContain("class=\"legend\"");
        int distLists = countOccurrences(html, "class=\"dist-list\"");
        int stackBars = countOccurrences(html, "class=\"stack-bar\"");
        assertThat(distLists).isEqualTo(2);
        assertThat(stackBars).isEqualTo(2);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}

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
}

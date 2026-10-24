package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用例详情页「上下卡片宽度对齐」门禁（2026-10-22 用户反馈"执行步骤和上下两部分没对齐"）。
 *
 * <p>规则：右栏（Case actions）只对失败用例存在；通过用例必须收成单栏
 * （{@code case-layout is-single}），否则 Execution steps 卡片会比上方标题卡、下方 Tab 卡窄一列。</p>
 */
class CaseLayoutAlignmentRenderTest {

    @Test
    @DisplayName("通过用例：无右栏 → case-layout is-single（Execution steps 与上下卡片同宽）")
    void passedCase_isSingleColumn() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("lay-pass");
        o.setName("passed case");
        o.setResult(TestResult.SUCCESS);

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("class=\"case-layout is-single\"");
        assertThat(html).doesNotContain("Case actions");
    }

    @Test
    @DisplayName("失败用例：Case actions 并入主列 → 仍是单栏（Execution steps 右边缘与上下卡片齐平）")
    void failedCase_isSingleColumnWithActions() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("lay-fail");
        o.setName("failed case");
        o.setResult(TestResult.FAILURE);

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("class=\"case-layout is-single\"");
        assertThat(html).contains("Case actions");
    }
}

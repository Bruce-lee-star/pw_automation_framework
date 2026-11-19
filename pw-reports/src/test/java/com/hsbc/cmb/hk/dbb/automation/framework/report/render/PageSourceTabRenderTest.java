package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 「Page source」Tab 渲染门禁：失败页源码用 iframe 独立渲染，保留样式、可看整页。 */
class PageSourceTabRenderTest {

    @Test
    @DisplayName("有源码快照：渲染 Page source Tab + iframe（本地预览 ../ 回退）")
    void rendersTabWithIframe() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("ps-1");
        o.setName("failure page source");
        o.setResult(TestResult.FAILURE);
        o.setPageSourcePath("ps-1/page-source.html");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("data-tab=\"tab-source\"");
        assertThat(html).contains("id=\"tab-source\"");
        assertThat(html).contains("Page source");
        assertThat(html).contains("class=\"page-source-frame\"");
        assertThat(html).contains("src=\"../ps-1/page-source.html\"");
        assertThat(html).contains("Open raw source");
    }

    @Test
    @DisplayName("无源码快照：不渲染 Page source Tab")
    void noTabWithoutSource() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("ps-2");
        o.setName("no source");
        o.setResult(TestResult.SUCCESS);
        o.getConsoleOutput().add("SOME_OUTPUT");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).doesNotContain("data-tab=\"tab-source\"");
        assertThat(html).doesNotContain("src=\"../ps-2/page-source.html\"");
    }
}

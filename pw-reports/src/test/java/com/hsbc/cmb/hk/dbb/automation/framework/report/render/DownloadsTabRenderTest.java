package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.DownloadRecord;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「Downloads」Tab 渲染门禁（additive-only）。
 *
 * <p>背景：框架把 {@code waitForDownloadSaved} 的下载文件经 {@code ReportingBridge.recordDownloadableFile}
 * 复制进 {@code <outputDir>/<outcomeId>/<file>}，并由 {@code ReportBus.attachDownloadableFile} 登记为
 * {@link DownloadRecord}；本测试保证 per-case 报告据此渲染出可点击下载的「Downloads」Tab，
 * 以及未挂载时的空态文案指向真实配置键（不泄漏 Javadoc 语法）。</p>
 */
class DownloadsTabRenderTest {

    @Test
    @DisplayName("有下载：渲染 Downloads Tab，链接指向同目录归档文件（本地预览无需 ../ 回退）")
    void rendersTabWithFileLink() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("dl-1");
        o.setName("downloads");
        o.setResult(TestResult.SUCCESS);
        o.getDownloads().add(new DownloadRecord(
                "DOWNLOAD: report.csv", "report.csv", "dl-1/report.csv", "2026-10-22T10:00:00Z"));

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("data-tab=\"tab-downloads\"");
        assertThat(html).contains("Downloads (1)");
        assertThat(html).contains("href=\"report.csv\"");
        assertThat(html).contains("download=\"report.csv\"");
        assertThat(html).doesNotContain("No files were downloaded in this scenario");
    }

    @Test
    @DisplayName("无下载：空态文案给出真实配置键，不泄漏 Javadoc 语法")
    void emptyStateShowsRealConfigKey() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("dl-2");
        o.setName("no downloads");
        o.setResult(TestResult.SUCCESS);
        //  底部 Tab 区仅在「有 console / trace / 录像 / 截图 / 下载」时渲染，故给一条 console 让 Tab 出现
        o.getConsoleOutput().add("SOME_OUTPUT");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("No files were downloaded in this scenario");
        assertThat(html).contains("playwright.browser.download.attachToReport.enabled=true");
        assertThat(html).doesNotContain("{@code");
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件单页报告门禁——核心契约是<b>自包含</b>：单文件即可渲染，不引用任何外部资源。
 *
 * <p>邮件客户端（Outlook 用 Word 引擎、Gmail 剥离 script/style）决定了这些约束，
 * 一旦有人往 email-report.ftl 里加 {@code <link>} / {@code <script>} / assets 引用，
 * 邮件正文就会出现裸 HTML 或丢样式——故用测试钉死。
 */
class EmailReportRendererTest {

    @Test
    @DisplayName("自包含：无外部 CSS/JS/图片引用")
    void emailReportIsSelfContained() throws Exception {
        String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .doesNotContain("<script")
                .doesNotContain("<link")
                .doesNotContain("assets/")
                .doesNotContain("favicon")
                .doesNotContain("src=")
                .doesNotContain("@import")
                .doesNotContain("url(");
        assertThat(html).contains("<style>");
        assertThat(html.trim()).endsWith("</html>");
    }

    @Test
    @DisplayName("自包含：样式内联在本文件内（有 <style> 且具备邮件安全布局）")
    void emailReportUsesInlineStyleAndTableLayout() throws Exception {
        String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

        // 表格布局（Outlook 不支持 flex/grid）+ 关键元素的行内样式（客户端会剥离部分 <style>）
        assertThat(html).contains("<table").contains("bgcolor=");
        assertThat(html).doesNotContain("display:flex").doesNotContain("display: flex");
        assertThat(html).doesNotContain("<svg");
    }

    @Test
    @DisplayName("含汇总、失败明细与 feature 汇总")
    void emailReportContainsSummaryAndFailures() throws Exception {
        String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .contains("Result distribution")
                .contains("Failures")
                .contains("Feature summary")
                .contains("Environment")
                .contains("User logs in with valid credentials")
                .contains("expected status 200 but was 401")
                .contains("Authentication");
    }

    @Test
    @DisplayName("无失败时展示全绿横幅，而非空的失败表")
    void showsAllPassedBannerWhenNoFailures() throws Exception {
        TestOutcome ok = new TestOutcome();
        ok.setOutcomeId("demo-1-passes-cleanly");
        ok.setName("Passes cleanly");
        ok.setUserStory(new UserStory("Demo", "Demo", "features/demo.feature"));
        ok.setResult(TestResult.SUCCESS);
        ok.setDuration(500);

        String html = EmailReportRenderer.renderToString(List.of(ok));

        assertThat(html).contains("All scenarios passed");
        assertThat(html).contains("Failures (0)");
    }

    @Test
    @DisplayName("落盘名为 email-report.html 且内容自包含")
    void writesEmailReportFile(@TempDir Path dir) throws Exception {
        Path out = EmailReportRenderer.writeEmailReport(dir, List.of(OutcomeFixtures.full()));

        assertThat(out).exists();
        assertThat(out.getFileName().toString()).isEqualTo("email-report.html");

        String onDisk = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(onDisk).doesNotContain("<script").doesNotContain("<link");
        assertThat(onDisk).contains("<style>");
    }

    @Test
    @DisplayName("兜底页同样自包含（模板异常时降级）")
    void minimalPageIsSelfContained() {
        String html = EmailReportRenderer.minimalEmail(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .doesNotContain("<script")
                .doesNotContain("<link")
                .contains("Test Report")
                .contains("Failed: 1");
    }

    @Test
    @DisplayName("空用例集也能渲染（不除零、不报错）")
    void rendersWithEmptyOutcomeList() throws Exception {
        String html = EmailReportRenderer.renderToString(List.of());

        assertThat(html).contains("<style>").contains("Result distribution");
        assertThat(html).contains("All scenarios passed");
    }
}

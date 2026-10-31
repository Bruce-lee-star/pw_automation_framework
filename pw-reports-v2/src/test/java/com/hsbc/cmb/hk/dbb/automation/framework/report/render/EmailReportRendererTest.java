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
    @DisplayName("含汇总、失败明细；Functional Coverage 与环境节已按用户决定删除")
    void emailReportContainsSummaryAndFailures() throws Exception {
        String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .contains("Summary")
                .contains("Full Failure List")
                .contains("Full Test Results")
                .contains("Test Failure Overview")
                .contains("User logs in with valid credentials")
                .contains("expected status 200 but was 401")
                .contains("Authentication");
        //  2026-10-22 用户决定：feature 统计并入顶部 KPI 卡后删除 Functional Coverage；
        //  并"email report 去除环境信息这一块"
        assertThat(html)
                .doesNotContain("Functional Coverage")
                .doesNotContain("Environment");
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
        assertThat(html).doesNotContain("Full Failure List");
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

        assertThat(html).contains("<style>").contains("Summary");
        assertThat(html).contains("All scenarios passed");
    }

    @Test
    @DisplayName("报告名居中放大：配置了 project.report.name 就显示它，不再显示 Test Report")
    void bannerUsesConfiguredReportName() throws Exception {
        String saved = System.getProperty("project.report.name");
        System.setProperty("project.report.name", "DBB Regression Suite");
        try {
            String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

            assertThat(html)
                    .contains("DBB Regression Suite")
                    .contains("align=\"center\"")
                    .contains("font-size:28px");
            // 配置了名字，标题就不该再回落成 "Test Report"
            assertThat(html).doesNotContain(">Test Report</div>");
        } finally {
            if (saved == null) {
                System.clearProperty("project.report.name");
            } else {
                System.setProperty("project.report.name", saved);
            }
        }
    }

    @Test
    @DisplayName("版式：960 宽正文 + 耗时指标条 + 运行时间行（2026-10-22 用户反馈页面太窄、时间区不好看、缺时间信息）")
    void layoutUsesWideContainerAndTimingStrip() throws Exception {
        String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

        // 正文宽度 720 → 960（仍是邮件客户端安全上限内）
        assertThat(html).contains("width=\"960\"");
        // 耗时由「一行粗体文本」改为 5 格分隔指标条：标签小字加粗 + 数值加大
        assertThat(html)
                .contains("background:#f8fafc;border:1px solid #e8eef6;border-radius:8px")
                .contains(">Total</div>")
                .contains(">Clock</div>")
                .contains(">Average</div>")
                .contains(">Max</div>")
                .contains(">Min</div>");
        //  指标条标签加粗提色（用户反馈"Total/Clock 这些字体不显眼"）
        assertThat(html).contains("font-size:11px;font-weight:700;color:#475569;text-transform:uppercase");
        //  运行时间行：生成 / 开始 / 结束（与三份站点报告同源的 run 窗口）
        assertThat(html)
                .contains("tests executed")
                .contains("Generated at <b>")
                .contains("Started <b>2026-10-15 10:15:30</b>");
    }

    @Test
    @DisplayName("feature 统计进顶部 KPI 卡；Functional Coverage 与环境信息已从邮件去除")
    void featureStatsInKpiRowAndCoverageAndEnvRemoved() throws Exception {
        String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

        //  feature 统计：KPI 行新增 Features 卡（6 卡，16.66% 宽）
        assertThat(html)
                .contains("Features")
                .contains("executed in this run")
                .contains("width=\"16.66%\"");
        //  Functional Coverage 整节删除（用户 2026-10-22 决定）
        assertThat(html).doesNotContain("Functional Coverage");
        //  环境信息从邮件去除（用户反馈 Summary 加环境列后不好看）
        assertThat(html).doesNotContain("Environment");
    }

    @Test
    @DisplayName("失败总览按 report.error.types 配置的类型聚合，而非整段消息片段")
    void frequentFailureTypesRespectConfig() throws Exception {
        String saved = System.getProperty("report.error.types");
        System.setProperty("report.error.types", "AuthFailure=401|unauthorized");
        try {
            String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

            assertThat(html)
                    .contains("Most Frequent Failure Types")
                    .contains("AuthFailure");
        } finally {
            if (saved == null) {
                System.clearProperty("report.error.types");
            } else {
                System.setProperty("report.error.types", saved);
            }
        }
    }

    @Test
    @DisplayName("配置的 report.error.types 在明细行同样生效（优先于内置分类，原样展示类型名）")
    void failureRowsRespectConfiguredType() throws Exception {
        String saved = System.getProperty("report.error.types");
        System.setProperty("report.error.types", "AuthFailure=401|unauthorized");
        try {
            String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

            assertThat(html)
                    .contains("Full Failure List")
                    .contains(">AuthFailure</span>")                 // 配置类型名原样进明细行徽章
                    .doesNotContain(">Assertion failure</span>");    // 配置命中时内置分类不覆盖
        } finally {
            if (saved == null) {
                System.clearProperty("report.error.types");
            } else {
                System.setProperty("report.error.types", saved);
            }
        }
    }

    @Test
    @DisplayName("Full Failure List 每条失败用例带分类徽章（与聚合榜同源：AssertionError → Assertion failure）")
    void failureRowsCarryTypeBadge() throws Exception {
        String html = EmailReportRenderer.renderToString(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .contains("Full Failure List")
                .contains("background:#b91c1c")            // Assertion failure 分类徽章底色（typeBg）
                .contains(">Assertion failure</span>");     // 分类徽章文本（failureTypeLabel）
    }
}

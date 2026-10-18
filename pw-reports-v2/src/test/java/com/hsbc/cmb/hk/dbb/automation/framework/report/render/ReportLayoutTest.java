package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告信息架构门禁：
 * <ul>
 *   <li>Feature 页与总览页一致，统计区含“耗时直方图”（此前 feature 页缺失）；</li>
 *   <li>per-scenario 页提供回到所属 Feature 的链接与面包屑（此前只能回 Overview）。</li>
 * </ul>
 */
class ReportLayoutTest {

    @Test
    @DisplayName("Feature 页含统计模块与耗时直方图，并可回总览")
    void featurePageHasStatisticsAndHistogram(@TempDir Path dir) throws Exception {
        List<Path> pages = FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full()));
        assertThat(pages).isNotEmpty();

        String html = Files.readString(pages.get(0), StandardCharsets.UTF_8);
        assertThat(html)
                .contains("Statistics")
                .contains("stats-grid")
                .contains("Duration distribution")
                .contains("hist2-bar")
                .contains("href=\"index.html\"");
        // Feature 页的耗时桶必须是本 feature 自己的（模板变量存在即渲染）
        assertThat(html).contains("Execution time");
    }

    @Test
    @DisplayName("per-scenario 页提供回到所属 Feature 的链接与面包屑")
    void scenarioLinksBackToFeature() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());

        assertThat(html).contains("← back to Authentication");
        assertThat(html).contains("feature-authentication.html");
        assertThat(html).contains("<a href=\"feature-authentication.html\">Authentication</a>");
        // 仍保留回 Overview 的路径
        assertThat(html).contains("<a href=\"index.html\">Overview</a>");
    }

    @Test
    @DisplayName("无 userStory 时不渲染 feature 链接，回退到 Overview")
    void scenarioWithoutFeatureFallsBackToOverview() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        o.setUserStory(null);

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("← back to overview");
        assertThat(html).doesNotContain("feature-");
    }
}

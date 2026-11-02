package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告导航可用性门禁（2026-10-23 用户反馈「Jenkins 上 index.html 点击 feature 没有任何交互」）。
 *
 * <p>根因：列表行原本用<b>内联 {@code onclick="location.href=..."}</b> 承载跳转，而 Jenkins 对归档 HTML
 * 施加 CSP / sandbox，内联脚本被浏览器拦掉，点击整行毫无反应；feature 页更是只有这一个入口。
 * 修复：改用<b>真实 {@code <a class="row-link">}</b> 锚点包裹链接单元格，完全不依赖 JS。</p>
 *
 * <p>本测试锁死该行为：渲染结果必须出现 row-link 锚点，且不得再出现内联 onclick。</p>
 */
class ReportNavigationRenderTest {

    @Test
    @DisplayName("index.html：feature 行用真实锚点跳转，无内联 onclick")
    void indexRowsNavigateByRealAnchor() throws Exception {
        String html = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));

        assertThat(html)
                .contains("<a class=\"row-link\" href=\"feature-")
                .doesNotContain("onclick=");
    }

    @Test
    @DisplayName("feature.html：scenario 行用真实锚点跳转，无内联 onclick")
    void featureRowsNavigateByRealAnchor(@TempDir Path dir) throws Exception {
        String html = Files.readString(
                FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full())).get(0),
                StandardCharsets.UTF_8);

        assertThat(html)
                .contains("<a class=\"row-link\" href=\"")
                .doesNotContain("onclick=");
    }
}

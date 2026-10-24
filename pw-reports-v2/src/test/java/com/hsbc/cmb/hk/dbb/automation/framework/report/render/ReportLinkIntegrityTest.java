package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告三层页面（index → feature → per-case）的<b>相对链接完整性</b>门禁。
 *
 * <p>背景（2026-10-23 用户反馈）：Jenkins 上 index 能点了，但 feature 页丢样式、从 feature 点 per-case 404。
 * 怀疑是相对路径深度问题：per-case 页在 {@code <dir>/<outcomeId>/}（比 index/feature 深一级），
 * 资源与用例页链接的 {@code ../} 前缀一旦写错，在没有 {@code <base href>} 时立刻暴露。</p>
 *
 * <p>本测试把三层页面真实渲染到临时目录，再逐个解析 {@code href} 并校验目标文件存在——
 * 任何“点了就 404 / 样式丢失”的链接都会在这里被抓住。</p>
 */
class ReportLinkIntegrityTest {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    @Test
    @DisplayName("index / feature / per-case 的相对链接（.html/.css/.svg）都必须解析到真实文件")
    void relativeLinksResolveToRealFiles(@TempDir Path dir) throws Exception {
        List<TestOutcome> outcomes = List.of(OutcomeFixtures.full());

        StaticAssets.copyAssets(dir);
        AggregateBuilder.writeIndex(dir, outcomes);
        List<Path> featurePages = FeatureHtmlRenderer.writeFeatures(dir, outcomes);
        for (TestOutcome o : outcomes) {
            HtmlRenderer.renderOutcome(dir, o);
        }

        List<Path> pages = new ArrayList<>();
        pages.add(dir.resolve("index.html"));
        pages.addAll(featurePages);
        for (TestOutcome o : outcomes) {
            pages.add(dir.resolve(o.getOutcomeId()).resolve(o.getOutcomeId() + ".html"));
        }

        List<String> broken = new ArrayList<>();
        for (Path page : pages) {
            if (!Files.exists(page)) {
                broken.add("MISSING PAGE: " + dir.relativize(page));
                continue;
            }
            String html = Files.readString(page, StandardCharsets.UTF_8);
            Matcher m = HREF.matcher(html);
            while (m.find()) {
                String href = m.group(1);
                if (href.startsWith("http") || href.startsWith("#")
                        || href.startsWith("mailto:") || href.startsWith("data:")) {
                    continue;
                }
                // 只校验导航与样式类链接；trace.zip / 录屏 / 截图由框架另写，不在本断言范围
                if (!(href.endsWith(".html") || href.endsWith(".css") || href.endsWith(".svg"))) {
                    continue;
                }
                int cut = href.indexOf('?');
                if (cut < 0) cut = href.indexOf('#');
                if (cut >= 0) href = href.substring(0, cut);

                Path resolved = page.getParent().resolve(href).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(dir.relativize(page) + "  ->  " + m.group(1));
                }
            }
        }

        assertThat(broken).as("断链（点击会 404 或样式丢失）").isEmpty();
    }

    @Test
    @DisplayName("per-case 页位于 <outcomeId>/ 子目录，资源必须用 ../ 回退一级")
    void perCasePageAssetsStepUpOneLevel(@TempDir Path dir) throws IOException {
        TestOutcome o = OutcomeFixtures.full();
        HtmlRenderer.renderOutcome(dir, o);

        Path page = dir.resolve(o.getOutcomeId()).resolve(o.getOutcomeId() + ".html");
        String html = Files.readString(page, StandardCharsets.UTF_8);

        assertThat(html)
                .contains("../assets/report-v2.css")
                .doesNotContain("href=\"assets/report-v2.css\"");
    }
}

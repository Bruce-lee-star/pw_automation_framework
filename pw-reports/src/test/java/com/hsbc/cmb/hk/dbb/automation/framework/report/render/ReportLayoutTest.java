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
import java.util.ArrayList;
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
    @DisplayName("Feature 页报告名三栏布局，与 overview / per-case 一致")
    void featureTopbarAlignsWithOverviewAndPerCase(@TempDir Path dir) throws Exception {
        List<Path> pages = FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full()));
        String html = Files.readString(pages.get(0), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("class=\"topbar-title\">")
                .contains("class=\"topbar-side is-right\"")
                .contains("← back to overview");
        // 旧写法：h1 写死 "Feature"、报告名被塞进右侧 meta —— 与 per-case 不一致
        assertThat(html).doesNotContain("<h1>Feature");
    }

    @Test
    @DisplayName("Feature 页 Duration 可排序，行上带 data-duration 数值")
    void featureDurationColumnIsSortable(@TempDir Path dir) throws Exception {
        List<Path> pages = FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full()));
        String html = Files.readString(pages.get(0), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("id=\"sort-duration\"")
                .contains("data-duration=\"")
                .contains("querySelectorAll('tr.sc-row')");
    }

    @Test
    @DisplayName("Feature 页筛选移到表头：Result / Scenario / Type 三列各带控件")
    void featureFiltersLiveInTableHeader(@TempDir Path dir) throws Exception {
        List<Path> pages = FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full()));
        String html = Files.readString(pages.get(0), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("class=\"filter-row\"")
                .contains("id=\"flt-result\"")
                .contains("id=\"flt-scenario\"")
                .contains("id=\"flt-type\"")
                .contains("data-name=\"");
        //  Scenario 与 index 的 Feature 一致：列出全部名字的下拉（非输入框），并带 Reset
        assertThat(html)
                .contains("<select id=\"flt-scenario\"")
                .contains("All scenarios</option>")
                .contains("id=\"flt-reset\"")
                .doesNotContain("id=\"flt-scenario\" placeholder");
        //  与表格脱节、且缺 Scenario 维度的上方工具栏已移除
        assertThat(html).doesNotContain("class=\"toolbar\"");
    }

    @Test
    @DisplayName("截图画廊支持左右箭头切图，序号 active / focus 状态同步")
    void screenshotGallerySupportsPrevNextNavigation() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());

        assertThat(html)
                .contains("class=\"shot-nav shot-prev\"")
                .contains("class=\"shot-nav shot-next\"")
                .contains("id=\"shot-prev\"")
                .contains("id=\"shot-next\"")
                .contains("aria-label=\"Previous screenshot\"")
                .contains("aria-label=\"Next screenshot\"")
                // 序号按钮的键盘焦点必须可见（用户特别提到"注意序号的 focus"）
                .contains(".shot-pick:focus-visible")
                .contains("t.setAttribute('aria-pressed', on ? 'true' : 'false')")
                // 键盘左右切图
                .contains("e.key === 'ArrowLeft'")
                .contains("e.key === 'ArrowRight'");
    }

    @Test
    @DisplayName("Feature 页构成/结果两块同排版：dist-list + stack-bar 各两份")
    void featureCompositionMatchesResultDistribution(@TempDir Path dir) throws Exception {
        List<Path> pages = FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full()));
        String html = Files.readString(pages.get(0), StandardCharsets.UTF_8);

        assertThat(html).doesNotContain("class=\"legend\"");
        assertThat(count(html, "class=\"dist-list\"")).isEqualTo(2);
        assertThat(count(html, "class=\"stack-bar\"")).isEqualTo(2);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    @Test
    @DisplayName("所有报告顶栏都不再显示 reportVersion（版本只留页脚）")
    void topbarsDoNotShowReportVersion(@TempDir Path dir) throws Exception {
        String index = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));
        String feature = Files.readString(
                FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full())).get(0),
                StandardCharsets.UTF_8);
        String perCase = HtmlRenderer.renderToString(OutcomeFixtures.full());

        for (String html : List.of(index, feature, perCase)) {
            assertThat(html)
                    .as("顶栏区不得出现 reportVersion")
                    .doesNotContain("topbar-ver\">reportVersion");
        }
        //  页脚仍保留版本（诊断/追溯用），证明不是把版本信息整个删掉
        assertThat(perCase).contains("<footer>Scenario page · reportVersion");
        //  三栏结构保留（空右侧占位），报告名仍居中
        assertThat(perCase).contains("class=\"topbar-title\">");
        assertThat(feature).contains("class=\"topbar-title\">");
        assertThat(index).contains("class=\"topbar-title\">");
    }

    @Test
    @DisplayName("三份报告顶栏都显示运行窗口（生成/开始/结束），per-case 用自身执行窗口")
    void topbarsShowRunWindow(@TempDir Path dir) throws Exception {
        String index = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));
        String feature = Files.readString(
                FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full())).get(0),
                StandardCharsets.UTF_8);
        String perCase = HtmlRenderer.renderToString(OutcomeFixtures.full());

        for (String html : List.of(index, feature, perCase)) {
            assertThat(html)
                    .as("顶栏应含运行窗口（Generated / Started / Ended）")
                    .contains("class=\"topbar-stack\"")
                    .contains("Generated at ")
                    .contains("Started ")
                    .contains(" · Ended ");
        }
        //  per-case 的窗口来自用例自身时间（fixture：2026-10-15T10:15:30+08:00 → 人读格式）
        assertThat(perCase).contains("Started 2026-10-15 10:15:30");
    }

    @Test
    @DisplayName("时间窗口口径按页分工：index=整个运行、feature=该 feature、per-case=该用例")
    void runWindowScopeIsPerPage(@TempDir Path dir) throws Exception {
        //  fixture 用例：2026-10-15T10:15:30.123+08:00 → 10:15:31.456+08:00（Authentication）
        TestOutcome auth = OutcomeFixtures.full();
        TestOutcome checkout = new TestOutcome();
        checkout.setOutcomeId("checkout-1-user-checks-out");
        checkout.setName("User checks out");
        checkout.setUserStory(new UserStory("Checkout", "Checkout", "features/checkout.feature"));
        checkout.setResult(TestResult.SUCCESS);
        checkout.setStartTime("2026-10-15T11:00:00.000+08:00");
        checkout.setEndTime("2026-10-15T11:05:00.000+08:00");
        checkout.setDuration(300000);
        List<TestOutcome> outcomes = List.of(auth, checkout);

        //  index：整个运行（最早开始 → 最晚结束）
        String index = AggregateBuilder.renderIndexToString(outcomes);
        assertThat(index).contains("Started 2026-10-15 10:15:30 · Ended 2026-10-15 11:05:00");

        //  feature 页：各自的窗口，不再与 index 相同（2026-10-22 用户核对发现两者一模一样）
        List<Path> pages = FeatureHtmlRenderer.writeFeatures(dir, outcomes);
        assertThat(pages).isNotEmpty();
        List<String> featurePages = new ArrayList<>();
        for (Path p : pages) {
            featurePages.add(Files.readString(p, StandardCharsets.UTF_8));
        }
        assertThat(featurePages)
                .as("Authentication feature 页应显示该 feature 自己的窗口")
                .anySatisfy(h -> assertThat(h)
                        .contains("Started 2026-10-15 10:15:30 · Ended 2026-10-15 10:15:31"))
                .as("Checkout feature 页应显示该 feature 自己的窗口")
                .anySatisfy(h -> assertThat(h)
                        .contains("Started 2026-10-15 11:00:00 · Ended 2026-10-15 11:05:00"));
        //  两个 feature 页都不是 index 的全量窗口
        assertThat(featurePages)
                .noneSatisfy(h -> assertThat(h)
                        .contains("Started 2026-10-15 10:15:30 · Ended 2026-10-15 11:05:00"));
    }

    @Test
    @DisplayName("Feature 头部改用 KPI 磁贴条，不再是大号通过率 + chips")
    void featureHeaderUsesKpiStrip(@TempDir Path dir) throws Exception {
        List<Path> pages = FeatureHtmlRenderer.writeFeatures(dir, List.of(OutcomeFixtures.full()));
        String html = Files.readString(pages.get(0), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("class=\"kpis kpis-4\"")
                .contains("class=\"feature-title\"")
                .doesNotContain("class=\"feature-head\"")
                //  旧的「右侧大号通过率」块（.sub + 24px 数字）已移除
                .doesNotContain("<div class=\"sub\">Pass rate</div>");
        //  KPI 四块：Scenarios / Passed / Issues / Pass rate
        assertThat(count(html, "class=\"kpi\"")).isEqualTo(4);
    }

    @Test
    @DisplayName("统计栅格用 minmax(0,1fr)：列可收缩，不会互相溢叠")
    void statsGridCannotOverflowIntoNeighbours(@TempDir Path dir) throws Exception {
        String index = AggregateBuilder.renderIndexToString(List.of(OutcomeFixtures.full()));
        //  规则在共享样式表里（页面只 <link> 它），故直接读资源内容断言
        String css;
        try (var in = ReportLayoutTest.class.getClassLoader()
                .getResourceAsStream("assets/report-v2.css")) {
            assertThat(in).isNotNull();
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        //  2026-10-22：Environment 曾并入统计卡第四列，用户反馈"不协调"（窄列里值被折断），
        //  已改回 3 列图表栅格 + 全宽环境横带（.env-band），正文宽度 1280 → 1440（同轮加宽）
        assertThat(css)
                .contains(".stats-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr))")
                .contains(".env-band {")
                .doesNotContain("grid-template-columns:1fr 1fr 1.2fr");
        //  渲染出的页面确实引用该样式表
        assertThat(index).contains("assets/report-v2.css");
    }

    @Test
    @DisplayName("截图改为点击放大：shot-zoom + 全屏预览层 + 再点/Esc 恢复")
    void screenshotsZoomOnClickAndRestoreOnSecondClick() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());

        assertThat(html)
                .contains("class=\"shot-zoom\"")
                .contains("cursor:zoom-in")
                .contains("cursor:zoom-out")
                .contains("openZoom(img.getAttribute('src'))")
                .contains("shotHover.addEventListener('click', closeZoom)")
                .contains("e.key === 'Escape'")
                .contains("Click anywhere or press Esc to restore");
        //  旧的 hover 即弹全屏（pointer-events:none，无法主动关闭）与 shot-link 跳转已移除
        assertThat(html).doesNotContain("pointer-events:none; }\n        #shot-hover.on");
        assertThat(html).doesNotContain("shot-link");
        assertThat(html).doesNotContain("shotStepIndex");
    }

    @Test
    @DisplayName("失败消息按 ' | ' 拆段渲染，cause 段单独成徽标")
    void failureMessageIsSplitIntoSegments() throws Exception {
        //  渲染层：模板用 failureLines(...) 逐段输出（ fixture 消息无 " | "，退化为单段）
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());
        assertThat(html).contains("class=\"failure-seg");
    }

    @Test
    @DisplayName("failureLines 把三段式诊断消息拆成可读的三行，并保留顺序")
    void failureLinesSplitsThreeSegmentDiagnosticMessage() {
        //  与 ElementOperationSupport#buildDiagnosticMessage 的输出逐字同构
        String message = "[NOT_FOUND_IN_DOM] type failed after 1 attempts on '#newPassword'"
                + " | page=https://host/logon title=Log on obstruction=Element not found"
                + " | cause=TimeoutError";

        assertThat(HtmlRenderer.failureLines(message))
                .containsExactly(
                        "[NOT_FOUND_IN_DOM] type failed after 1 attempts on '#newPassword'",
                        "page=https://host/logon title=Log on obstruction=Element not found",
                        "cause=TimeoutError");
    }

    @Test
    @DisplayName("failureLines 对无分段/空白/null 输入安全退化")
    void failureLinesDegradesSafely() {
        assertThat(HtmlRenderer.failureLines(null)).isEmpty();
        assertThat(HtmlRenderer.failureLines("   ")).isEmpty();
        assertThat(HtmlRenderer.failureLines("plain message"))
                .containsExactly("plain message");
        //  分段符两侧的额外空白会被规范化掉（strip 后仍是两段，不产出空行）
        assertThat(HtmlRenderer.failureLines("a  |  b")).containsExactly("a", "b");
        //  单个竖线（两侧无空格）不是分段符：HTML 片段里常见，不能误切
        assertThat(HtmlRenderer.failureLines("a|b")).containsExactly("a|b");
    }

    @Test
    @DisplayName("Trace：播放按钮只开 Viewer 首页，Tips 在路径上方")
    void tracePlayButtonOpensViewerHomeAndTipIsAbovePath() throws Exception {
        TestOutcome o = OutcomeFixtures.full();
        o.setTracePath("login-12-x/trace.zip");
        String html = HtmlRenderer.renderToString(o);

        assertThat(html)
                .contains("href=\"https://trace.playwright.dev\"")
                .doesNotContain("trace.playwright.dev/?trace=")
                .doesNotContain("btn-open-trace");
        assertThat(html.indexOf("class=\"trace-hint\""))
                .isLessThan(html.indexOf("class=\"trace-path-row\""));
    }

    @Test
    @DisplayName("per-scenario 页提供回到所属 Feature 的链接与面包屑")
    void scenarioLinksBackToFeature() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());

        // 2026-10-22：顶部返回链接固定文案 "← back to feature"（feature 名过长会挤压标题），
        // 指向仍必须是当前 scenario 所属 feature 页。
        assertThat(html).contains("← back to feature");
        assertThat(html).contains("../feature-authentication.html");
        assertThat(html).contains("<a href=\"../feature-authentication.html\">Authentication</a>");
        // 仍保留回 Overview 的路径
        assertThat(html).contains("<a href=\"../index.html\">Overview</a>");
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

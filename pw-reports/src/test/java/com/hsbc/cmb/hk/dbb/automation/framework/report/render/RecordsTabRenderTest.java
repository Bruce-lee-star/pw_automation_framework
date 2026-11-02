package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「Playwright records」Tab 渲染门禁。
 *
 * <p>背景（2026-10-21）：录屏一直进不了报告，根因在 Java 侧（close 之后再取 {@code context.pages()}
 * 恒为空 + 相对/绝对路径比较让 move 落到自身）；模板侧同时要保证「有录像」时给出正确的相对地址、
 * 「无录像」时给出可照做的开启方式（旧文案把 Javadoc 的 {@code {@code ...}} 原样吐了出来）。
 */
class RecordsTabRenderTest {

    @Test
    @DisplayName("有录像：渲染 video 播放器，本地预览用 ../ 回退一级到报告根")
    void rendersPlayerWithRelativeSource() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("rec-1");
        o.setName("records");
        o.setResult(TestResult.SUCCESS);
        o.setVideoPath("rec-1/records/video-abc.webm");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("<video class=\"record-video\"");
        assertThat(html).contains("src=\"../rec-1/records/video-abc.webm\"");
        assertThat(html).doesNotContain("No video was recorded");
    }

    @Test
    @DisplayName("无录像：空态文案给出真实配置键，不泄漏 Javadoc 语法")
    void emptyStateShowsRealConfigKey() throws Exception {
        TestOutcome o = new TestOutcome();
        o.setOutcomeId("rec-2");
        o.setName("no records");
        o.setResult(TestResult.SUCCESS);
        //  底部 Tab 区仅在「有 console / trace / 录像」时渲染，故这里给一条 console 让 Tab 出现
        o.getConsoleOutput().add("SOME_OUTPUT");

        String html = HtmlRenderer.renderToString(o);

        //  英文空态文案（2026-10-22 与 Tab 标签/按钮统一，避免中英混排）
        assertThat(html).contains("No video was recorded");
        assertThat(html).contains("playwright.context.recordVideo.enabled=true");
        assertThat(html).doesNotContain("{@code");
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-case 页 "Playwright Trace" 区契约。
 *
 * <p><b>2026-10-22 变更（用户要求"Play In Trace Viewer 只打开 https://trace.playwright.dev/"）</b>：
 * 早先这里实现过「?trace=&lt;可访问URL&gt; 远程拉取」的二态深链（托管拼绝对 URL、本地用 JS
 * 把相对路径解析成绝对 URL 再拼）。问题是官方 Viewer 的 {@code ?trace=} 强依赖服务端 CORS，
 * 而 {@code file://} 与 IDEA 内置预览（63342）必然被浏览器拦截 —— 拼了参数只会点开一片
 * "Could not load trace"，反而误导用户。
 *
 * <p>现契约：播放按钮是<b>普通链接，只打开 Viewer 首页</b>；要播放下载好的 trace.zip 需用户
 * 自己拖进 Viewer（Tip 已说明）。下载按钮仍按二态给地址（托管 = baseUrl + path，本地 = 回退一级）。
 */
class TraceBarRenderTest {

    private static final String BASE_URL_PROP = "pw.report.base.url";

    @AfterEach
    void resetBaseUrl() {
        // 恢复为空（本键默认即空）；PwReportsConfig.str 直读 System.getProperty，set("") 确定性复位
        System.setProperty(BASE_URL_PROP, "");
    }

    @Test
    @DisplayName("播放按钮只打开 Viewer 首页，不再拼 ?trace= 深链（本地/托管一致）")
    void playButtonOpensViewerHomeOnly() throws Exception {
        System.setProperty(BASE_URL_PROP, "");
        TestOutcome o = OutcomeFixtures.full();
        o.setTracePath("login-12-x/trace.zip");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html)
                .contains("href=\"https://trace.playwright.dev\"")
                .contains(">Play in Trace Viewer</a>")
                //  深链与 JS 预探测整体移除：拼了参数在 file:// / IDEA 预览下必然失败
                .doesNotContain("trace.playwright.dev/?trace=")
                .doesNotContain("id=\"btn-open-trace\"")
                .doesNotContain("data-trace=")
                .doesNotContain("new URL(rel, location.href)")
                //  下载入口仍在，且锚定到 trace.zip
                .contains("Download trace.zip")
                .contains("download=\"trace.zip\"");
    }

    @Test
    @DisplayName("托管（baseUrl 存在）→ 下载用绝对 URL；播放按钮仍只开首页")
    void hostedBaseUrlStillOnlyOpensViewerHome() throws Exception {
        System.setProperty(BASE_URL_PROP, "https://ci.example/job/run42/artifact/serenity/");
        TestOutcome o = OutcomeFixtures.full();
        o.setTracePath("login-12-x/trace.zip");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html)
                .contains("href=\"https://trace.playwright.dev\"")
                .doesNotContain("trace.playwright.dev/?trace=")
                //  下载地址：baseUrl + tracePath（真实工件 URL；/ 为路径分隔符，
                //  不能编码成 %2F，否则 Jenkins 按 %2F 当单文件名 → 下载 404）
                .contains("ci.example")
                .contains("login-12-x/trace.zip")
                .contains("Download trace.zip");
    }

    @Test
    @DisplayName("Tips 位于 trace 路径上方（先讲怎么播，再给路径与操作）")
    void tipIsRenderedAboveThePathRow() throws Exception {
        System.setProperty(BASE_URL_PROP, "");
        TestOutcome o = OutcomeFixtures.full();
        o.setTracePath("login-12-x/trace.zip");

        String html = HtmlRenderer.renderToString(o);

        int tipIdx = html.indexOf("class=\"trace-hint\"");
        int pathIdx = html.indexOf("class=\"trace-path-row\"");
        assertThat(tipIdx).isPositive();
        assertThat(pathIdx).isPositive();
        assertThat(tipIdx).isLessThan(pathIdx);
    }
}

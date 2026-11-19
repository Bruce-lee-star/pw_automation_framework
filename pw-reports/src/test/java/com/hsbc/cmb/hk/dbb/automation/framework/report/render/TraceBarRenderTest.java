package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
 * 自己拖进 Viewer（Tip 已说明）。下载按钮仍按二态给地址（CI = 自动推导的 baseUrl + path，
 * 本地 = 回退一级）。
 *
 * <p>基准 URL 不再由 {@code pw.report.base.url} 配置提供：CI 直接由 Jenkins {@code BUILD_URL}
 * 与 {@code publishHTML} 的 reportName（约定 "Test Report"）推导，本地为空。
 */
class TraceBarRenderTest {

    @Test
    @DisplayName("播放按钮只打开 Viewer 首页，不再拼 ?trace= 深链（本地/托管一致）")
    void playButtonOpensViewerHomeOnly() throws Exception {
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
    @DisplayName("CI：由 Jenkins BUILD_URL + publishHTML reportName 自动推导基准 URL（无需配置）")
    void baseUrlDerivedFromJenkinsBuildUrl() {
        String base = PwReportsConfig.baseUrl(Map.of(
                "BUILD_URL", "https://ci.example/job/run42/",
                "JENKINS_PUBLISH_HTML_REPORT_NAME", "Test Report"));

        assertThat(base).isEqualTo("https://ci.example/job/run42/Test%20Report/");
    }

    @Test
    @DisplayName("CI：Jenkinsfile 改了 reportName 时可用 JENKINS_PUBLISH_HTML_REPORT_NAME 覆盖")
    void baseUrlHonorsPublishNameOverride() {
        String base = PwReportsConfig.baseUrl(Map.of(
                "BUILD_URL", "https://ci.example/job/run42/",
                "JENKINS_PUBLISH_HTML_REPORT_NAME", "PW Report"));

        assertThat(base).isEqualTo("https://ci.example/job/run42/PW%20Report/");
    }

    @Test
    @DisplayName("本地：无 BUILD_URL 时基准为空（回退 file:// 相对链接）")
    void baseUrlEmptyWithoutBuildUrl() {
        assertThat(PwReportsConfig.baseUrl(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("Tips 位于 trace 路径上方（先讲怎么播，再给路径与操作）")
    void tipIsRenderedAboveThePathRow() throws Exception {
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

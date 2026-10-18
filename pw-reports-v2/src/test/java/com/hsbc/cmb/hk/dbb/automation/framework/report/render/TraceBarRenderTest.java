package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-case 页 "Playwright Trace" 区深链契约（对齐 Playwright 官方远程 trace 模式 {@code ?trace=<可访问URL>} + CORS）：
 * <ul>
 *   <li>报告已托管（{@code pw.report.base.url} 存在，通常 CI HTTPS 制品/反代）→ 拼<b>绝对 URL</b>，公开 Viewer 跨源拉取（服务端需 CORS）；</li>
 *   <li>本地（无 baseUrl，HTTP）→ 用<b>相对路径</b>，Viewer 经 referrer + Local Network Access 解析（HTTP 绝对 URL 会撞 mixed-content）。</li>
 * </ul>
 */
class TraceBarRenderTest {

    private static final String BASE_URL_PROP = "pw.report.base.url";

    @AfterEach
    void resetBaseUrl() {
        // 恢复为空（本键默认即空）；PwReportsConfig.str 直读 System.getProperty，set("") 确定性复位
        System.setProperty(BASE_URL_PROP, "");
    }

    @Test
    @DisplayName("本地（无 baseUrl）→ 相对路径深链（Viewer 经 LNA 解析）")
    void localNoBaseUrlProducesRelativeTraceDeepLink() throws Exception {
        System.setProperty(BASE_URL_PROP, "");
        TestOutcome o = OutcomeFixtures.full();
        o.setTracePath("login-12-x/trace.zip");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("trace.playwright.dev/?trace=");
        assertThat(html).contains("login-12-x%2Ftrace.zip"); // 相对路径（?url 把 / 编为 %2F）
        assertThat(html).doesNotContain("ci.example");       // 无绝对 URL
        assertThat(html).doesNotContain("Download trace");
    }

    @Test
    @DisplayName("已托管（baseUrl 存在）→ 绝对 URL 深链（官方 CI 模式，需服务端 CORS）")
    void hostedBaseUrlProducesAbsoluteTraceDeepLink() throws Exception {
        System.setProperty(BASE_URL_PROP, "https://ci.example/job/run42/artifact/serenity/");
        TestOutcome o = OutcomeFixtures.full();
        o.setTracePath("login-12-x/trace.zip");

        String html = HtmlRenderer.renderToString(o);

        assertThat(html).contains("trace.playwright.dev/?trace=");
        assertThat(html).contains("ci.example");             // 绝对 URL = baseUrl + tracePath
        assertThat(html).contains("login-12-x%2Ftrace.zip");
        assertThat(html).doesNotContain("Download trace");
    }
}

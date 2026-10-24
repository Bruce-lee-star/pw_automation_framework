package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-scenario 页 "Route Register" 渲染门禁：注册发生在哪个步骤就附在哪个步骤下，
 * 仅在无步骤上下文（钩子等）注册时才落用例级兜底卡片。
 */
class RouteRegistrationRenderTest {

    @Test
    @DisplayName("用例级兜底卡片渲染为 Route Register（含条数）")
    void rendersCaseLevelRouteRegisterCard() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());
        assertThat(html)
                .contains("Route Register (1)")
                .contains("[Route] register GET route for");
    }

    @Test
    @DisplayName("步骤级注册挂在注册发生的那个步骤下（step-route 容器）")
    void rendersStepLevelRouteRegisterUnderItsStep() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());
        assertThat(html)
                .contains("step-route")
                .contains("[Route] register POST route for");
    }

    @Test
    @DisplayName("步骤级 Route Register 为可折叠面板，且默认收起")
    void stepLevelRouteRegisterIsCollapsible() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());
        assertThat(html)
                //  默认折叠（2026-10-20 用户要求）：无 open 属性
                //  色调类名 2026-10-21 起为 con-register（与 Route Captured 的 con-captured 区分）
                .contains("<details class=\"con-group con-register step-route\">")
                .contains("<summary>Route Register<span class=\"con-count\">1</span></summary>");
    }

    @Test
    @DisplayName("步骤级 API Request / Route Captured 按来源分栏，各自可折叠")
    void stepLevelApiPanelsAreSplitBySource() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());
        assertThat(html)
                // RestAssured 主动请求 → API Request（结构化卡片：方法徽标 + URL + 状态 + 分区）
                .contains("<summary>API Request<span class=\"con-count\">1</span></summary>")
                .contains("api-card")
                //  默认折叠（与 Route Register / Route Captured 一致）
                .contains("<details class=\"con-group con-net step-api\">")
                .contains("api-m-post\">POST</span>")
                .contains("api-url\">https://api.example.com/api/session</span>")
                .contains("api-s-bad\">401</span>")
                .contains("api-ms\">86 ms</span>")
                .contains("api-label\">Request headers</span>")
                //  JSON 体额外带 JSON 标记（模板据此改用 <pre class="api-json"> 渲染）
                .contains("api-label\">Request body<span class=\"api-tag\">JSON</span></span>")
                .contains("api-label\">Response headers</span>")
                .contains("api-label\">Response body<span class=\"api-tag\">JSON</span></span>")
                .contains("<pre class=\"api-json\">")
                // route 抓包（NET 前缀）→ Route Captured，且与 API Request 是两个面板
                .contains("<summary>Route Captured<span class=\"con-count\">1</span></summary>")
                //  2026-10-22：Route Captured 与 API Request 同构展示（不再是一段等宽原文），
                //  带序号 + 能力标签 + 方法/URL/状态/耗时
                .contains("api-seq\">1</span>")
                .contains("api-kind\">CAPTURED</span>")
                .contains("api-m-get\">GET</span>")
                .contains("api-url\">https://api.example.com/profile/list</span>")
                .contains("api-s-ok\">200</span>")
                .contains("api-ms\">18 ms</span>")
                .doesNotContain("NET GET https://api.example.com/profile/list")
                .contains("step-captured");
    }

    @Test
    @DisplayName("API 面板排在失败信息之前（先看发了什么，再看为什么失败）")
    void apiPanelsRenderBeforeFailurePanel() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());
        assertThat(html.indexOf("API Request"))
                .isLessThan(html.indexOf("failure-panel"))
                .isLessThan(html.indexOf("Stack trace"));
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-scenario 页"Route registrations"卡片渲染门禁。
 */
class RouteRegistrationRenderTest {

    @Test
    @DisplayName("per-scenario 页渲染 Route registrations 卡片（含条数）")
    void rendersRouteRegistrationsCard() throws Exception {
        String html = HtmlRenderer.renderToString(OutcomeFixtures.full());
        assertThat(html)
                .contains("Route registrations (2)")
                .contains("[Route] register GET route for")
                .contains("[Route] register POST route for");
    }
}

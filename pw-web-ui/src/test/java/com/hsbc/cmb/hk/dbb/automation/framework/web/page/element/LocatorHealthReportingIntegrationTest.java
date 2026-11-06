package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.AggregateBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.LocatorHealthHtmlRenderer;
import com.microsoft.playwright.PlaywrightException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 端到端链路：采集（pw-web-ui）→ SPI（pw-core）→ 独立页（pw-reports）。
 *
 * <p>分段单测都通过，并不能证明「装配起来真的能看到产物」——中间的 SPI 注册、依赖方向、
 * 模板取值任一处断链，表现都是「报告目录里静静少一个文件」。本用例用真实 {@code ServiceLoader}
 * 发现 + 真实渲染器，一次锁定整条链路；同时守护「主报告不被污染」这条产品约束。
 */
public class LocatorHealthReportingIntegrationTest {

    private Path dir;

    @Before
    public void setUp() throws IOException {
        LocatorHealthRecorder.reset();
        LocatorHealthReportRegistry.install(null); // 强制重新按 SPI 解析，确保走真实 provider
        dir = Files.createTempDirectory("locator-health-e2e-");
    }

    @After
    public void tearDown() throws IOException {
        LocatorHealthRecorder.reset();
        LocatorHealthReportRegistry.install(null);
        deleteRecursively(dir);
    }

    @Test
    public void frameworkProducesStandalonePageAndKeepsMainReportClean() throws Exception {
        LocatorHealthRecorder.recordUse("#login");
        LocatorHealthRecorder.recordFailure(new PlaywrightException(
                "strict mode violation: locator('#row') resolved to 3 elements"), "#row", "click");

        // 空用例列表：证明独立页只依赖采集数据，不依赖 outcome（与 index 的统计解耦）
        Path out = LocatorHealthHtmlRenderer.write(dir, List.of());

        assertNotNull("应产出 locator-health.html", out);
        String html = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue("应为定位健康度独立页", html.contains("Locator health"));
        assertTrue("应展示 strict 违例证据", html.contains("Strict violations"));
        assertTrue("违例选择器应出现", html.contains("#row"));
        assertTrue("应展示选择器用量表", html.contains("Most used selectors"));
        assertTrue("用量选择器应出现", html.contains("#login"));

        // 主报告（index）完全不引入：既无内容、也无入口链接
        String index = AggregateBuilder.renderIndexToString(List.of());
        assertFalse("index 不应含独立页链接", index.contains("locator-health.html"));
        assertFalse("index 不应内嵌违例内容", index.contains("Strict violations"));
        assertFalse("index 不应内嵌场景归因", index.contains("#row"));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}

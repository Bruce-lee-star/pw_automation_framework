package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import java.net.URI;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * 真实浏览器验证：{@link PlaywrightPageObservationProvider} 从 DOM 读取 {@code data-i18n} 并填入
 * {@link ObservedElement#nameKey()}（设计 §4.6 ② / §4.7）。
 *
 * <p>浏览器不可用时自动跳过，不阻塞构建。</p>
 */
public class PlaywrightObservationDataI18nTest {

    private static Optional<ObservedElement> byKey(List<ObservedElement> els, String nameKey) {
        return els.stream().filter(e -> nameKey.equals(e.nameKey())).findFirst();
    }

    @Test
    public void dataI18nBecomesNameKeyAndHiddenExcluded() {
        String fixture = resolveFixtureUrl();
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            harness.acquire(fixture);
            PageObservation obs = harness.observationProvider().observe("page:data-i18n");
            List<ObservedElement> els = obs.elements();

            // 1) 带 data-i18n 的链接：nameKey 精确，首选定位符是 role+key（跨语言稳定）
            ObservedElement privacy = byKey(els, "footer.privacy").orElse(null);
            assertNotNull("footer.privacy 应被观察", privacy);
            assertEquals("link", privacy.role());
            assertEquals("footer.privacy", privacy.nameKey());
            assertTrue(privacy.hasNlsKey());
            assertEquals("footer.privacy", privacy.attributes().get("data-i18n"));
            LocatorSpec p = privacy.primaryLocator();
            assertNotNull(p);
            assertTrue("首选应为 role+key 定位", p.usesNlsKey());
            assertEquals("role=link;nameKey=footer.privacy", p.canonical());

            // 2) 两个相同 key 的 submit 按钮 → 都带 login.submit，按 nth 消歧
            long submitCount = els.stream().filter(e -> "login.submit".equals(e.nameKey())).count();
            assertEquals(2, submitCount);
            assertNotNull(els.stream().filter(e -> "login.submit".equals(e.nameKey()))
                    .filter(e -> e.primaryLocator().nth() == 0).findFirst().orElse(null));
            assertNotNull(els.stream().filter(e -> "login.submit".equals(e.nameKey()))
                    .filter(e -> e.primaryLocator().nth() == 1).findFirst().orElse(null));

            // 3) checkbox 带 key（无 aria-label）：nameKey 仍精确，不依赖文案反查
            ObservedElement agree = byKey(els, "link.agree").orElse(null);
            assertNotNull(agree);
            assertEquals("checkbox", agree.role());
            assertEquals("link.agree", agree.nameKey());

            // 4) 无 data-i18n、靠 aria-label 的元素：name 精确，nameKey 为 null（回退反查）
            ObservedElement user = els.stream()
                    .filter(e -> "textbox".equals(e.role()) && "Username".equals(e.name()))
                    .findFirst().orElse(null);
            assertNotNull(user);
            assertNull(user.nameKey());
            assertEquals("username", user.attributes().get("data-testid"));
            assertFalse(user.primaryLocator().usesNlsKey());

            // 5) 无 data-i18n、仅靠 aria-label 的按钮
            ObservedElement close = els.stream()
                    .filter(e -> "button".equals(e.role()) && "Close dialog".equals(e.name()))
                    .findFirst().orElse(null);
            assertNotNull(close);
            assertNull(close.nameKey());

            // 6) 隐藏元素（display:none）被排除
            assertFalse("隐藏元素应被排除",
                    els.stream().anyMatch(e -> "hidden.btn".equals(e.nameKey())));

            // 7) 纯文本段（p，无语义 role）不被 role 观察采集（与 ARIA 快照一致；纯文本断言走 TEXT 路径）
            assertFalse("纯文本段不应被 role 观察采集",
                    els.stream().anyMatch(e -> "footer.copyright".equals(e.nameKey())));
        } catch (RuntimeException e) {
            assumeNoException("Playwright browser unavailable in this environment", e);
        } finally {
            harness.closeAll();
        }
    }

    private static String resolveFixtureUrl() {
        java.net.URL resource = PlaywrightObservationDataI18nTest.class.getResource(
                "/com/hsbc/cmb/hk/dbb/automation/framework/web/agent/observation-data-i18n-fixture.html");
        assumeTrue("observation-data-i18n-fixture.html missing on test classpath", resource != null);
        try {
            return Paths.get(URI.create(resource.toURI().toString())).toUri().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve fixture URL", e);
        }
    }
}

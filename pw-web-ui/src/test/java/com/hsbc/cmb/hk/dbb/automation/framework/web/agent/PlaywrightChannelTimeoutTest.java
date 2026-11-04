package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotBounds;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import org.junit.After;
import org.junit.Test;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * 运行期超时真正施加（v3.11，P0 第 3 项剩余）：{@link PlaywrightChannel} 把
 * {@code ActionTypePolicy.defaultTimeout} 注入每个 Playwright 调用。本类注入极短超时 resolver，
 * 在 {@code file://} fixture 上验证「不存在元素的 ASSERT_VISIBLE 在策略超时内失败，
 * 而非 Playwright 默认 30s」。浏览器不可用时按 {@code assume} 跳过，不阻塞构建。
 */
public class PlaywrightChannelTimeoutTest {

    private static final long SHORT_TIMEOUT_MS = 300L;

    private Playwright playwright;

    @After
    public void closePlaywright() {
        if (playwright != null) {
            playwright.close();
        }
    }

    private PlaywrightChannel openChannel(String fixtureUrl) throws Exception {
        playwright = Playwright.create();
        Browser browser = playwright.chromium()
                .launch(new BrowserType.LaunchOptions().setHeadless(true));
        BrowserContext ctx = browser.newContext();
        Page page = ctx.newPage();
        page.navigate(fixtureUrl);
        return new PlaywrightChannel(page, browser, ctx, null, SnapshotBounds.DEFAULT,
                a -> SHORT_TIMEOUT_MS);
    }

    private static String resolveFixtureUrl() {
        java.net.URL resource = PlaywrightChannelTimeoutTest.class.getResource(
                "/com/hsbc/cmb/hk/dbb/automation/framework/web/agent/stepplan-fixture.html");
        assumeTrue("stepplan-fixture.html missing on test classpath", resource != null);
        try {
            return Paths.get(URI.create(resource.toURI().toString())).toUri().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve fixture URL", e);
        }
    }

    @Test
    public void policyTimeoutDoesNotFireForPresentElement() {
        String url = resolveFixtureUrl();
        PlaywrightChannel ch = null;
        try {
            ch = openChannel(url);
        } catch (Exception e) {
            assumeNoException("Playwright browser unavailable", e);
            return;
        }
        try {
            Command visible = Command.assertVisible(LocatorSpec.role("textbox", "Username"));
            CommandResult r = ch.execute(visible);
            assertTrue(r.success());
        } finally {
            if (ch != null) {
                ch.close();
            }
        }
    }

    @Test
    public void policyTimeoutAbortsAbsentElementInsteadOfDefault30s() {
        String url = resolveFixtureUrl();
        PlaywrightChannel ch = null;
        try {
            ch = openChannel(url);
        } catch (Exception e) {
            assumeNoException("Playwright browser unavailable", e);
            return;
        }
        try {
            Command absent = Command.assertVisible(LocatorSpec.role("button", "DefinitelyMissing"));
            long start = System.nanoTime();
            CommandResult r = ch.execute(absent);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertFalse(r.success());
            assertTrue("should abort at policy timeout (~300ms), not Playwright default 30s; elapsed="
                    + elapsedMs, elapsedMs < 5_000L);
        } finally {
            if (ch != null) {
                ch.close();
            }
        }
    }
}

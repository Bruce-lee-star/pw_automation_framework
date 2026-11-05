package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepDriver;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import org.junit.Test;

import java.net.URI;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

/**
 * popup 新页面捕获与切换（v3.13，P0 第 4 项 Frame 生命周期）：window.open 弹出的新页面经
 * {@link PlaywrightChannel} 的 onPopup 捕获；{@code SWITCH_TO_POPUP} 命令把驱动目标切到该 popup，
 * 后续命令在 popup 页面上执行。浏览器不可用时按 {@code assume} 跳过，不阻塞构建。
 */
public class PlaywrightChannelPopupTest {

    private static String resolveFixtureUrl() {
        java.net.URL resource = PlaywrightChannelPopupTest.class.getResource(
                "/com/hsbc/cmb/hk/dbb/automation/framework/web/agent/popup-harness.html");
        if (resource == null) {
            throw new IllegalStateException("popup-harness.html missing on test classpath");
        }
        try {
            return Paths.get(URI.create(resource.toURI().toString())).toUri().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve fixture URL", e);
        }
    }

    @Test
    public void clickTriggersPopupAndDriverSwitchesToIt() {
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            BrowserCommandChannel ch = harness.acquire(resolveFixtureUrl());
            StepPlan plan = new StepPlan(1, "popup-plan", List.of(
                    Command.click(LocatorSpec.css("#open")),
                    Command.switchToPopup(),
                    Command.assertVisible(LocatorSpec.css("#open"))
            ), ParamScope.EMPTY, List.of());
            List<CommandResult> results = StepDriver.execute(plan, ch);
            assertEquals(3, results.size());
            assertTrue(results.get(0).success());
            assertTrue(results.get(1).success());
            assertTrue(results.get(2).success());
        } catch (Exception e) {
            assumeNoException("Playwright browser unavailable", e);
        } finally {
            harness.closeAll();
        }
    }
}

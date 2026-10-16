package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.steps.StepFailure;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-16 修复锁定：<b>步骤级失败不拆除 Context/Page</b>（修复"步骤失败时过早关闭 context"）。
 *
 * <p>背景：{@code stepFailed / lastStepFailed / stepIgnored} 曾在步骤级就调
 * {@code cleanupAfterAbnormalTermination → clearThreadResourcesOnCaseAbort → closeContext/closePage}，
 * 场景尚未结束即拆 context，导致：① {@code stepFinished} 收尾截图拿不到 page（静默丢失）；
 * ② 后续步骤 {@code getPage()} 重建出 about:blank 新 tab；③ 与场景收尾二次拆除。
 * 现改为：步骤级只做"截图+记录"，Context/Page 一律留到场景边界（testFinished / glue @After →
 * {@code cleanupForScenario}）统一释放。</p>
 *
 * <p>本类装 mock 组合根（{@link PlaywrightRuntime} seam），断言步骤级失败<b>绝不</b>触发
 * {@code closeContext/closePage}。</p>
 */
public class PlaywrightListenerStepFailureDefersTeardownTest {

    private final ContextRegistry contextRegistry = mock(ContextRegistry.class);
    private final PageRegistry pageRegistry = mock(PageRegistry.class);
    private PlaywrightListener listener;

    @Before
    public void setUp() throws Exception {
        listener = new PlaywrightListener();
        ListenerPerfStats.resetStats();

        // 禁截图，规避浏览器依赖（与 PlaywrightListenerTest 同口径）
        Field strat = PlaywrightListener.class.getDeclaredField("screenshotStrategy");
        strat.setAccessible(true);
        strat.set(listener, ScreenshotStrategy.DISABLED);

        // web 测试 classpath 无 route 实现：注入 mock RouteLifecycle 防 NPE
        RouteLifecycle routeLifecycle = mock(RouteLifecycle.class);
        when(routeLifecycle.getCurrentCapture()).thenReturn(mock(CaptureContext.class));
        RouteLifecycleRegistry.register(routeLifecycle);

        // 装 mock 组合根：使任何 getContext/getPage 回落空、绝不初始化浏览器，且可验证关闭行为
        PlaywrightRuntime.setInstance(new PlaywrightRuntime(
                mock(BrowserRegistry.class),
                contextRegistry,
                pageRegistry,
                mock(BrowserStartup.class),
                mock(BrowserRestart.class),
                mock(BrowserCleanup.class),
                PlaywrightRuntimeState.INSTANCE));
    }

    @After
    public void tearDown() throws Exception {
        PlaywrightRuntime.resetInstance();
        PlaywrightManager.resetProvider();
        RouteLifecycleRegistry.register(null);
        ListenerPerfStats.resetStats();
        // 复位阶段标记，杜绝跨用例污染（同 PlaywrightListenerTest）
        Field phase = PlaywrightListener.class.getDeclaredField("discoveryPhaseCompleted");
        phase.setAccessible(true);
        phase.setBoolean(null, false);
    }

    private static StepFailure failure(String msg) {
        return new StepFailure(mock(ExecutedStepDescription.class), new RuntimeException(msg));
    }

    @Test
    public void stepFailed_doesNotCloseContextOrPage() {
        listener.stepFailed(failure("boom"));

        verify(contextRegistry, never()).closeContext();
        verify(pageRegistry, never()).closePage();
    }

    @Test
    public void lastStepFailed_doesNotCloseContextOrPage() {
        listener.lastStepFailed(failure("boom"));

        verify(contextRegistry, never()).closeContext();
        verify(pageRegistry, never()).closePage();
    }

    @Test
    public void stepIgnored_doesNotCloseContextOrPage() {
        listener.stepIgnored();

        verify(contextRegistry, never()).closeContext();
        verify(pageRegistry, never()).closePage();
    }
}

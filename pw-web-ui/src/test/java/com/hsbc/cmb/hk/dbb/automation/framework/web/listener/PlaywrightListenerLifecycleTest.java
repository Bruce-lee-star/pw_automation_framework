package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.listener.ListenerGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStatus;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PlaywrightListener 原生生命周期契约（DEV-V5：替代原 PlaywrightListenerTest /
 * PlaywrightListenerStepFailureDefersTeardownTest，原 框架 适配器已删除）。
 *
 * <p>覆盖关键守卫：<b>步骤级失败不得关闭 Context/Page</b>（延迟到场景边界）；
 * <b>场景结束必须正确 teardown</b>（关闭 Context/Page，替代原 adapters.testFinished 承担的职责）；
 * 轻量回调不得抛异常；性能统计可用。</p>
 */
public class PlaywrightListenerLifecycleTest {

    private PlaywrightListener listener;
    private ContextRegistry contextRegistry;
    private PageRegistry pageRegistry;
    private BrowserRegistry browserRegistry;

    @Before
    public void setUp() throws Exception {
        contextRegistry = mock(ContextRegistry.class);
        pageRegistry = mock(PageRegistry.class);
        browserRegistry = mock(BrowserRegistry.class);

        PlaywrightRuntime runtime = new PlaywrightRuntime(
                browserRegistry,
                contextRegistry,
                pageRegistry,
                mock(BrowserStartup.class),
                mock(BrowserRestart.class),
                mock(BrowserCleanup.class),
                PlaywrightRuntimeState.INSTANCE);
        PlaywrightRuntime.setInstance(runtime);

        listener = new PlaywrightListener();
        // 关闭截图（避免失败路径触碰浏览器）
        Field field = PlaywrightListener.class.getDeclaredField("screenshotStrategy");
        field.setAccessible(true);
        field.set(listener, ScreenshotStrategy.DISABLED);

        // 标记场景/步骤活跃（等价 onScenarioStarted 的守卫设置，但不触发浏览器初始化）
        ListenerGuard.guards().scenarioStarted();
        ListenerGuard.guards().stepStarted();
    }

    @After
    public void tearDown() {
        try {
            ListenerGuard.guards().endScenario();
        } catch (Exception ignore) {
            // 幂等忽略
        }
        PlaywrightRuntime.resetInstance();
    }

    private LifecycleStepResult failedStep() {
        return LifecycleStepResult.builder()
                .stepName("failing step").status(LifecycleStatus.FAILED)
                .error(new RuntimeException("boom")).build();
    }

    private LifecycleScenarioResult failedScenario() {
        return LifecycleScenarioResult.builder()
                .info(LifecycleScenarioInfo.builder().name("scenarioA").uri("f.feature").build())
                .status(LifecycleStatus.FAILED).build();
    }

    private LifecycleScenarioResult passedScenario() {
        return LifecycleScenarioResult.builder()
                .info(LifecycleScenarioInfo.builder().name("scenarioA").uri("f.feature").build())
                .status(LifecycleStatus.PASSED).build();
    }

    /** 核心守卫（DEV-V5 沿用）：步骤级失败不得关闭 Context/Page。 */
    @Test
    public void stepLevelFailure_doesNotCloseContextOrPage() {
        listener.onStepFinished(failedStep());

        verify(contextRegistry, never()).closeContext();
        verify(pageRegistry, never()).closePage();
    }

    /** 对照：场景结束必须正确 teardown（关闭 Context，替代原 adapters.testFinished）。 */
    @Test
    public void scenarioFinished_tearsDownContext() {
        listener.onScenarioFinished(failedScenario());

        verify(contextRegistry, atLeastOnce()).closeContext();
    }

    /** 轻量回调不得抛异常，且性能统计可用。 */
    @Test
    public void lightweightLifecycleCallbacks_doNotThrow() {
        listener.onTestRunStarted();
        listener.onStepStarted(LifecycleStepInfo.builder().stepName("step1").build());
        listener.onStepFinished(LifecycleStepResult.builder().stepName("step1").status(LifecycleStatus.PASSED).build());
        listener.onScenarioFinished(passedScenario());
        listener.onTestRunFinished();

        assertNotNull("性能统计应始终可用", PlaywrightListener.getPerformanceStats());
    }

    /** 步骤成功收尾不触发失败聚合/截图异常路径、不关闭场景级资源。 */
    @Test
    public void successfulStepFinish_recordsNoClose() {
        listener.onStepFinished(LifecycleStepResult.builder().stepName("ok").status(LifecycleStatus.PASSED).build());
        verify(contextRegistry, never()).closeContext();
    }
}

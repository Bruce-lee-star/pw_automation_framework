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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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

    // ══════════════ 失败后跳过后续步骤（回归护栏） ══════════════

    /** 首个失败步骤仍保留且仅保留一张失败截图（不抑制本步骤的失败证据）。 */
    @Test
    public void failedStep_capturesExactlyOneFailureScreenshot() throws Exception {
        PlaywrightListener spy = spyListener(ScreenshotStrategy.AFTER_EACH_STEP);
        beginStep(spy, "failing step");

        spy.onStepFinished(failedStep());

        verify(spy, times(1)).captureScreenshotOnFailure(any());
        verify(spy, never()).captureScreenshotAtStepLevel(anyString());
    }

    /** 失败后 Cucumber 派发的 SKIPPED 步骤不得再截图、不得再产生步骤级副作用。 */
    @Test
    public void skippedStepsAfterFailure_areNotProcessedByTheFramework() throws Exception {
        PlaywrightListener spy = spyListener(ScreenshotStrategy.AFTER_EACH_STEP);
        beginStep(spy, "failing step");
        spy.onStepFinished(failedStep());
        clearInvocations(spy);

        for (String name : new String[]{"skipped-1", "skipped-2"}) {
            spy.onStepStarted(LifecycleStepInfo.builder().stepName(name).build());
            spy.onStepFinished(skippedStep(name));
        }

        verify(spy, never()).captureScreenshotAtStepLevel(anyString());
        verify(spy, never()).captureScreenshotOnFailure(any());
        assertFalse("失败后的跳过步骤必须释放 stepDepth，避免闩锁泄漏到下一场景",
                ListenerGuard.guards().isStepActive());
    }

    /** 正常场景的成功步骤仍按 AFTER_EACH_STEP 截图（零回归）。 */
    @Test
    public void passedStep_stillCapturesScreenshotWhenScenarioIsHealthy() throws Exception {
        PlaywrightListener spy = spyListener(ScreenshotStrategy.AFTER_EACH_STEP);
        beginStep(spy, "ok");
        clearInvocations(spy);

        spy.onStepFinished(passedStep("ok"));

        verify(spy, times(1)).captureScreenshotAtStepLevel("ok");
    }

    /** 新场景复位失败闩锁，截图能力恢复（防跨场景抑制）。 */
    @Test
    public void newScenario_resetsFailureLatchAndResumesScreenshots() throws Exception {
        PlaywrightListener spy = spyListener(ScreenshotStrategy.AFTER_EACH_STEP);
        beginStep(spy, "failing step");
        spy.onStepFinished(failedStep());

        ListenerGuard.guards().endScenario();
        ListenerGuard.guards().scenarioStarted();
        beginStep(spy, "fresh");
        clearInvocations(spy);

        spy.onStepFinished(passedStep("fresh"));

        verify(spy, times(1)).captureScreenshotAtStepLevel("fresh");
    }

    /** 构造可观测截图调用的 spy：真实截图入口被 stub，避免触碰浏览器。 */
    private PlaywrightListener spyListener(ScreenshotStrategy strategy) throws Exception {
        PlaywrightListener fresh = new PlaywrightListener();
        Field field = PlaywrightListener.class.getDeclaredField("screenshotStrategy");
        field.setAccessible(true);
        field.set(fresh, strategy);
        PlaywrightListener spy = spy(fresh);
        doNothing().when(spy).captureScreenshotAtStepLevel(anyString());
        doNothing().when(spy).captureScreenshotOnFailure(any());
        return spy;
    }

    /** 抵消 setUp 预置的 stepDepth，再按真实顺序派发一次步骤开始。 */
    private void beginStep(PlaywrightListener target, String name) {
        ListenerGuard.guards().stepFinished();
        target.onStepStarted(LifecycleStepInfo.builder().stepName(name).build());
    }

    private LifecycleStepResult skippedStep(String name) {
        return LifecycleStepResult.builder().stepName(name).status(LifecycleStatus.SKIPPED).build();
    }

    private LifecycleStepResult passedStep(String name) {
        return LifecycleStepResult.builder().stepName(name).status(LifecycleStatus.PASSED).build();
    }
}

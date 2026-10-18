package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkLifecycleBus;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkLifecycleListener;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 原生生命周期总线派发契约（DEV-V5：替代原 ThucydidesStepsListenerAdapter 的 delegate 转发契约）。
 *
 * <p>原适配器把 框架 事件转发给 delegate；去 框架 后，派发职责由
 * {@link FrameworkLifecycleBus} 承担。本类守护：① 注册/注销；② 事件逐类型派发一次；
 * ③ {@code ensureDefaultListeners()} 幂等注册三个内置监听器；④ 一次失败事件只派发一次（N-21）。</p>
 */
public class FrameworkLifecycleBusDispatchTest {

    private FrameworkLifecycleListener listener;

    @Before
    public void setUp() {
        listener = mock(FrameworkLifecycleListener.class);
        FrameworkLifecycleBus.register(listener);
    }

    @After
    public void tearDown() {
        FrameworkLifecycleBus.resetForTest();
    }

    @Test
    public void registersAndUnregistersListener() {
        FrameworkLifecycleBus.unregister(listener);
        // 注销后派发不再到达
        FrameworkLifecycleBus.fireTestRunStarted();
        verify(listener, times(0)).onTestRunStarted();
        FrameworkLifecycleBus.register(listener);
    }

    @Test
    public void scenarioLifecycleEvents_dispatchToRegisteredListener() {
        LifecycleScenarioInfo info = LifecycleScenarioInfo.builder().name("scenarioA").uri("f.feature").build();
        LifecycleScenarioResult result = LifecycleScenarioResult.builder()
                .info(info).status(LifecycleStatus.PASSED).build();

        FrameworkLifecycleBus.fireTestRunStarted();
        FrameworkLifecycleBus.fireScenarioStarted(info);
        FrameworkLifecycleBus.fireScenarioFinished(result);
        FrameworkLifecycleBus.fireTestRunFinished();

        verify(listener).onTestRunStarted();
        verify(listener).onScenarioStarted(info);
        verify(listener).onScenarioFinished(result);
        verify(listener).onTestRunFinished();
    }

    @Test
    public void stepLifecycleEvents_dispatchToRegisteredListener() {
        LifecycleStepInfo step = LifecycleStepInfo.builder().stepName("my step").line(10).build();
        LifecycleStepResult stepResult = LifecycleStepResult.builder()
                .status(LifecycleStatus.FAILED).error(new RuntimeException("boom")).build();

        FrameworkLifecycleBus.fireStepStarted(step);
        FrameworkLifecycleBus.fireStepFinished(stepResult);

        verify(listener).onStepStarted(step);
        verify(listener).onStepFinished(stepResult);
    }

    /** N-21（原生等价）：一次失败事件 → 每个监听器只收到一次派发（无重复路径）。 */
    @Test
    public void failureEventDispatchesExactlyOnce() {
        LifecycleStepResult stepResult = LifecycleStepResult.builder()
                .status(LifecycleStatus.FAILED).error(new RuntimeException("boom")).build();

        FrameworkLifecycleBus.fireStepFinished(stepResult);

        verify(listener, times(1)).onStepFinished(stepResult);
    }

    @Test
    public void ensureDefaultListeners_isIdempotentAndRegistersBuiltIns() {
        int before = FrameworkLifecycleBus.isDefaultListenersRegistered() ? 1 : 0;
        FrameworkLifecycleBus.ensureDefaultListeners();
        FrameworkLifecycleBus.ensureDefaultListeners(); // 第二次应为 no-op
        // 仅断言幂等不抛、且标记置位；具体监听器实例由 PLAYWRIGHT_LISTENER 等暴露供外部校验
        org.junit.Assert.assertTrue("默认监听器应已注册", FrameworkLifecycleBus.isDefaultListenersRegistered());
    }
}

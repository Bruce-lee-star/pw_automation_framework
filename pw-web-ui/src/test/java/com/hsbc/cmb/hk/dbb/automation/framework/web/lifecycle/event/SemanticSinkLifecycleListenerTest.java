package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSink;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSinks;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class SemanticSinkLifecycleListenerTest {

    private final SemanticSinkLifecycleListener listener = new SemanticSinkLifecycleListener();
    private static final String ENABLED = WebFrameworkConfig.AGENT_SEMANTIC_ENABLED.getKey();
    private String originalEnabled;

    @Before
    public void setUp() {
        originalEnabled = System.getProperty(ENABLED);
        System.setProperty(ENABLED, WebFrameworkConfig.AGENT_SEMANTIC_ENABLED.getDefaultValue());
        FrameworkLifecycleBus.resetForTest();
    }

    @After
    public void tearDown() {
        System.setProperty(ENABLED, originalEnabled == null
                ? WebFrameworkConfig.AGENT_SEMANTIC_ENABLED.getDefaultValue() : originalEnabled);
        StepPlanActionSinks.clear();
        FrameworkLifecycleBus.resetForTest();
    }

    @Test
    public void scenarioStartClearsStaleSinkFromReusedWorkerThread() {
        StepPlanActionSinks.setCurrent(mock(StepPlanActionSink.class));
        assertTrue(StepPlanActionSinks.isBound());

        listener.onScenarioStarted(LifecycleScenarioInfo.builder()
                .name("scenarioA").uri("f.feature").build());

        assertFalse(StepPlanActionSinks.isBound());
    }

    @Test
    public void scenarioFinishClearsSinkForNextScenario() {
        StepPlanActionSinks.setCurrent(mock(StepPlanActionSink.class));
        LifecycleScenarioInfo info = LifecycleScenarioInfo.builder()
                .name("scenarioA").uri("f.feature").build();

        listener.onScenarioFinished(LifecycleScenarioResult.builder()
                .info(info).status(LifecycleStatus.PASSED).build());

        assertFalse(StepPlanActionSinks.isBound());
    }

    @Test
    public void disabledByDefaultDoesNotRegisterSinkCleanupListener() {
        FrameworkLifecycleBus.ensureDefaultListeners();

        assertTrue(FrameworkLifecycleBus.isDefaultListenersRegistered());
        assertFalse("默认关闭时不应注册 agent 监听器",
                FrameworkLifecycleBus.isRegistered(SemanticSinkLifecycleListener.class));
    }

    @Test
    public void enablingAgentRegistersSinkCleanupListener() {
        System.setProperty(ENABLED, "true");

        FrameworkLifecycleBus.ensureDefaultListeners();

        assertTrue("开启后业务零钩子依赖默认注册",
                FrameworkLifecycleBus.isRegistered(SemanticSinkLifecycleListener.class));
    }
}

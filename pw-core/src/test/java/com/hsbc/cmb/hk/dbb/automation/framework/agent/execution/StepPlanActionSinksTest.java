package com.hsbc.cmb.hk.dbb.automation.framework.agent.execution;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StepPlanActionSinksTest {

    private String originalEnabled;

    @Before
    public void setUp() {
        originalEnabled = System.getProperty(ConfigRegistry.AGENT_SEMANTIC_ENABLED.key());
        System.setProperty(ConfigRegistry.AGENT_SEMANTIC_ENABLED.key(), "true");
        StepPlanActionSinks.resetProviderForTest();
        TestStepPlanActionSinkProvider.CREATED.set(0);
    }

    @After
    public void tearDown() {
        // 显式设回默认值；不用 clearProperty（Serenity 配置快照不会回落）
        System.setProperty(ConfigRegistry.AGENT_SEMANTIC_ENABLED.key(),
                originalEnabled == null
                        ? ConfigRegistry.AGENT_SEMANTIC_ENABLED.defaultValue() : originalEnabled);
        StepPlanActionSinks.resetProviderForTest();
    }

    @Test
    public void registrySwitchDefaultsToDisabledAndIsLiveRead() {
        String key = ConfigRegistry.AGENT_SEMANTIC_ENABLED.key();

        System.setProperty(key, ConfigRegistry.AGENT_SEMANTIC_ENABLED.defaultValue());
        assertFalse("未配置时默认关闭", ConfigRegistry.AGENT_SEMANTIC_ENABLED.booleanValue());

        System.setProperty(key, "true");
        assertTrue(ConfigRegistry.AGENT_SEMANTIC_ENABLED.booleanValue());
        System.setProperty(key, "yes");
        assertTrue(ConfigRegistry.AGENT_SEMANTIC_ENABLED.booleanValue());

        System.setProperty(key, "nonsense");
        assertFalse("非法值 fail-closed", ConfigRegistry.AGENT_SEMANTIC_ENABLED.booleanValue());
    }

    @Test
    public void spiIsResolvedLazilyAndReusedWithinOneThread() {
        assertFalse(StepPlanActionSinks.isBound());

        StepPlanActionSink first = StepPlanActionSinks.requireCurrent();

        assertNotNull(first);
        assertSame(first, StepPlanActionSinks.requireCurrent());
        assertEquals(1, TestStepPlanActionSinkProvider.CREATED.get());
        assertTrue(StepPlanActionSinks.isBound());
    }

    @Test
    public void clearForcesFreshSinkForNextScenario() {
        StepPlanActionSink first = StepPlanActionSinks.requireCurrent();
        StepPlanActionSinks.clear();

        StepPlanActionSink second = StepPlanActionSinks.requireCurrent();

        assertNotSame(first, second);
        assertEquals(2, TestStepPlanActionSinkProvider.CREATED.get());
    }

    @Test
    public void explicitBindingWinsAndNeverTouchesSpi() {
        StepPlanActionSink explicit = proxySink();
        StepPlanActionSinks.setCurrent(explicit);

        assertSame(explicit, StepPlanActionSinks.requireCurrent());
        assertEquals(0, TestStepPlanActionSinkProvider.CREATED.get());
    }

    @Test
    public void disabledAgentSkipsSpiDiscoveryButKeepsExplicitBinding() {
        System.setProperty(ConfigRegistry.AGENT_SEMANTIC_ENABLED.key(),
                ConfigRegistry.AGENT_SEMANTIC_ENABLED.defaultValue());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                StepPlanActionSinks::requireCurrent);
        assertTrue(ex.getMessage().contains(ConfigRegistry.AGENT_SEMANTIC_ENABLED.key()));
        assertEquals("关闭时不应加载/创建任何 SPI provider",
                0, TestStepPlanActionSinkProvider.CREATED.get());

        StepPlanActionSink explicit = proxySink();
        StepPlanActionSinks.setCurrent(explicit);
        assertSame("业务显式绑定不受开关影响", explicit, StepPlanActionSinks.requireCurrent());
    }

    @Test
    public void providerReturningNullFailsFast() {
        StepPlanActionSinks.setProviderForTest(() -> null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                StepPlanActionSinks::requireCurrent);
        assertTrue(ex.getMessage().contains("返回了 null"));
    }

    @Test
    public void missingProviderGivesActionableError() {
        StepPlanActionSinks.setProviderForTest(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                StepPlanActionSinks::requireCurrent);
        assertTrue(ex.getMessage().contains("StepPlanActionSinkProvider"));
        assertTrue(ex.getMessage().contains("META-INF/services"));
    }

    @Test
    public void providerExceptionIsWrappedWithContext() {
        StepPlanActionSinks.setProviderForTest(() -> {
            throw new IllegalStateException("boom");
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                StepPlanActionSinks::requireCurrent);
        assertTrue(ex.getMessage().contains("创建失败"));
    }

    private static StepPlanActionSink proxySink() {
        return (StepPlanActionSink) Proxy.newProxyInstance(
                StepPlanActionSink.class.getClassLoader(),
                new Class<?>[]{StepPlanActionSink.class},
                (proxy, method, args) -> null);
    }
}

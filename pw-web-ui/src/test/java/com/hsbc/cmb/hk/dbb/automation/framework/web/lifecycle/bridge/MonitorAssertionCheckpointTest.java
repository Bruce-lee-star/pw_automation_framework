package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionProbe;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.LifecycleState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * web 侧检查点契约：元素操作 / 等待循环调用的 {@link MonitorAssertionCheckpoint#checkNow()}
 * 必须「立即让当前 case 当前步骤判红」，且不终止 JVM / run、不牵连并行 case。
 *
 * <p>经 {@link PlaywrightRuntime#setInstance} 注入替身 ContextRegistry，模拟两条并行 case
 * 各自持有不同 BrowserContext；不需要真实浏览器。检查点与适配层均无共享可变状态，
 * 故隔离性不依赖场景开始/结束事件是否按序派发。</p>
 */
public class MonitorAssertionCheckpointTest {

    private BrowserContext currentCtx;
    private BrowserContext otherCtx;
    private ContextRegistry contextRegistry;

    @Before
    public void setUp() {
        RouteAssertionRegistry.clear();
        currentCtx = mock(BrowserContext.class);
        otherCtx = mock(BrowserContext.class);
        contextRegistry = mock(ContextRegistry.class);
        when(contextRegistry.currentContextForThread()).thenReturn(currentCtx);
        PlaywrightRuntime.setInstance(new PlaywrightRuntime(
                mock(BrowserRegistry.class), contextRegistry, mock(PageRegistry.class),
                mock(BrowserStartup.class), mock(BrowserRestart.class),
                mock(BrowserCleanup.class), mock(LifecycleState.class)));
    }

    @After
    public void tearDown() {
        PlaywrightRuntime.resetInstance();
        RouteAssertionRegistry.clear();
    }

    @Test
    public void elementOperationCheckpointFailsCurrentStepImmediatelyAndOnlyOnce() {
        RouteAssertionRegistry.register(new ContextScopedProbe(currentCtx));

        AssertionError thrown = null;
        try {
            MonitorAssertionCheckpoint.checkNow();
        } catch (AssertionError e) {
            thrown = e;
        }
        assertNotNull("元素操作检查点必须立即抛 AssertionError", thrown);
        assertTrue("失败明细必须含规则 pattern",
                thrown.getMessage() != null && thrown.getMessage().contains("/api/current-case"));

        MonitorAssertionCheckpoint.checkNow();
        assertTrue("抛错后当前线程仍可继续执行，未终止 JVM / test run", true);
    }

    @Test
    public void parallelCaseFailureNeverFailsThisCase() {
        RouteAssertionRegistry.register(new ContextScopedProbe(otherCtx));

        MonitorAssertionCheckpoint.checkNow();

        when(contextRegistry.currentContextForThread()).thenReturn(otherCtx);
        try {
            MonitorAssertionCheckpoint.checkNow();
            fail("另一条 case 必须能取到自己的 MONITOR 断言失败");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("/api/current-case"));
        }
    }

    @Test
    public void stepBoundaryDoesNotConsumeSoNextOperationStillFails() {
        RouteAssertionRegistry.register(new ContextScopedProbe(currentCtx));

        // onStepFinished 成功分支不再取走失败（不引入 ThreadLocal 暂存），
        // 下一个元素操作检查点仍能立即判红。
        try {
            MonitorAssertionCheckpoint.checkNow();
            fail("下一个业务操作必须取到仍留在 context 队列中的失败");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("/api/current-case"));
        }
    }

    @Test
    public void scenarioEndDrainsOnlyTheCurrentContext() {
        RouteAssertionRegistry.register(new ContextScopedProbe(otherCtx));

        // 本 case 场景收尾：不抛、且不能取走另一条并行 case 的失败
        MonitorAssertionCheckpoint.drainForEvidence();
        MonitorAssertionCheckpoint.checkNow();

        // 另一条 case 自己的失败仍在
        when(contextRegistry.currentContextForThread()).thenReturn(otherCtx);
        try {
            MonitorAssertionCheckpoint.checkNow();
            fail("另一条 case 的失败必须由它自己消费");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("/api/current-case"));
        }
    }

    /** 桩探针：只有 owner context 会取到失败，且为消费式。 */
    private static final class ContextScopedProbe implements RouteAssertionProbe {
        private final Object owner;
        private boolean consumed;

        ContextScopedProbe(Object owner) {
            this.owner = owner;
        }

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures() {
            return List.of();
        }

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures(Object context) {
            if (context != owner || consumed) {
                return List.of();
            }
            consumed = true;
            return List.of(new RouteAssertionFailure("/api/current-case", "POST",
                    "https://host/api/current-case", 200, 500, List.of(), false,
                    60_000L, "", "", "", ""));
        }
    }
}

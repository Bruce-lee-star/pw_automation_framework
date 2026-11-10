package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * MONITOR 断言「当前 case 立即判红」检查点契约。
 *
 * <p>核心不变式：检查点<b>不持有任何状态</b>，失败始终留在按 context 归属的消费式队列里；
 * 因此并行隔离不依赖线程、不依赖生命周期事件的派发顺序。</p>
 */
public class RouteAssertionCheckpointTest {

    private static final Object CONTEXT_A = new Object();
    private static final Object CONTEXT_B = new Object();

    @Before
    public void setUp() {
        RouteAssertionRegistry.clear();
        RouteAssertionVerdict.clear();
    }

    @After
    public void tearDown() {
        RouteAssertionRegistry.clear();
        RouteAssertionVerdict.clear();
    }

    /** 桩探针：只有 owner context 会取到失败；全局无参通道一旦被调用即视为隔离失效。 */
    private static final class ContextScopedProbe implements RouteAssertionProbe {
        private final Object owner;
        private boolean consumed;
        private int globalDrainCalls;

        ContextScopedProbe(Object owner) {
            this.owner = owner;
        }

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures() {
            globalDrainCalls++;
            return List.of();
        }

        @Override
        public List<RouteAssertionFailure> drainAndResolveFailures(Object context) {
            if (context != owner || consumed) {
                return List.of();
            }
            consumed = true;
            return List.of(failure("/api/owner-only"));
        }
    }

    @Test
    public void checkNowFailsCurrentStepImmediatelyAndIsConsumedOnce() {
        RouteAssertionRegistry.register(new ContextScopedProbe(CONTEXT_A));
        AssertionError thrown = null;
        try {
            RouteAssertionCheckpoint.checkNow(CONTEXT_A);
        } catch (AssertionError e) {
            thrown = e;
        }
        assertNotNull("MONITOR 断言定案必须在业务线程立即抛 AssertionError", thrown);
        assertTrue("失败明细必须含规则 pattern",
                thrown.getMessage() != null && thrown.getMessage().contains("/api/owner-only"));

        // 消费式幂等：同一批失败不得在后续步骤重复抛
        RouteAssertionCheckpoint.checkNow(CONTEXT_A);
    }

    @Test
    public void foreignContextFailureNeverFailsThisCase() {
        ContextScopedProbe probe = new ContextScopedProbe(CONTEXT_A);
        RouteAssertionRegistry.register(probe);

        RouteAssertionCheckpoint.checkNow(CONTEXT_B);
        assertEquals("case 级判定绝不能走全局 drain", 0, probe.globalDrainCalls);

        try {
            RouteAssertionCheckpoint.checkNow(CONTEXT_A);
            fail("A case 必须能取到自己的 MONITOR 断言失败");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("/api/owner-only"));
        }
    }

    @Test
    public void failureStaysInContextQueueUntilItsOwnCaseConsumesIt() {
        RouteAssertionRegistry.register(new ContextScopedProbe(CONTEXT_A));

        // 模拟「步骤结束回调什么都不做」：失败不被取走、也不进入任何 ThreadLocal 暂存。
        // 另一个 case 的检查点不会误取，本 case 下一个业务操作仍能立即判红。
        RouteAssertionCheckpoint.checkNow(CONTEXT_B);
        try {
            RouteAssertionCheckpoint.checkNow(CONTEXT_A);
            fail("未被消费的失败必须仍留在 A case 的 context 队列中");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("/api/owner-only"));
        }
    }

    @Test
    public void drainForEvidenceConsumesWithoutThrowing() {
        RouteAssertionRegistry.register(new ContextScopedProbe(CONTEXT_A));

        RouteAssertionCheckpoint.drainForEvidence(CONTEXT_A);
        RouteAssertionCheckpoint.checkNow(CONTEXT_A);
    }

    /**
     * 回归用户诉求：「最后一步内完全无框架业务操作时，失败只在场景收尾被消费」——
     * 此时不能再只留痕，必须登记判决供 Runner 投影成 case failure。
     */
    @Test
    public void drainForEvidenceRegistersVerdictInsteadOfOnlyLogging() {
        RouteAssertionRegistry.register(new ContextScopedProbe(CONTEXT_A));

        RouteAssertionCheckpoint.drainForEvidence(CONTEXT_A);

        AssertionError verdict = RouteAssertionVerdict.takeAndClear();
        assertNotNull("晚到失败必须登记判决，否则 case 会假绿", verdict);
        assertTrue("判决必须保留失败明细", verdict.getMessage().contains("/api/owner-only"));
        assertNull("判决必须消费式，不得重复投影", RouteAssertionVerdict.peek());
    }

    @Test
    public void drainForEvidenceOnForeignContextLeavesOwnerFailureIntact() {
        RouteAssertionRegistry.register(new ContextScopedProbe(CONTEXT_A));

        RouteAssertionCheckpoint.drainForEvidence(CONTEXT_B);
        try {
            RouteAssertionCheckpoint.checkNow(CONTEXT_A);
            fail("场景收尾只能消费本 case context 的失败");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("/api/owner-only"));
        }
    }

    @Test
    public void absentProbeAndNullContextAreNoOp() {
        RouteAssertionCheckpoint.checkNow(null);
        RouteAssertionCheckpoint.checkNow(CONTEXT_A);
        RouteAssertionCheckpoint.drainForEvidence(null);
        RouteAssertionCheckpoint.drainForEvidence(CONTEXT_A);
    }

    private static RouteAssertionFailure failure(String pattern) {
        return new RouteAssertionFailure(pattern, "POST", "https://host" + pattern,
                200, 500, List.of(), false, 60_000L, "", "", "", "");
    }
}

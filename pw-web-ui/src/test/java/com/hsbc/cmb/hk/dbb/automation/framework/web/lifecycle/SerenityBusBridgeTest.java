package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.SerenityBusBridge;
import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.steps.StepFailure;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

/**
 * Serenity 桥接单测（设计文档 9.10-⑤）：断言编排线程把并发任务失败经 {@link StepEventBus#testFailed} 正确标记，
 * 且失败回放具备异常安全性（监听器异常不污染原始失败语义）。
 */
public class SerenityBusBridgeTest {

    /** 捕获 testFailed 事件的监听器（复用 Serenity 基类，仅覆盖测试方法）。 */
    private static final class CapturingListener extends BaseStepListener {
        final List<Throwable> failures = new ArrayList<>();

        CapturingListener() {
            super(new File("target/serenity-bridge-test"));
        }

        @Override
        public void testFailed(TestOutcome result, Throwable throwable) {
            Throwable t = throwable;
            if (t == null) {
                t = new RuntimeException(result != null ? result.getTitle() : "testFailed");
            }
            //  解包到根因（replayFailures 经 valueOrThrow 透传 CompletionException，根因为真实失败）
            while (t.getCause() != null && t.getCause() != t) {
                t = t.getCause();
            }
            failures.add(t);
        }
    }

    private static CapturingListener bind() {
        CapturingListener listener = new CapturingListener();
        StepEventBus.getEventBus().registerListener(listener);
        return listener;
    }

    private static void unbind(CapturingListener listener) {
        try {
            StepEventBus.getEventBus().dropListener(listener);
        } catch (Throwable ignored) {
            //  离线环境 dropListener 不存在或失败均不致命
        }
    }

    @Test
    public void orchestrationThreadMarksEachFailureViaTestFailed() {
        CapturingListener listener = bind();
        try {
            List<ContextTaskResult<?>> results = new ArrayList<>();
            results.add(ContextTaskResult.success("ok", 1, "t1", 1L, List.of()));
            results.add(ContextTaskResult.failure("bad1", new RuntimeException("boom1"), "t2", 2L, List.of()));
            results.add(ContextTaskResult.failure("bad2", new TimeoutException("slow"), "t3", 3L, List.of()));

            try {
                SerenityBusBridge.replayFailures(results);
                fail("expected CompletionException");
            } catch (CompletionException ce) {
                //  汇总异常应被抛出（既有断言语义保留）
                assertTrue(ce.getMessage().contains("2 task(s) failed"));
            }
            //  每个失败项都经 StepEventBus.testFailed 标记到 Serenity
            assertEquals(2, listener.failures.size());
            boolean sawBoom = listener.failures.stream()
                    .anyMatch(t -> t.getMessage() != null && t.getMessage().contains("boom1"));
            boolean sawSlow = listener.failures.stream()
                    .anyMatch(t -> t.getMessage() != null && t.getMessage().contains("slow"));
            assertTrue("boom1 marked", sawBoom);
            assertTrue("slow marked", sawSlow);
        } finally {
            unbind(listener);
        }
    }

    @Test
    public void allSuccessMarksNothingAndDoesNotThrow() {
        CapturingListener listener = bind();
        try {
            List<ContextTaskResult<?>> results = new ArrayList<>();
            results.add(ContextTaskResult.success("ok1", 1, "t1", 1L, List.of()));
            results.add(ContextTaskResult.success("ok2", 2, "t2", 1L, List.of()));

            SerenityBusBridge.replayFailures(results); // 不应抛
            assertTrue("no Serenity failures marked", listener.failures.isEmpty());
        } finally {
            unbind(listener);
        }
    }

    @Test
    public void listenerExceptionDoesNotEscapeReplay() {
        //  模拟 Serenity 监听器内部异常：回放仍须抛出汇总 CompletionException，不向上泄露监听器异常
        BaseStepListener explosive = new BaseStepListener(new File("target/serenity-bridge-test")) {
            @Override
            public void testFailed(TestOutcome result, Throwable throwable) {
                throw new RuntimeException("listener boom");
            }
        };
        StepEventBus.getEventBus().registerListener(explosive);
        try {
            List<ContextTaskResult<?>> results = new ArrayList<>();
            results.add(ContextTaskResult.failure("bad", new RuntimeException("x"), "t1", 1L, List.of()));
            try {
                SerenityBusBridge.replayFailures(results);
                fail("expected CompletionException");
            } catch (CompletionException ce) {
                assertFalse(ce.getMessage().contains("listener boom"));
            }
        } finally {
            try {
                StepEventBus.getEventBus().dropListener(explosive);
            } catch (Throwable ignored) {
                //  离线环境忽略
            }
        }
    }

    /** 捕获 stepFailed 事件的监听器（用于证明"已广播的失败不可撤回"）。 */
    private static final class StepFailureCounter extends BaseStepListener {
        final List<Throwable> seen = new ArrayList<>();

        StepFailureCounter() {
            super(new File("target/serenity-bridge-test"));
        }

        @Override
        public void stepFailed(StepFailure failure) {
            seen.add(failure.getException());
        }
    }

    /**
     * 业务捕获并恢复 {@code @Step} 异常后，{@link SerenityBusBridge#clearStepFailureState()} 必须：
     * ① 解除"后续 @Step 代理步骤被 StepInterceptor 静默跳过"；② 但【不】撤回已广播/已入 TestResultTally
     * 的失败（Serenity 无公开移除 API）——报告仍留红，这正是"重试应放在 @Step 内部"的原因。
     */
    @Test
    public void clearStepFailureState_resetsSkipFlag_butDoesNotRetractRecordedFailure() {
        StepEventBus bus = StepEventBus.getEventBus();
        StepFailureCounter counter = new StepFailureCounter();
        bus.registerListener(counter);
        try {
            bus.clearStepFailures(); // 线程级总线可能被同线程其它用例置位：先复位
            assertFalse("前置：无失败", bus.aStepInTheCurrentTestHasFailed());

            // ① @Step 代理步骤抛异常 → 总线置位 + 失败已广播/入 tally（业务随后 try/catch 恢复）
            bus.stepFailed(new StepFailure(mock(ExecutedStepDescription.class), new RuntimeException("boom")));
            assertTrue("失败后 StepInterceptor 将跳过后续 @Step 步骤", bus.aStepInTheCurrentTestHasFailed());
            assertEquals("失败已广播（不可撤回）", 1, counter.seen.size());

            // ② 业务恢复后调用框架豁口：解除"后续步骤被跳过"
            SerenityBusBridge.clearStepFailureState();
            assertFalse("后续 @Step 代理步骤恢复执行", bus.aStepInTheCurrentTestHasFailed());

            // ③ 诚实边界：已广播/已入 TestResultTally 的失败不因清除而撤回 → 报告仍留红
            assertEquals("clearStepFailures 只解跳、不撤红", 1, counter.seen.size());
        } finally {
            try {
                bus.dropListener(counter);
            } catch (Throwable ignored) {
                //  离线环境忽略
            }
            bus.clearStepFailures(); // 不把失败态泄漏给同线程后续用例
        }
    }

    /** 无失败时调用应为 no-op 且绝不抛（含非 Serenity 环境降级路径）。 */
    @Test
    public void clearStepFailureState_isNoOpWithoutFailureAndNeverThrows() {
        StepEventBus bus = StepEventBus.getEventBus();
        bus.clearStepFailures();
        assertFalse(bus.aStepInTheCurrentTestHasFailed());

        SerenityBusBridge.clearStepFailureState(); // 不应抛

        assertFalse(bus.aStepInTheCurrentTestHasFailed());
    }
}

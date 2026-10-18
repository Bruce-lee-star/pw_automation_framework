package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge.LifecycleBusBridge;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link LifecycleBusBridge} 单测（DEV-V5，去 框架）。
 *
 * <p>v2 的 {@code @Step} 拦截器自行把抛出的异常记录到报告，故本桥只需在编排线程汇总失败并抛
 * {@link CompletionException}（失败不漏报）；{@code clearStepFailureState} /
 * {@code recordCrashRecoveryIfAny} 在 v2 下降级为 no-op（不抛、无 框架 总线耦合）。
 */
public class LifecycleBusBridgeTest {

    @Test
    public void replayFailuresSurfacesAllFailuresAsCompletionException() {
        List<ContextTaskResult<?>> results = new ArrayList<>();
        results.add(ContextTaskResult.success("ok", 1, "t1", 1L, List.of()));
        results.add(ContextTaskResult.failure("bad1", new RuntimeException("boom1"), "t2", 2L, List.of()));
        results.add(ContextTaskResult.failure("bad2", new TimeoutException("slow"), "t3", 3L, List.of()));

        try {
            LifecycleBusBridge.replayFailures(results);
            fail("expected CompletionException");
        } catch (CompletionException ce) {
            assertTrue(ce.getMessage().contains("2 task(s) failed"));
            assertTrue("bad1 surfaced in summary", ce.getMessage().contains("bad1"));
            assertTrue("bad2 surfaced in summary", ce.getMessage().contains("bad2"));
        }
    }

    @Test
    public void replayFailuresWithAllSuccessDoesNotThrow() {
        List<ContextTaskResult<?>> results = new ArrayList<>();
        results.add(ContextTaskResult.success("ok1", 1, "t1", 1L, List.of()));
        results.add(ContextTaskResult.success("ok2", 2, "t2", 1L, List.of()));

        LifecycleBusBridge.replayFailures(results); // 全成功不应抛
    }

    @Test
    public void clearStepFailureStateIsNoOpAndNeverThrows() {
        // v2 下为 no-op：不抛、无状态可改
        LifecycleBusBridge.clearStepFailureState();
        LifecycleBusBridge.clearStepFailureState();
    }

    @Test
    public void recordCrashRecoveryIfAnyIsNoOpAndNeverThrows() {
        List<ContextTaskResult<?>> results = new ArrayList<>();
        results.add(ContextTaskResult.success("ok", 1, "t1", 1L, List.of()));

        LifecycleBusBridge.recordCrashRecoveryIfAny(results); // 不应抛
        LifecycleBusBridge.recordCrashRecoveryIfAny(null);    // null 安全
    }
}

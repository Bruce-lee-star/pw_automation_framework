package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 并发执行模型下的失败投影契约：{@code ConcurrentContextExecutor} 的 worker 线程里
 * MONITOR 断言抛出的 {@link AssertionError} 不会冒泡到 Cucumber，而是被包装进
 * {@link ContextTaskResult}；{@link LifecycleBusBridge#replayFailures} 是它回到编排线程的
 * 唯一出口，必须保留原始 cause，不能降级成摘要文本。
 */
public class LifecycleBusBridgeCauseTest {

    @Test
    public void replayFailuresKeepsOriginalAssertionErrorAsCause() {
        AssertionError original = new AssertionError("Route MONITOR assertion failed - /api/profile");
        ContextTaskResult<Object> failed = ContextTaskResult.failure(
                "case-a", original, "ctx-1", 12L, List.of());

        try {
            LifecycleBusBridge.replayFailures(List.of(failed));
            fail("并发任务失败必须在编排线程回放抛错");
        } catch (CompletionException ce) {
            assertSame("原始 AssertionError 必须保留在 cause 链", original, ce.getCause());
            assertTrue("摘要仍须含失败任务数", ce.getMessage().contains("1 task(s) failed"));
        }
    }

    @Test
    public void replayFailuresWithOnlySuccessesDoesNotThrow() {
        LifecycleBusBridge.replayFailures(List.of());
        LifecycleBusBridge.replayFailures(List.of(
                ContextTaskResult.success("case-a", "ok", "ctx-1", 1L, List.of())));
    }
}

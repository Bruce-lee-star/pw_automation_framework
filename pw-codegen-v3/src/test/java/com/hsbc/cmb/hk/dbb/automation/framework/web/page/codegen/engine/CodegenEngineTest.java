package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.engine;

import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodegenEngine} 不变式：单线程串行、提交顺序 = 执行顺序（seq 单调）、shutdown 后静默拒绝。
 *
 * <p>事件泵不归引擎管（它由 {@code CodegenLauncher} 的独立 daemon 线程承担），故此处不测泵。
 */
public class CodegenEngineTest {

    private CodegenEngine engine;

    @After
    public void tearDown() {
        if (engine != null) {
            engine.shutdownGracefully();
        }
    }

    @Test
    public void runsAllTasksOnSingleThreadInSubmissionOrder() throws InterruptedException {
        engine = new CodegenEngine(null);
        final int n = 200;
        final CountDownLatch done = new CountDownLatch(n);
        final List<Integer> order = Collections.synchronizedList(new ArrayList<>(n));
        final Set<String> threads = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < n; i++) {
            final int idx = i;
            engine.submit(CodegenEngine.Kind.PICK, page(), () -> {
                threads.add(Thread.currentThread().getName());
                order.add(idx);
                done.countDown();
            });
        }

        assertTrue("所有任务应在 5s 内执行完", done.await(5, TimeUnit.SECONDS));

        // 单调顺序：提交顺序严格等于执行顺序（单线程串行 ⇒ seq 单调）
        for (int i = 0; i < n; i++) {
            assertEquals("执行顺序必须等于提交顺序", i, order.get(i).intValue());
        }
        // 单线程：所有任务都在同一条名为 codegen-engine 的线程上执行
        assertEquals("引擎必须只使用单线程", 1, threads.size());
        assertTrue("引擎线程名应为 codegen-engine", threads.iterator().next().contains("codegen-engine"));
    }

    @Test
    public void rejectsSubmissionAfterShutdown() {
        engine = new CodegenEngine(null);
        engine.shutdownGracefully();

        boolean accepted = engine.submit(CodegenEngine.Kind.REGEN, page(), () -> { });
        assertFalse("shutdown 后提交必须被静默拒绝", accepted);
    }

    private static Page page() {
        return null;   // 单测不涉及真实页面，url 取 "-"
    }
}

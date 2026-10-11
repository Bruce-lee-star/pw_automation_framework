package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 4 参重载 {@code runBoundedCapture(step, action, limitMs, recordFailureOnTimeout)} 契约
 * （2026-10-11 browser-close 根治）：
 * <ul>
 *   <li>{@code recordFailureOnTimeout=false}：超时<b>不</b>自动记 ShutdownCoordinator 失败 ——
 *       由调用方在超时路径执行补偿（如强收 driver）并自行决定记账语义（补偿成功→WARN，失败→ERROR）；</li>
 *   <li>{@code true} / 3 参重载：保持既有行为（超时记 ERROR + 计数），零变更。</li>
 * </ul>
 */
public class CloseGuardRecordFailureSwitchTest {

    private CountDownLatch release;

    @After
    public void tearDown() {
        if (release != null) {
            release.countDown();
        }
        //  与 BrowserCleanupImplRunBoundedTest 同：不清 ShutdownCoordinator（避免越界副作用），
        //  全部用差值断言隔离。
    }

    @Test
    // @DisplayName: "recordFailureOnTimeout=false 时超时不得记账（补偿语义由调用方负责）"
    public void timeoutWithRecordFailureDisabledDoesNotAccountFailure() {
        release = new CountDownLatch(1);
        long before = ShutdownCoordinator.getFailureCount();

        CloseGuard.Result rc = CloseGuard.runBoundedCapture(
                "test/no-record", () -> awaitQuietly(release), 300, false);

        assertFalse("超限必须返回 completed=false", rc.completed());
        assertEquals("false 时超时不得记账", before, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "recordFailureOnTimeout=true 时超时必须记账（与默认行为一致）"
    public void timeoutWithRecordFailureEnabledAccountsFailure() {
        release = new CountDownLatch(1);
        long before = ShutdownCoordinator.getFailureCount();

        CloseGuard.Result rc = CloseGuard.runBoundedCapture(
                "test/record", () -> awaitQuietly(release), 300, true);

        assertFalse(rc.completed());
        assertEquals("true 时必须记账", before + 1, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "3 参重载必须保持既有记账行为（零变更）"
    public void threeArgOverloadPreservesLegacyAccounting() {
        release = new CountDownLatch(1);
        long before = ShutdownCoordinator.getFailureCount();

        CloseGuard.Result rc = CloseGuard.runBoundedCapture(
                "test/legacy", () -> awaitQuietly(release), 300);

        assertFalse(rc.completed());
        assertEquals("3 参重载保持既有记账", before + 1, ShutdownCoordinator.getFailureCount());
    }

    @Test
    // @DisplayName: "关闭记账开关不影响正常完成路径"
    public void fastActionStillCompletesWithRecordFailureDisabled() {
        AtomicBoolean ran = new AtomicBoolean(false);
        long before = ShutdownCoordinator.getFailureCount();

        CloseGuard.Result rc = CloseGuard.runBoundedCapture(
                "test/fast", () -> ran.set(true), 2_000, false);

        assertTrue("限内完成必须返回 completed=true", rc.completed());
        assertTrue("动作必须真的被执行", ran.get());
        assertEquals("正常路径不记账", before, ShutdownCoordinator.getFailureCount());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

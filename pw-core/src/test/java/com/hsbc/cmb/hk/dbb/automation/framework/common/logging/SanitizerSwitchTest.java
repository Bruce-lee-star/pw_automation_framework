package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SanitizerSwitch} 重入守卫契约：只有最外层拥有标记并负责清除。
 *
 * <p>回归 2026-11-12 {@code StackOverflowError}：标记若被内层提前清除，
 * 「格式化 → 打日志 → 再格式化」的环会重新打开。
 */
public class SanitizerSwitchTest {

    @After
    public void cleanupGuard() {
        SanitizerSwitch.exit();
    }

    @Test
    public void onlyOutermostLayerOwnsTheGuard() {
        assertTrue(SanitizerSwitch.enter());
        assertFalse("第二次进入必须识别为重入", SanitizerSwitch.enter());
        SanitizerSwitch.exit();
        assertTrue("外层清除后应可重新进入", SanitizerSwitch.enter());
        SanitizerSwitch.exit();
    }

    @Test
    public void reentrantLayerMustNotClearOuterGuard() {
        assertTrue(SanitizerSwitch.enter());
        assertFalse(SanitizerSwitch.enter());
        // 模拟内层 convert 结束：它不得 exit()，否则外层余下的格式化又变成"可再读配置"状态
        assertFalse("外层标记必须仍在", SanitizerSwitch.enter());
        SanitizerSwitch.exit();
        assertTrue(SanitizerSwitch.enter());
        SanitizerSwitch.exit();
    }

    @Test
    public void guardIsPerThread() throws Exception {
        assertTrue(SanitizerSwitch.enter());
        try {
            AtomicBoolean otherEntered = new AtomicBoolean(false);
            Thread t = new Thread(() -> otherEntered.set(SanitizerSwitch.enter()));
            t.start();
            t.join(2_000);
            assertFalse("线程必须在超时前结束", t.isAlive());
            assertTrue("其它线程不得被本线程的格式化守卫阻塞", otherEntered.get());
        } finally {
            SanitizerSwitch.exit();
        }
    }

    @Test
    public void safeDefaultAlwaysKeepsSanitizationOn() {
        assertTrue("重入/不可判定时跳过脱敏会让明文凭据落盘", SanitizerSwitch.SAFE_DEFAULT);
    }
}

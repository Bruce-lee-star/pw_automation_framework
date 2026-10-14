package com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SsoConcurrencySelfCheckTest {

    @After
    public void reset() {
        System.clearProperty("sso.concurrency.selfcheck.enabled");
        System.clearProperty("sso.concurrency.selfcheck.failfast");
        System.clearProperty("sso.concurrency.selfcheck.maxPerKey");
    }

    @Test
    public void disabledByDefaultNoOp() {
        // 默认关闭：onAcquire/onRelease 不应有任何副作用或异常
        SsoConcurrencySelfCheck.onAcquire("env_user");
        SsoConcurrencySelfCheck.onRelease("env_user");
    }

    @Test
    public void enabledTracksPerKeyAndReleasesWithoutThrowing() {
        System.setProperty("sso.concurrency.selfcheck.enabled", "true");
        System.setProperty("sso.concurrency.selfcheck.maxPerKey", "1");
        // 第二次 acquire 同一 key 超过上限，但 failfast=false -> 仅记 ERROR，不抛
        SsoConcurrencySelfCheck.onAcquire("k1");
        SsoConcurrencySelfCheck.onAcquire("k1");
        SsoConcurrencySelfCheck.onRelease("k1");
        SsoConcurrencySelfCheck.onRelease("k1");
        // 再次 acquire 应不再超限（计数已归零）
        SsoConcurrencySelfCheck.onAcquire("k1");
        SsoConcurrencySelfCheck.onRelease("k1");
    }

    @Test
    public void failfastThrowsWhenExceeded() {
        System.setProperty("sso.concurrency.selfcheck.enabled", "true");
        System.setProperty("sso.concurrency.selfcheck.failfast", "true");
        System.setProperty("sso.concurrency.selfcheck.maxPerKey", "1");
        SsoConcurrencySelfCheck.onAcquire("x");
        try {
            SsoConcurrencySelfCheck.onAcquire("x");
            fail("expected IllegalStateException on exceeded concurrency");
        } catch (IllegalStateException expected) {
            assertTrue("unexpected message: " + expected.getMessage(),
                    expected.getMessage().contains("active holders 2 > maxPerKey 1"));
        } finally {
            SsoConcurrencySelfCheck.onRelease("x");
            SsoConcurrencySelfCheck.onRelease("x");
        }
    }
}

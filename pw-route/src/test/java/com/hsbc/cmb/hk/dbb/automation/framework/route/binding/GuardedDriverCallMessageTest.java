package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCall.OnTimeout;
import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Timeout message contract for {@link GuardedDriverCallImpl}: FAIL_FAST must state that no
 * driver reply was received (driver busy / shared client connection under concurrent load)
 * and point to {@code DEBUG=pw:channel} for protocol-level inspection, without the
 * misleading legacy phrase "driver unresponsive".
 */
public class GuardedDriverCallMessageTest {

    @Test
    public void failFastTimeoutMessageIsAccurate() throws Exception {
        GuardedDriverCallImpl call = new GuardedDriverCallImpl();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> call.guarded("bind:x", 50, OnTimeout.FAIL_FAST, () -> {
                    Thread.sleep(1000);
                    return null;
                }));

        String msg = ex.getMessage();
        assertTrue("Must state no driver reply + driver busy / concurrent connection use: " + msg,
                msg.contains("no driver reply") && msg.contains("driver busy"));
        assertTrue("Must point to DEBUG=pw:channel for protocol inspection: " + msg,
                msg.contains("DEBUG=pw:channel"));
        assertTrue("Misleading legacy phrase must be gone: " + msg, !msg.contains("driver unresponsive"));
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link CurrentScenario} 只读门面契约：有场景名则返回，缺失/空白一律 {@code unknown} 且不抛异常。
 *
 * <p>该值被元素层的定位健康度采集用于给证据标注「哪条用例出的问题」，故非 Serenity 运行
 * （或尚未进入场景）时必须安全降级，绝不能影响主流程。</p>
 */
public class CurrentScenarioTest {

    @After
    public void tearDown() {
        TestContextHolder.resetForCurrentThread();
    }

    @Test
    public void returnsBoundScenarioName() {
        TestContextHolder.get().set(ListenerGuard.CURRENT_TEST_NAME_KEY, "Login as user A");

        assertEquals("Login as user A", CurrentScenario.nameOrUnknown());
    }

    @Test
    public void returnsUnknownWhenMissingOrBlank() {
        assertEquals("unknown", CurrentScenario.nameOrUnknown());

        TestContextHolder.get().set(ListenerGuard.CURRENT_TEST_NAME_KEY, "   ");
        assertEquals("unknown", CurrentScenario.nameOrUnknown());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * 当前场景名的只读门面（跨包可用）。
 *
 * <p>场景名原本只存放在 {@link ListenerGuard#CURRENT_TEST_NAME_KEY}（包级私有），
 * 其它包（如元素层采集定位健康度）需要给证据标注「哪条用例出的问题」。开放整个 key 会让
 * 任意代码都能写场景名，故只暴露<b>读</b>一个方法。</p>
 *
 * <p>非 Serenity / 未进入场景时返回 {@code "unknown"}，不抛异常（诊断代码不得影响主流程）。</p>
 */
public final class CurrentScenario {

    private static final String UNKNOWN = "unknown";

    private CurrentScenario() {
    }

    /** 当前场景名；缺失或空白时返回 {@code "unknown"}。 */
    public static String nameOrUnknown() {
        try {
            String name = TestContextHolder.get().get(ListenerGuard.CURRENT_TEST_NAME_KEY);
            return name == null || name.isBlank() ? UNKNOWN : name;
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }
}

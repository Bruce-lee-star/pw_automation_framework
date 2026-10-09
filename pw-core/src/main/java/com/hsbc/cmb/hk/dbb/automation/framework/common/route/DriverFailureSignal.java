package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 驱动失败信号（跨模块、单向：UI → route）。
 *
 * <p>由 UI 侧（pw-web-ui 的监听器）在**步骤因 Playwright 异常失败**时 {@link #report()}，
 * 供 route 收尾判断"本场景的驱动连接已不可信"，从而**跳过 unroute 等待**并只记一条短 WARN。</p>
 *
 * <p><b>为什么需要它</b>：route 看不到 UI 的失败。实测因果链是"UI 点击先因驱动侧
 * {@code Cannot find command to respond} 失败 → 该连接随后不再应答 → 收尾 unroute 超时 10s →
 * 规则未确证"，而 {@code isChannelUsable()} 只有在**超时之后**才为 false，且"context 已关闭 ⇒ 跳过
 * unroute"依赖"先关 context 再收尾 route"的时序 —— 实测 route 收尾常常**先于** context 关闭。
 * 有了本信号，route 收尾不再依赖任何时序：只要 UI 已报过驱动异常，就直接跳过 unroute。</p>
 *
 * <p><b>放在 pw-core</b>：pw-web-ui（上报）与 pw-route（消费）都依赖 pw-core；信号本身不引用任何
 * Playwright 类型，保持纯值语义。</p>
 *
 * <p><b>生命周期</b>：UI 侧只 {@link #report()}；route 侧收尾读取 {@link #reported()} 后调用
 * {@link #reset()} 自行清零（消费即清零，故 UI 侧无需"每场景清零"钩子）。</p>
 */
public final class DriverFailureSignal {

    private static final AtomicBoolean FAILED = new AtomicBoolean();

    private DriverFailureSignal() {
    }

    /** 上报"本场景已发生驱动异常"（UI 侧调用；幂等）。 */
    public static void report() {
        FAILED.set(true);
    }

    /** 清零（route 侧收尾消费后调用；下一个场景重新开始）。 */
    public static void reset() {
        FAILED.set(false);
    }

    /** 本场景是否已上报过驱动异常。 */
    public static boolean reported() {
        return FAILED.get();
    }
}

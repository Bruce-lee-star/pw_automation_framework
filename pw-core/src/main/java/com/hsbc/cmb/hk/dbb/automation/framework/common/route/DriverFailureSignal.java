package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 驱动失败信号（跨模块、单向：UI → route）。
 *
 * <p>由 UI 侧（pw-web-ui 的监听器）在<b>步骤因 Playwright 异常失败</b>时 {@link #report(Object)}，
 * 供 route 收尾判断"本场景的驱动连接已不可信"，从而<b>跳过 unroute 等待</b>并只记一条短 WARN。</p>
 *
 * <p><b>为什么需要它</b>：route 看不到 UI 的失败。实测因果链是"UI 点击先因驱动侧
 * {@code Cannot find command to respond} 失败 → 该连接随后不再应答 → 收尾 unroute 超时 10s →
 * 规则未确证"，而 {@code isChannelUsable()} 只有在<b>超时之后</b>才为 false，且"context 已关闭 ⇒ 跳过
 * unroute"依赖"先关 context 再收尾 route"的时序 —— 实测 route 收尾常常<b>先于</b> context 关闭。
 * 有了本信号，route 收尾不再依赖任何时序：只要本场景已报过驱动异常，就直接跳过 unroute。</p>
 *
 * <p><b>per-context 键控（2026-10-24 收口 · 根治 P0-2）</b>：信号按<b>连接身份</b>（{@code key}，
 * 实际即 {@code BrowserContext}；pw-core 不引用 Playwright 类型，故用 {@link Object} 身份键）隔离，
 * <b>不再</b>是 JVM 级 {@code static} 单值。原全局单值下，任一场景 {@code report()} 会令<b>所有</b>
 * 并行 runtime 的响应轮询/派发判 true → 断链毒化其它并行用例（"跨用例信道毒化"的同族问题）。
 * 改为 per-context 后，只有<b>上报信号那个 context 所属</b>的 runtime 受影响，其余并行用例的
 * MONITOR/CAPTURE 轮询与派发不受牵连。</p>
 *
 * <p><b>放在 pw-core</b>：pw-web-ui（上报）与 pw-route（消费）都依赖 pw-core；信号本身不引用任何
 * Playwright 类型（键以 {@link Object} 身份承载），保持纯值语义。</p>
 *
 * <p><b>生命周期</b>：UI 侧只 {@link #report(Object)}；route 侧收尾读取 {@link #reported(Object)} 后调用
 * {@link #reset(Object)} 自行清零（消费即清零，故 UI 侧无需"每场景清零"钩子）；场景边界另有
 * {@link #resetAll()} 兜底清扫（下一场景重新开始）。</p>
 */
public final class DriverFailureSignal {

    /** 按连接身份隔离的失败信号（key = BrowserContext 身份 / 任意 Object；null 键视为无信号）。 */
    private static final ConcurrentHashMap<Object, AtomicBoolean> FAILED = new ConcurrentHashMap<>();

    private DriverFailureSignal() {
    }

    /** 上报"指定连接的场景已发生驱动异常"（UI 侧调用；幂等）。{@code key == null} 视为无操作。 */
    public static void report(Object key) {
        if (key == null) {
            return;
        }
        FAILED.computeIfAbsent(key, k -> new AtomicBoolean()).set(true);
    }

    /** 清零指定连接的信号（route 侧收尾消费后调用；下一个场景重新开始）。{@code key == null} 视为无操作。 */
    public static void reset(Object key) {
        if (key == null) {
            return;
        }
        AtomicBoolean removed = FAILED.remove(key);
        if (removed != null) {
            removed.set(false);
        }
    }

    /** 指定连接的场景是否已上报过驱动异常。{@code key == null} 返回 {@code false}。 */
    public static boolean reported(Object key) {
        if (key == null) {
            return false;
        }
        AtomicBoolean v = FAILED.get(key);
        return v != null && v.get();
    }

    /** 场景边界兜底清扫：清空全部信号（下一场景重新开始）。 */
    public static void resetAll() {
        FAILED.clear();
    }
}

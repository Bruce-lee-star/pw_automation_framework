package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拾取去重闸门：同元素「并发去重」+「失败冷却」。
 *
 * <p>为什么需要（真实运行日志证据）：
 * <ul>
 *   <li>一次拾取要走 100~200ms 的 CDP 往返，这期间同一元素的事件会<b>再次</b>通过「是否已拾取」检查
 *       （{@code matchFieldKey} 只能看到<b>已完成</b>的拾取）⇒ 同一元素被排两次，Elements 里出现
 *       {@code #2/#3}、{@code #4/#5} 这类完全重复的条目；</li>
 *   <li>拾取失败的元素不会进入已拾取列表，于是<b>每个</b>输入事件都重跑一遍 CDP：实测同一密码框在
 *       260ms 内被重试 13 次，既刷屏又拖慢拾取。</li>
 * </ul>
 *
 * <p>因此 {@link #begin} 用元素指纹抢占、{@link #finish} 释放并记录失败冷却。冷却只压高频的
 * {@code fill}；{@code click} 是用户的明确意图，始终允许重试。
 *
 * <p>线程安全：{@code begin}/{@code finish} 分别在 Playwright 派发线程与拾取执行器线程调用。
 */
final class PickGuard {

    /** 失败冷却时长：足够覆盖「连续输入」的事件爆发，又不至于让用户以为点了没反应。 */
    static final long DEFAULT_FAILURE_COOLDOWN_MS = 1_500L;

    private final long failureCooldownMs;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    PickGuard() {
        this(DEFAULT_FAILURE_COOLDOWN_MS);
    }

    /** 可注入冷却时长的构造（单测用）。 */
    PickGuard(long failureCooldownMs) {
        this.failureCooldownMs = Math.max(0L, failureCooldownMs);
    }

    /**
     * 抢占一次拾取。
     *
     * @param fingerprint 元素指纹（{@link #fingerprint(Map)}）；{@code null} 表示无法识别，直接放行
     * @param type        动作类型（{@code click} / {@code fill}）
     * @return {@code true} 可真正执行；{@code false} 表示同元素正在拾取中或仍在失败冷却期
     */
    boolean begin(String fingerprint, String type) {
        if (fingerprint == null) return true;
        if (!"click".equals(type) && coolingDown(fingerprint)) return false;
        return inFlight.add(fingerprint);
    }

    /** 结束一次拾取：释放占位；失败则进入冷却，成功则清掉历史失败。 */
    void finish(String fingerprint, boolean succeeded) {
        if (fingerprint == null) return;
        inFlight.remove(fingerprint);
        if (succeeded) failedAt.remove(fingerprint);
        else failedAt.put(fingerprint, System.currentTimeMillis());
    }

    /** 该元素是否仍在失败冷却期（过期即顺势清理）。 */
    boolean coolingDown(String fingerprint) {
        Long failedAtMs = failedAt.get(fingerprint);
        if (failedAtMs == null) return false;
        if (System.currentTimeMillis() - failedAtMs < failureCooldownMs) return true;
        failedAt.remove(fingerprint);
        return false;
    }

    /**
     * 元素指纹：优先页面侧 {@code cssSelector}，退化到 {@code role|name|tag}。
     *
     * <p>刻意<b>不含坐标</b>——坐标随滚动/重排变化，会把同一元素当成不同元素。
     */
    static String fingerprint(Map<String, Object> info) {
        if (info == null) return null;
        String css = value(info, "cssSelector");
        if (!css.isEmpty()) return css;
        String role = value(info, "role");
        return (role.isEmpty() ? "generic" : role) + "|" + value(info, "name") + "|" + value(info, "tag");
    }

    private static String value(Map<String, Object> info, String key) {
        Object v = info.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}

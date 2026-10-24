package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * per-context → outcomeId 绑定（线程安全），供 <b>CloseGuard 收尾线程</b>在关闭 context 时，
 * 凭 context <b>精确</b>解析该 context 所属用例的 per-case 落点（trace / video 归属），
 * 从根本上消除并行下"取最近开始的用例"的最佳努力歧义。
 *
 * <p>范式复用 {@code DownloadRegistry} 的 {@code ConcurrentHashMap<BrowserContext, ...>} +
 * close 时 {@link #unbind(BrowserContext)}，防止 key 随 context 永久驻留导致泄漏
 * （{@link BrowserContext} 未覆写 equals/hashCode → 按身份匹配）。</p>
 *
 * @apiNote framework-internal：由 {@code PlaywrightContextManager} 绑定 / 解绑、
 *          {@code ScenarioTraceRecorder} 读取；业务代码不得依赖。
 */
public final class ContextOutcomeBinding {

    private static final ConcurrentMap<BrowserContext, String> BINDINGS = new ConcurrentHashMap<>();

    private ContextOutcomeBinding() {
    }

    /**
     * 绑定 context 到其所属用例 id（id 为 null / 空时忽略，回落既有最佳努力逻辑）。
     *
     * @param context   浏览器上下文（非 null）
     * @param outcomeId 报告侧用例 id（{@link com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridge#currentOutcomeId()}）
     */
    public static void bind(BrowserContext context, String outcomeId) {
        if (context != null && outcomeId != null && !outcomeId.isEmpty()) {
            BINDINGS.put(context, outcomeId);
        }
    }

    /**
     * 取 context 绑定的用例 id；无绑定（如未接报告 / 创建时句柄缺失）返回 {@code null}。
     *
     * @param context 浏览器上下文（可为 null）
     * @return 绑定的用例 id，或 {@code null}
     */
    public static String outcomeId(BrowserContext context) {
        return context == null ? null : BINDINGS.get(context);
    }

    /**
     * 解除绑定（context 关闭时调用，防泄漏）。context 已不在 map 中时 no-op。
     *
     * @param context 浏览器上下文（可为 null）
     */
    public static void unbind(BrowserContext context) {
        if (context != null) {
            BINDINGS.remove(context);
        }
    }
}

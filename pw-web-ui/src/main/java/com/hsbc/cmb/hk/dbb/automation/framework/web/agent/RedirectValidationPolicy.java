package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * 每跳重定向校验策略（R3 运行期落点，fail-closed）。
 *
 * <p>与 {@link RouteTestBedHarness#rejectNonSandboxTarget(String)} 共用同一套"沙箱主机"语义
 * （file://、loopback、{@code pw.agent.testbed.allowedHosts} 显式列出的主机），但把判定从
 * "仅首跳目标"扩展到"<b>链上每一跳</b>"以及所有类别的请求 —— 包括服务端 302/307 重定向目标、
 * 子资源（CSS/JS/图片）、iframe、弹窗与下载。任何一跳命中非沙箱主机即整体拒绝，
 * 防止"首跳指向沙箱、重定向逃逸到生产"的绕过（R3 的核心威胁）。</p>
 *
 * <p><b>铁律：fail-closed</b>。allow-list 为空 → 除 file/loopback 外一律拒绝；
 * URL 无法解析 → 拒绝；列表项空白 → 忽略。本类为纯函数、无状态、线程安全，
 * 可由靶场路由拦截器逐请求同步调用，也可由诊断层对重定向链做离链断言。</p>
 */
public final class RedirectValidationPolicy {

    /** 与靶场护栏共用同一配置键（设计 §3.1.2.1 / R4 / R8）。 */
    static final String ALLOWED_HOSTS_KEY = "pw.agent.testbed.allowedHosts";

    private RedirectValidationPolicy() {
    }

    /**
     * 靶场重定向守卫模式。
     *
     * <ul>
     *   <li>{@link #WARN}（默认）：不硬阻断任何跨主机请求 —— 登录 SSO、IdP 回调等合法流程本就会跨主机跳转，
     *       由企业网络层兜底出站；框架仅做入口校验（{@link RouteTestBedHarness#rejectNonSandboxTarget}）
     *       并提供本策略供诊断/审计。此模式不会因"请求落到非沙箱主机"而中断测试。</li>
     *   <li>{@link #STRICT}：注册 {@code page.route} 拦截，命中非沙箱主机的请求一律 {@code abort}，
     *       用于已显式把 auth/IdP 主机列入 {@value ALLOWED_HOSTS_KEY} 的团队，在 CI 中捕获意外逃逸。</li>
     * </ul>
     */
    public enum RedirectGuardMode {
        /** 默认：放行，企业出站由网络层兜底。 */
        WARN,
        /** 可选：硬阻断非沙箱主机（需自行把合法跨主机目标列入 allow-list）。 */
        STRICT
    }

    /** 靶场重定向守卫模式配置键，默认 {@link RedirectGuardMode#WARN}。 */
    static final String REDIRECT_GUARD_KEY = "pw.agent.testbed.redirectGuard";

    /**
     * 解析当前守卫模式。非法/空白值回落 {@link RedirectGuardMode#WARN}（fail-open，避免误伤合法登录流）。
     */
    public static RedirectGuardMode guardMode() {
        String v = ConfigReader.getProperty(REDIRECT_GUARD_KEY, "warn").trim().toLowerCase(Locale.ROOT);
        return "strict".equals(v) ? RedirectGuardMode.STRICT : RedirectGuardMode.WARN;
    }

    /**
     * 判定单个请求 URL 是否落入沙箱。
     *
     * @param url 请求 URL（可为 null / 空白）
     * @return 允许返回 {@code true}；拒绝（含解析失败 / 非沙箱 / 空白）返回 {@code false}
     */
    public static boolean isSandboxUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String scheme;
        String host;
        try {
            URI uri = new URI(url);
            scheme = uri.getScheme();
            host = stripBrackets(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
        if ("file".equalsIgnoreCase(scheme) || isLoopback(host)) {
            return true;
        }
        String allowed = ConfigReader.getProperty(ALLOWED_HOSTS_KEY, "");
        if (allowed.isBlank()) {
            return false;
        }
        for (String h : allowed.split(",")) {
            if (host != null && host.equalsIgnoreCase(h.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 逐跳校验整条重定向链（index 0 = 首跳，末位 = 链尾）。
     *
     * @param redirectChain 有序的重定向链 URL 列表（首跳在前）；可为 null
     * @return 拒绝时返回首个逃逸跳的索引（≥0）；全链均落沙箱返回 {@code -1}；
     *         {@code redirectChain} 为 null 视为逃逸，返回 {@code 0}（fail-closed）
     */
    public static int firstEscapeHop(List<String> redirectChain) {
        if (redirectChain == null) {
            return 0;
        }
        for (int i = 0; i < redirectChain.size(); i++) {
            if (!isSandboxUrl(redirectChain.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return "localhost".equals(h) || "127.0.0.1".equals(h)
                || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h);
    }

    /** IPv6 字面量在 {@link URI#getHost()} 中带方括号，去掉以便与 allow-list / loopback 判定一致。 */
    private static String stripBrackets(String host) {
        if (host == null) {
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}

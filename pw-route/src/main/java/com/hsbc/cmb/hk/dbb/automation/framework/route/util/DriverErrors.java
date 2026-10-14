package com.hsbc.cmb.hk.dbb.automation.framework.route.util;

/**
 * 驱动层错误归一化工具（2026-10-14，P1-7 / P0-3 配套）。
 *
 * <p><b>问题</b>：Playwright 异常（{@code PlaywrightException}）的 {@code toString()} 常把整段
 * JS 栈（{@code coreBundle.js:12345 ...}）一并带出，直接 {@code LOGGER.warn("...", t.toString())}
 * 会把数 KB 的 JS 栈打进业务日志，淹没真实信号。</p>
 *
 * <p><b>职责</b>：
 * <ul>
 *   <li>{@link #shortMessage(Throwable)}：取异常首行 + 根因类名，截断到 {@value #MAX_LEN} 字符，
 *       供 catch 块日志使用（DEBUG 级才打完整栈）；</li>
 *   <li>{@link #isHandleReclaimed(Throwable)}：判定"响应/请求句柄已被驱动回收"类错误
 *       （{@code No resource with given identifier} / {@code Object doesn't exist}），
 *       供 P0-3 把"框架/驱动竞态"与"真协议错误"分类计数。</li>
 * </ul>
 *
 * <p>@apiNote framework-internal：仅 route 内部诊断使用。</p>
 */
public final class DriverErrors {

    /** 短消息上限（字符）；足以容纳首行异常描述 + 根因类名，又不至于刷屏。 */
    private static final int MAX_LEN = 240;

    private DriverErrors() {
    }

    /**
     * 异常短消息：首行 + 根因类名，截断到 {@value #MAX_LEN}。
     *
     * <p>首行即 {@code Throwable.toString()} 的第一行（通常是 {@code PlaywrightException: <原因>}），
     * 已含最有价值信息；追加根因类名便于区分"句柄回收"与"真网络错误"等类别。</p>
     *
     * @param t 异常（{@code null} 安全，返回 {@code "null"}）
     */
    public static String shortMessage(Throwable t) {
        if (t == null) {
            return "null";
        }
        String firstLine = firstLine(t.toString());
        Throwable cause = t.getCause();
        String causeSuffix = cause != null ? " (" + cause.getClass().getSimpleName() + ")" : "";
        String combined = firstLine + causeSuffix;
        return combined.length() > MAX_LEN ? combined.substring(0, MAX_LEN) : combined;
    }

    /**
     * 是否"驱动句柄已被回收"类错误（框架/驱动竞态，区别于真协议错误）。
     *
     * <p>Playwright 在对象表回收后，对失效句柄的任何调用会抛含以下片段的异常：
     * {@code No resource with given identifier}（通用）或 {@code Object doesn't exist: <type>@...}
     * （{@code Connection.getExistingObject} 查表失败）。这两类都属"竞态"而非应用缺陷。</p>
     *
     * @param t 异常（{@code null} 视为非回收类）
     */
    public static boolean isHandleReclaimed(Throwable t) {
        if (t == null) {
            return false;
        }
        String m = t.getMessage() != null ? t.getMessage() : t.toString();
        return m.contains("No resource with given identifier")
                || m.contains("Object doesn't exist");
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }
}

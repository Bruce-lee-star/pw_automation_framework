package com.hsbc.cmb.hk.dbb.automation.framework.report.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 控制台 / API 行归类——把 {@link ConsoleCapture} 捕获的原始 stdout/stderr 按“来源语义”分组，
 * 供 per-case 页分区展示，替代原先笼统的 “Standard output / Standard error”。
 *
 * <p>归类口径（纯渲染期启发式，不改 JSON 真相源，保持 schema additive-only）：
 * <ul>
 *   <li>{@code route}  —— Route 注册日志：{@code [Route ...]} 前缀，或含 {@code route for '}；</li>
 *   <li>{@code net}    —— 网络调用：{@code NET ...}（bridge 约定前缀），或 {@code GET/POST ... http(s)://}；</li>
 *   <li>{@code log}    —— 应用日志：logback 常见形态（{@code INFO/WARN/ERROR/DEBUG/TRACE} 前缀，或 {@code HH:mm:ss.SSS} 时间戳）；</li>
 *   <li>{@code out}    —— 其余普通 stdout；</li>
 *   <li>{@code err}    —— stderr（route/net 仍按语义归类，其余归错误）。</li>
 * </ul>
 *
 * <p>健壮性：null/空行跳过；任何分类异常退化为 {@code out}/{@code err}，绝不阻断渲染。
 */
public final class ConsoleClassifier {

    public static final String ROUTE = "route";
    public static final String NETWORK = "net";
    public static final String LOG = "log";
    public static final String CONSOLE = "out";
    public static final String ERROR = "err";

    private static final String ROUTE_LABEL = "API / Route registration";
    private static final String NETWORK_LABEL = "Network calls";
    private static final String LOG_LABEL = "Application log";
    private static final String CONSOLE_LABEL = "Console output";
    private static final String ERROR_LABEL = "Console errors";

    private static final Pattern HTTP_LINE =
            Pattern.compile("(?i)^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+https?://.*");
    private static final Pattern LOG_LEVEL =
            Pattern.compile("^(INFO|WARN|ERROR|DEBUG|TRACE)\\b.*");
    private static final Pattern LOG_TIME =
            Pattern.compile("^\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}.*");

    private ConsoleClassifier() {
    }

    /**
     * 分类一批 stdout/stderr 行，返回<b>非空</b>分组（顺序固定：route → net → log → out → err）。
     * 每组为 {@code {key, tone, label, lines}}，tone 与 key 同值（供 CSS 类名 {@code con-<tone>}）。
     */
    public static List<Map<String, Object>> classify(List<String> stdout, List<String> stderr) {
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        add(stdout, false, buckets);
        add(stderr, true, buckets);

        List<Map<String, Object>> groups = new ArrayList<>();
        emit(groups, buckets, ROUTE, ROUTE_LABEL);
        emit(groups, buckets, NETWORK, NETWORK_LABEL);
        emit(groups, buckets, LOG, LOG_LABEL);
        emit(groups, buckets, CONSOLE, CONSOLE_LABEL);
        emit(groups, buckets, ERROR, ERROR_LABEL);
        return groups;
    }

    /** 单行归类（包级可见，便于单测）。 */
    static String category(String line, boolean isError) {
        String t = line == null ? "" : line.strip();
        String c;
        if (t.startsWith("[Route") || t.contains("route for '")) {
            c = ROUTE;
        } else if (t.startsWith("NET ") || HTTP_LINE.matcher(t).matches()) {
            c = NETWORK;
        } else if (LOG_TIME.matcher(t).matches() || LOG_LEVEL.matcher(t).matches()) {
            c = LOG;
        } else {
            c = CONSOLE;
        }
        // stderr 的普通输出/应用日志归“错误”；route/net 保持语义分类，便于排查。
        return (isError && (CONSOLE.equals(c) || LOG.equals(c))) ? ERROR : c;
    }

    private static void add(List<String> lines, boolean isError, Map<String, List<String>> buckets) {
        if (lines == null) {
            return;
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            buckets.computeIfAbsent(category(line, isError), k -> new ArrayList<>()).add(line);
        }
    }

    private static void emit(List<Map<String, Object>> out, Map<String, List<String>> buckets,
                             String key, String label) {
        List<String> lines = buckets.get(key);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("key", key);
        group.put("tone", key);
        group.put("label", label);
        group.put("lines", lines);
        out.add(group);
    }
}

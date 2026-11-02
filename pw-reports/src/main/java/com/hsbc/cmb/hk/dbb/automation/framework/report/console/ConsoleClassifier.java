package com.hsbc.cmb.hk.dbb.automation.framework.report.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 控制台 / API 行归类——把 {@link ConsoleCapture} 捕获的原始 stdout/stderr 按“来源语义”分组，
 * 供 per-case 页分区展示，替代原先笼统的 “Standard output / Standard error”。
 *
 * <p>归类口径（纯渲染期启发式，不改 JSON 真相源，保持 schema additive-only）：
 * <ul>
 *   <li>{@code route}  —— Route <b>注册</b>日志：{@code [Route ...]} 前缀，或含 {@code route for '}
 *       （页面标题 “Route registration”，与 “Route Captured” 是两回事：前者声明规则，后者是实际抓到的调用）；</li>
 *   <li>{@code net}    —— <b>实际抓到的</b>网络调用（页面标题 “Route Captured”）：{@code NET ...}
 *       （bridge 约定前缀），或 {@code GET/POST ... http(s)://}；</li>
 *   <li>{@code out}    —— 其余普通 stdout（<b>含应用日志</b>：logback 形态同样归入本组，见下）；</li>
 *   <li>{@code err}    —— stderr（route/net 仍按语义归类，其余归错误）。</li>
 * </ul>
 *
 * <p><b>2026-10-19 用户反馈：不要 “Application log” 区</b>。原 {@code log} 组（logback
 * {@code INFO/WARN/...} 或 {@code HH:mm:ss.SSS} 时间戳形态）已<b>取消</b>，这类行并入
 * {@code out}（Console）——底部统一以 “Console log” 呈现整个运行日志，不再按来源切出独立卡片。
 *
 * <p>健壮性：null/空行跳过；任何分类异常退化为 {@code out}/{@code err}，绝不阻断渲染。
 */
public final class ConsoleClassifier {

    public static final String ROUTE = "route";
    public static final String NETWORK = "net";
    public static final String CONSOLE = "out";
    public static final String ERROR = "err";

    private static final String ROUTE_LABEL = "Route registration";
    private static final String NETWORK_LABEL = "Route Captured";
    private static final String CONSOLE_LABEL = "Console output";
    private static final String ERROR_LABEL = "Console errors";

    private static final Pattern HTTP_LINE =
            Pattern.compile("(?i)^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+https?://.*");

    private ConsoleClassifier() {
    }

    /**
     * 分类一批 stdout/stderr 行，返回<b>非空</b>分组（顺序固定：route → net → log → out → err）。
     * 每组为 {@code {key, tone, label, lines}}，tone 与 key 同值（供 CSS 类名 {@code con-<tone>}）。
     */
    public static List<Map<String, Object>> classify(List<String> stdout, List<String> stderr) {
        return classifyFiltered(stdout, stderr, null);
    }

    /**
     * 步骤级「已捕获」分组：只保留 {@code net}——即<b>实际抓到的 API 调用</b>。
     *
     * <p>用途：满足「在哪个步骤抓到，就展示在哪个步骤」。{@code route}（<b>注册</b>日志）刻意
     * <b>不</b>进这里：它已由步骤内的 “Route Register” 面板展示，再放一遍就是同一份信息重复
     * （2026-10-20 用户反馈：Route Captured 不该是注册日志）。
     */
    public static List<Map<String, Object>> classifyCaptured(List<String> stdout, List<String> stderr) {
        return classifyFiltered(stdout, stderr, Set.of(NETWORK));
    }

    /**
     * 底部「Console log」分组：只保留 {@code out}/{@code err}。
     *
     * <p>net（实际捕获）已在各自步骤的 “Route Captured” 展示，此处不再重复。
     */
    public static List<Map<String, Object>> classifyResidual(List<String> stdout, List<String> stderr) {
        return classifyFiltered(stdout, stderr, Set.of(CONSOLE, ERROR));
    }

    private static List<Map<String, Object>> classifyFiltered(List<String> stdout, List<String> stderr,
                                                              Set<String> keep) {
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        add(stdout, false, buckets);
        add(stderr, true, buckets);

        List<Map<String, Object>> groups = new ArrayList<>();
        emit(groups, buckets, ROUTE, ROUTE_LABEL, keep);
        emit(groups, buckets, NETWORK, NETWORK_LABEL, keep);
        emit(groups, buckets, CONSOLE, CONSOLE_LABEL, keep);
        emit(groups, buckets, ERROR, ERROR_LABEL, keep);
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
        } else {
            //  应用日志（logback 形态）不再单列 “Application log”，并入 Console
            c = CONSOLE;
        }
        // stderr 的普通输出/应用日志归“错误”；route/net 保持语义分类，便于排查。
        return (isError && CONSOLE.equals(c)) ? ERROR : c;
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
                             String key, String label, Set<String> keep) {
        if (keep != null && !keep.contains(key)) {
            return;
        }
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

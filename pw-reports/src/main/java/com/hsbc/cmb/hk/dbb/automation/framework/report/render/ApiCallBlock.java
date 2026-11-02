package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 API 调用的<b>结构化展示模型</b>——把上报的「管道分隔多行块」解析成字段，
 * 供 per-case 页渲染成卡片（方法徽标 + URL + 状态 + 耗时 + Request/Response 分区）。
 *
 * <p><b>为什么在 Java 侧解析而不是模板里 split</b>：FreeMarker 的 {@code ?split} 分隔符语义
 * （字面量 / 正则）在不同版本间有歧义，且切片语法兼容性差；解析属逻辑，应归 Java 并可单测，
 * 模板只负责取值展示（2026-10-20 实测：FTL 内 split 管道符直接抛 lexical error）。
 *
 * <p><b>行格式</b>（由 {@code AbstractRestJob} 生成，见 {@code ApiOperationKind.REST}）：
 * <pre>
 * REST|GET|https://host/getotp.htm?type=1|200|5771 ms
 * REQH|Request headers|Accept=application/json
 * REQB|Request body|&lt;none&gt;
 * RESH|Response headers|Content-Type=text/html
 * RESB|Response body|{"token":"..."}
 * </pre>
 *
 * <p><b>向后兼容</b>：历史/外部来源的单行块（如 Serenity 时代的
 * {@code POST /api/session -> 401}）没有管道结构，此时整体作为 URL、无分区行——
 * 旧报告不会因格式升级而丢内容或渲染失败。
 */
public final class ApiCallBlock {

    private final String kind;
    private final String method;
    private final String url;
    private final String status;
    private final String elapsed;
    private final List<Map<String, String>> rows;

    private ApiCallBlock(String kind, String method, String url, String status, String elapsed,
                         List<Map<String, String>> rows) {
        this.kind = kind;
        this.method = method;
        this.url = url;
        this.status = status;
        this.elapsed = elapsed;
        this.rows = rows;
    }

    /** 分区行行首 KEY（{@code REQH|Request headers|...} 的第一段）。 */
    private static final java.util.Set<String> ROW_KEYS =
            java.util.Set.of("REQH", "REQB", "RESH", "RESB", "ERR");

    /**
     * JSON 美化器（{@code INDENT_OUTPUT} + 标准分隔符）；解析失败时原样返回，绝不影响其它展示。
     *
     * <p><b>为什么要自定义 pretty printer</b>：Jackson 默认的 {@code DefaultPrettyPrinter} 输出
     * {@code "key" : value}（冒号<b>前后都有空格</b>），既不符合 JSON 惯用写法、也不像"正经 JSON"，
     * 复制出去还得手工清理。这里改写分隔符为标准的 {@code ": "}（2026-10-21 用户反馈：
     * request/response body 要按 JSON 格式展示）。</p>
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER;

    static {
        JSON_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    }

    /** 标准 JSON 分隔符的漂亮打印器（仅改「字段名 : 值」的分隔符，其余沿用默认缩进）。 */
    private static final class JsonPrettyPrinter
            extends com.fasterxml.jackson.core.util.DefaultPrettyPrinter {

        private JsonPrettyPrinter() {
        }

        private JsonPrettyPrinter(JsonPrettyPrinter base) {
            super(base);
        }

        @Override
        public com.fasterxml.jackson.core.util.DefaultPrettyPrinter createInstance() {
            return new JsonPrettyPrinter(this);
        }

        @Override
        public void writeObjectFieldValueSeparator(com.fasterxml.jackson.core.JsonGenerator g)
                throws java.io.IOException {
            g.writeRaw(": ");
        }
    }

    /** 脱敏/单元格消毒用的 {@code %7C} 还原为管道符（{@code AbstractRestJob#cell} 编码方向的反向）。 */
    private static final String PIPE_ESCAPE = "%7C";

    /**
     * 解析一个多行块；格式不符时降级为「整块作 URL」的单卡片（绝不抛异常、绝不丢内容）。
     *
     * <p><b>为什么按"行首 KEY"识别而不是简单按换行切分</b>：JSON 美化后的请求/响应体含换行
     * （2 空格缩进排版），按换行切会把一个 JSON 体拆成多行。这里只有「以已知 KEY 开头」的
     * 行才开启新分区，其余内容续接到上一行的 value——value 因此可安全含换行。
     *
     * @param block 上报的原始块（可能含换行）
     */
    public static ApiCallBlock parse(String block) {
        if (block == null || block.isBlank()) {
            return new ApiCallBlock("", "", "", "", "", List.of());
        }
        String[] lines = block.split("\n", -1);
        String[] head = lines[0].split("\\|", -1);
        if (head.length < 5) {
            //  非管道格式（历史单行）：整体作为 URL，方法/状态留空由模板隐藏或中性展示
            return new ApiCallBlock("", "", block.trim(), "", "", List.of());
        }
        List<Map<String, String>> rows = new ArrayList<>();
        StringBuilder pendingValue = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (isRowStart(line)) {
                if (pendingValue != null) {
                    appendRow(rows, pendingValue.toString());
                }
                pendingValue = new StringBuilder(line);
            } else if (pendingValue != null) {
                //  上一行 value 的续行（JSON 美化体）——原样拼回，保留缩进
                pendingValue.append('\n').append(line);
            }
        }
        if (pendingValue != null) {
            appendRow(rows, pendingValue.toString());
        }
        return new ApiCallBlock("", head[1], head[2], head[3], head[4], List.copyOf(rows));
    }

    private static boolean isRowStart(String line) {
        int bar = line.indexOf('|');
        return bar > 0 && ROW_KEYS.contains(line.substring(0, bar));
    }

    private static void appendRow(List<Map<String, String>> rows, String rowLine) {
        String[] cells = rowLine.split("\\|", 3);
        if (cells.length < 3) {
            return;
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("label", cells[1]);
        //  反向解码单元格消毒（| → %7C），并尽量把 JSON 值美化缩进后再展示
        //  （2026-10-21 用户要求：request payload / response body / JSON 头值能按 JSON 显示就按 JSON 显示）。
        //  是 JSON 时额外打 {@code json} 标记：模板据此改用 <pre> 代码块渲染（保缩进/独立滚动）。
        String raw = decode(cells[2]);
        String pretty = prettyIfJson(raw);
        row.put("value", pretty == null ? raw : pretty);
        if (pretty != null) {
            row.put("json", "true");
        }
        rows.add(row);
    }

    /**
     * 按花括号层级重排缩进（每层 2 空格），让 JSON 的观感与标准写法一致。
     *
     * <p>只依据「行的首/末非空白字符」判定层级：JSON 字符串里的换行一定被转义为 {@code \n}，
     * 故行首只可能是键/值/嵌套括号，判定安全。空行丢弃，避免多余空白。</p>
     */
    private static String reindent(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        List<String> out = new ArrayList<>();
        int depth = 0;
        for (String rawLine : json.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            char first = line.charAt(0);
            if (first == '}' || first == ']') {
                depth = Math.max(0, depth - 1);
            }
            out.add("  ".repeat(depth) + line);
            char last = line.charAt(line.length() - 1);
            if (last == '{' || last == '[') {
                depth++;
            }
        }
        return String.join("\n", out);
    }

    /** 把 {@code cell()} 编码的 {@code %7C} 还原为管道符。 */
    private static String decode(String v) {
        return v == null ? "" : v.replace(PIPE_ESCAPE, "|");
    }

    /**
     * 若值是 JSON（对象/数组），按标准 JSON 编排（2 空格缩进、{@code ": "} 分隔）后返回；
     * <b>不是 JSON 则返回 {@code null}</b>（调用方据此决定是否走代码块渲染）。
     *
     * <p>解析失败一律按"非 JSON"处理——绝不让美化逻辑影响普通文本
     * （{@code k=v; k=v} 头、{@code <none>} 等）。</p>
     */
    private static String prettyIfJson(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        try {
            Object parsed = JSON_MAPPER.readValue(trimmed, Object.class);
            //  必须每次新建 printer：{@code DefaultPrettyPrinter} 是有状态对象（内部 _nesting）。
            //  再用 {@link #reindent} 统一缩进为 2 空格层级——不依赖 Jackson 的 nesting 记账，
            //  输出与标准 JSON 完全一致（首层字段 2 空格、收尾括号与开括号对齐）。
            return reindent(JSON_MAPPER.writer(new JsonPrettyPrinter()).writeValueAsString(parsed));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** HTTP 动词：仅用于从 {@code request : POST url} 里切出方法，避免把 URL 自身当方法。 */
    private static final java.util.Set<String> HTTP_VERBS = java.util.Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE");

    /**
     * 解析「Route Captured」条目为与 API Request <b>同构</b>的卡片模型
     * （2026-10-22 用户要求：Route Captured 与 API Request 显示一致，且每条抓包之间明显区分）。
     *
     * <p>支持两种上报形态：
     * <ul>
     *   <li><b>结构化块</b>（{@code RouteEvidenceSink} 写入）：{@code [Route MONITOR] https://host/x?*** — {}
     *       + {@code "  pattern   : auth/assert"} 等分区行（substring 见类文档）；</li>
     *   <li><b>控制台镜像单行</b>：{@code NET POST https://host/x -> 200 (86 ms)}。</li>
     * </ul>
     *
     * <p>解析不出分区时降级为「整块作 Detail 分区」的单卡片——绝不抛异常、绝不丢内容
     * （与 {@link #parse(String)} 的兼容策略一致）。
     */
    public static ApiCallBlock parseCaptured(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ApiCallBlock("", "", "", "", "", List.of());
        }
        String text = raw.strip();
        String kind = "";
        if (text.startsWith("[Route")) {
            int close = text.indexOf(']');
            if (close > 6) {
                kind = text.substring(6, close).trim();
                text = text.substring(close + 1).strip();
            }
        }
        int brace = text.indexOf('{');
        if (brace >= 0) {
            //  取首个 '{' 之后的<b>全部</b>内容——包含抓包体自身闭合的 '}' 及其后的 tail。
            //  tail 由 CapturedApiCall/MockHandler 的 detailWith(...) 追加（MONITOR RESULT 的
            //  result / respBody (not read)；CAPTURE 的 rule / applied）。原先截断到末 '}'
            //  会丢失这些信息，导致 monitor 的 "无响应体" 连原因都看不到。
            //  闭合 '}' 在第 0 列（无 2 空格缩进）→ splitSections 不会当成分区起始，安全跳过。
            String inner = text.substring(brace + 1);
            return parseCapturedBlock(kind, stripTrailingSeparators(text.substring(0, brace)), inner);
        }
        return parseCapturedLine(kind, stripTrailingSeparators(text));
    }

    /** 抓包块内的一个分区：{@code label} + 行内值 + 续行（头/体/规则声明等）。 */
    private record Section(String label, String inline, List<String> lines) {
    }

    private static ApiCallBlock parseCapturedBlock(String kind, String headUrl, String body) {
        List<Map<String, String>> rows = new ArrayList<>();
        String method = "";
        String url = headUrl;
        String status = "";
        String elapsed = "";
        List<Section> sections = splitSections(body);
        for (Section section : sections) {
            String key = section.label().toLowerCase(java.util.Locale.ROOT);
            switch (key) {
                case "pattern" -> putRow(rows, "Pattern", section.inline());
                case "request" -> {
                    String value = section.inline();
                    int space = value.indexOf(' ');
                    String verb = space > 0 ? value.substring(0, space).toUpperCase(java.util.Locale.ROOT) : "";
                    if (HTTP_VERBS.contains(verb)) {
                        method = verb;
                        url = value.substring(space + 1).strip();
                    } else if (!value.isEmpty()) {
                        url = value;
                    }
                }
                case "duration" -> elapsed = withUnit(section.inline(), "ms");
                case "elapsed" -> elapsed = withUnit(section.inline(), "");
                case "delay" -> putRow(rows, "Delay", section.inline());
                case "response" -> status = section.inline();
                case "reqheaders" -> putJoinedRow(rows, "Request headers", section);
                case "respheaders" -> putJoinedRow(rows, "Response headers", section);
                case "reqbody" -> putBodyRow(rows, "Request body", section);
                case "respbody" -> putBodyRow(rows, "Response body", section);
                case "rule" -> putRow(rows, "Rule", section.inline());
                case "applied" -> putRow(rows, "Applied", section.inline());
                case "result" -> putRow(rows, "Result", section.inline());
                case "stage" -> putRow(rows, "Stage", section.inline());
                default -> putRow(rows, capitalize(section.label()), section.inline());
            }
        }
        if (sections.isEmpty()) {
            //  没有任何分区（格式未知/上报被裁断）：保留原文，信息不丢
            putRow(rows, "Detail", body.strip());
        }
        return new ApiCallBlock(kind, method, url, status, elapsed, List.copyOf(rows));
    }

    /**
     * 把块正文切成若干分区。
     *
     * <p>判据：<b>恰好 2 空格缩进</b>且含冒号的行开启新分区（{@code "  reqHeaders:"}）；
     * 4 空格缩进的行是上一分区的续行（{@code "    key: value"} 头、或 JSON 体的一行）。
     * 这样 JSON 体里的换行不会被误判成新分区。
     */
    private static List<Section> splitSections(String body) {
        List<Section> sections = new ArrayList<>();
        String label = null;
        String inline = "";
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            int colon = sectionColon(line);
            if (colon > 0) {
                if (label != null) {
                    sections.add(new Section(label, inline, List.copyOf(lines)));
                }
                label = line.substring(2, colon).trim();
                inline = line.substring(colon + 1).trim();
                lines = new ArrayList<>();
            } else if (label != null) {
                String content = stripIndent(line);
                if (!content.isBlank()) {
                    lines.add(content);
                }
            }
        }
        if (label != null) {
            sections.add(new Section(label, inline, List.copyOf(lines)));
        }
        return sections;
    }

    /** 分区起始行判定：返回冒号下标；非分区行返回 {@code -1}。 */
    private static int sectionColon(String line) {
        if (line == null || line.length() < 3
                || line.charAt(0) != ' ' || line.charAt(1) != ' ' || line.charAt(2) == ' ') {
            return -1;
        }
        return line.indexOf(':', 2);
    }

    /** 去掉行首最多 4 个空格（分区续行的固定缩进），保留更深层的相对缩进（JSON 用）。 */
    private static String stripIndent(String line) {
        int i = 0;
        while (i < 4 && i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(i);
    }

    /** {@code key: value} 续行合并为 JSON 对象渲染（2026-10-22 用户反馈：headers 应按 JSON 格式展示，
     *  而不是 {@code k=v; k=v} 拼成一坨）。值经 Jackson 转义，避免 header 值里含引号/大括号破坏结构。 */
    private static void putJoinedRow(List<Map<String, String>> rows, String label, Section section) {
        Map<String, String> obj = new LinkedHashMap<>();
        for (String line : section.lines()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                obj.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        if (obj.isEmpty()) {
            if (!section.inline().isBlank()) {
                putRow(rows, label, section.inline());
            }
            return;
        }
        String pretty = prettyJsonObject(obj);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", pretty == null ? section.inline() : pretty);
        if (pretty != null) {
            row.put("json", "true");
        }
        rows.add(row);
    }

    private static String prettyJsonObject(Map<String, String> map) {
        try {
            return reindent(JSON_MAPPER.writer(new JsonPrettyPrinter()).writeValueAsString(map));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 体分区：整体带缩进展示，是 JSON 时自动美化并打 {@code json} 标记。 */
    /**
     * 体分区：整体带缩进展示，是 JSON 时自动美化并打 {@code json} 标记。
     *
     * <p>响应体<b>不再截断</b>（2026-10-22 用户要求），采集侧已无 {@code [truncated]} /
     * {@code [evidence limited ...]} 标记，故这里也不再渲染说明行。
     */
    private static void putBodyRow(List<Map<String, String>> rows, String label, Section section) {
        String raw = String.join("\n", section.lines());
        if (raw.isBlank()) {
            raw = section.inline();
        }
        if (raw.isBlank()) {
            return;
        }
        String pretty = prettyIfJson(raw);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", pretty == null ? raw : pretty);
        if (pretty != null) {
            row.put("json", "true");
        }
        rows.add(row);
    }

    private static void putRow(List<Map<String, String>> rows, String label, String value) {
        if (label == null || value == null || value.isBlank()) {
            return;
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value.strip());
        rows.add(row);
    }

    /** 控制台镜像单行：{@code NET POST https://host/x -> 200 (86 ms)}。 */
    private static ApiCallBlock parseCapturedLine(String kind, String text) {
        String body = text.startsWith("NET ") ? text.substring(4).strip() : text;
        String method = "";
        String url = body;
        String status = "";
        String elapsed = "";
        int space = body.indexOf(' ');
        String verb = space > 0 ? body.substring(0, space).toUpperCase(java.util.Locale.ROOT) : "";
        if (HTTP_VERBS.contains(verb)) {
            method = verb;
            String rest = body.substring(space + 1).strip();
            int arrow = rest.indexOf("->");
            String tail = "";
            if (arrow >= 0) {
                tail = rest.substring(arrow + 2).strip();
                rest = rest.substring(0, arrow).strip();
            }
            url = rest;
            int paren = tail.indexOf('(');
            status = paren >= 0 ? tail.substring(0, paren).strip() : tail;
            if (paren >= 0) {
                elapsed = tail.substring(paren + 1).replace(")", "").strip();
            }
        }
        return new ApiCallBlock(kind.isEmpty() ? "CAPTURED" : kind, method, url, status, elapsed, List.of());
    }

    /**
     * 去掉头部行尾的分隔符（{@code "url — "} / {@code "url - "} / {@code "url:"}）。
     *
     * <p>只剥离「空白 + 分隔符」结尾，不碰 URL 自身末尾的连字符（{@code .../a-b-} 必须原样保留）。
     */
    private static String stripTrailingSeparators(String head) {
        String out = head.stripTrailing();
        while (out.length() > 1 && isSeparator(out.charAt(out.length() - 1))
                && Character.isWhitespace(out.charAt(out.length() - 2))) {
            out = out.substring(0, out.length() - 1).stripTrailing();
        }
        if (out.endsWith(":")) {
            out = out.substring(0, out.length() - 1).stripTrailing();
        }
        return out;
    }

    private static boolean isSeparator(char c) {
        return c == '—' || c == '–' || c == '-' || c == ':';
    }

    /** 数值型耗时补单位（{@code "417"} → {@code "417 ms"}；已是 {@code "417ms"} 则原样）。 */
    private static String withUnit(String value, String unit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String v = value.strip();
        if (unit.isEmpty() || v.endsWith(unit)) {
            return v;
        }
        return v + " " + unit;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public String getKind() {
        return kind;
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public String getStatus() {
        return status;
    }

    public String getElapsed() {
        return elapsed;
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * AgentTrace 导出（R9）：把存储导出为脱敏后的 JSON 文件。
 *
 * <p>不变式：
 * <ul>
 *   <li>输出只含 {@link AgentTrace#toMap()} 的最小元数据，<b>任何原始 secret 都不会出现</b>；</li>
 *   <li>导出物<b>不可重执行</b>：不含任何命令、回放指令或可执行内容；</li>
 *   <li>写入前确保父目录存在（{@code Files.createDirectories}）。</li>
 * </ul>
 */
public final class AgentTraceWriter {

    private AgentTraceWriter() {
    }

    /** 把整个存储导出为 {@code dir/fileName} 的 JSON 数组文件，返回实际路径。 */
    public static Path writeTo(Path dir, String fileName, AgentTraceStore store) throws IOException {
        Path file = dir.resolve(fileName);
        List<AgentTrace> all = store.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < all.size(); i++) {
            sb.append("  ").append(toJsonObject(all.get(i).toMap()));
            sb.append(i < all.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        Files.createDirectories(dir);
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** 把安全 Map 渲染为单行紧凑 JSON 对象（键已按顺序，值为 String/Long）。 */
    static String toJsonObject(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(quote(e.getKey())).append(": ");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number) {
                sb.append(v.toString());
            } else {
                sb.append(quote(v.toString()));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /** JSON 字符串转义（覆盖引号、反斜杠、控制字符）。 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}

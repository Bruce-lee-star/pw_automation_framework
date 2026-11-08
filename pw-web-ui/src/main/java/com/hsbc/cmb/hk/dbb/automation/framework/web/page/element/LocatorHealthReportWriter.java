package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 定位健康度报告落盘器：把 {@link LocatorHealthRecorder} 的内存快照写成
 * {@code locator-health.json}（机器消费 / 后续聚合基线）与同名 {@code .md}（人看）。
 *
 * <p><b>时机</b>：JVM 退出时由 {@link com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator}
 * 调一次（有界任务，抛异常只记 WARN，绝不影响退出码）。也可由测试/CI 直接调 {@link #write(Path)}。</p>
 *
 * <p><b>路径</b>：{@code -Dpw.locator.health.output=<file.json>}，默认 {@value #DEFAULT_OUTPUT}；
 * 报告目录不存在会自动创建。</p>
 *
 * <p><b>产物内容</b>：按选择器聚合的用量与违例（{@code selectors}），以及逐条 strict 违例证据
 * （{@code violations}：场景、选择器、命中数、操作、线程、时间）。无采集数据时<b>不落盘</b>，
 * 避免给未跑浏览器用例的工程制造空文件。</p>
 */
public final class LocatorHealthReportWriter {

    /** 默认输出路径（相对工作目录）。 */
    public static final String DEFAULT_OUTPUT = "target/locator-health.json";
    /** 输出路径系统属性键。 */
    public static final String OUTPUT_PROPERTY = "pw.locator.health.output";

    private static final Logger LOGGER = LoggerFactory.getLogger(LocatorHealthReportWriter.class);
    private static final int TOP_N = 20;

    private LocatorHealthReportWriter() {
    }

    /** 关闭钩子入口：有数据才写；任何异常只记 WARN。 */
    public static void writeDefaultIfAny() {
        try {
            if (LocatorHealthRecorder.snapshot().isEmpty()) {
                return;
            }
            String configured = System.getProperty(OUTPUT_PROPERTY);
            Path target = Paths.get(configured == null || configured.isBlank() ? DEFAULT_OUTPUT : configured.trim());
            write(target);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[locator-health] report write skipped: {}", e.toString());
        }
    }

    /**
     * 写报告（JSON + 同名 Markdown）。
     *
     * @param jsonFile JSON 目标文件；{@code .md} 写在其同目录同名文件
     * @return 实际写入的 JSON 路径
     */
    public static Path write(Path jsonFile) throws IOException {
        if (jsonFile == null) {
            throw new IllegalArgumentException("jsonFile must not be null");
        }
        Path parent = jsonFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(buildJson());
        Files.writeString(jsonFile, json, StandardCharsets.UTF_8);
        Files.writeString(siblingMarkdown(jsonFile), buildMarkdown(), StandardCharsets.UTF_8);
        return jsonFile;
    }

    private static JsonObject buildJson() {
        List<LocatorHealthRecorder.SelectorHealth> selectors = LocatorHealthRecorder.snapshot();
        List<LocatorHealthRecorder.Violation> violations = LocatorHealthRecorder.violations();

        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("selectorsTotal", selectors.size());
        root.addProperty("violationsTotal", violations.size());
        root.addProperty("truncated", LocatorHealthRecorder.truncated());

        JsonArray selectorArray = new JsonArray();
        for (LocatorHealthRecorder.SelectorHealth health : selectors) {
            JsonObject node = new JsonObject();
            node.addProperty("selector", health.selector());
            node.addProperty("kind", health.kind());
            node.addProperty("uses", health.uses());
            node.addProperty("violations", health.violations());
            node.addProperty("maxMatchCount", health.maxMatchCount());
            selectorArray.add(node);
        }
        root.add("selectors", selectorArray);

        JsonArray violationArray = new JsonArray();
        for (LocatorHealthRecorder.Violation violation : violations) {
            JsonObject node = new JsonObject();
            node.addProperty("scenario", violation.scenario());
            node.addProperty("selector", violation.selector());
            node.addProperty("kind", violation.kind());
            node.addProperty("operation", violation.operation());
            node.addProperty("matchCount", violation.matchCount());
            node.addProperty("thread", violation.thread());
            node.addProperty("at", violation.at().toString());
            violationArray.add(node);
        }
        root.add("violations", violationArray);
        return root;
    }

    private static String buildMarkdown() {
        List<LocatorHealthRecorder.SelectorHealth> selectors = LocatorHealthRecorder.snapshot();
        List<LocatorHealthRecorder.Violation> violations = LocatorHealthRecorder.violations();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# 定位健康度报告\n\n")
                .append("- 采集选择器: ").append(selectors.size())
                .append("（strict 违例 ").append(violations.size()).append(" 条")
                .append(LocatorHealthRecorder.truncated() ? "，已达上界被截断" : "").append("）\n")
                .append("- 当前场景名缺失时记为 `unknown`（非 Serenity 运行属正常）\n\n");

        sb.append("## strict 违例（一次定位命中多个元素）\n\n");
        if (violations.isEmpty()) {
            sb.append("无。\n");
        } else {
            sb.append("| 场景 | 选择器 | 类型 | 命中数 | 操作 | 线程 |\n")
                    .append("| --- | --- | --- | --- | --- | --- |\n");
            for (LocatorHealthRecorder.Violation v : violations) {
                sb.append("| ").append(cell(v.scenario()))
                        .append(" | ").append(cell("`" + v.selector() + "`"))
                        .append(" | ").append(v.kind())
                        .append(" | ").append(v.matchCount())
                        .append(" | ").append(cell(v.operation()))
                        .append(" | ").append(cell(v.thread()))
                        .append(" |\n");
            }
        }

        sb.append("\n## 选择器用量 TOP ").append(TOP_N).append("\n\n")
                .append("| 选择器 | 类型 | 用量 | 违例 | 最大命中数 |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        int shown = 0;
        for (LocatorHealthRecorder.SelectorHealth health : selectors) {
            if (shown++ >= TOP_N) {
                break;
            }
            sb.append("| ").append(cell("`" + health.selector() + "`"))
                    .append(" | ").append(health.kind())
                    .append(" | ").append(health.uses())
                    .append(" | ").append(health.violations())
                    .append(" | ").append(health.maxMatchCount() == 0 ? "-" : health.maxMatchCount())
                    .append(" |\n");
        }
        sb.append("\n> 说明：违例 = Playwright strict 模式判定的「一次定位命中多个元素」。")
                .append("列表项/表格行这类**本来就多个匹配**的选择器应使用 `.nth(i)` 或 `PageElementList`，")
                .append("否则会在交互时抛 strict 违例；本报告只做事实采集，不改变执行语义。\n");
        return sb.toString();
    }

    private static Path siblingMarkdown(Path jsonFile) {
        // 根路径没有 fileName 段；回退为固定基名，避免对可能为空的返回值解引用
        Path fileName = jsonFile.getFileName();
        String name = fileName == null ? "report" : fileName.toString();
        String base = name.toLowerCase(Locale.ROOT).endsWith(".json")
                ? name.substring(0, name.length() - ".json".length()) : name;
        return jsonFile.resolveSibling(base + ".md");
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}

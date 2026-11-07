package com.hsbc.cmb.hk.dbb.automation.framework.bridge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.cli.SkeletonCli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MCP 工具 ↔ {@link SkeletonCli} 的适配层：<b>只调用可测入口 {@code run(...)}，绝不调用
 * {@code main(...)}</b>（后者含 {@code System.exit}，会直接终止 stdio server）。
 *
 * <p>为什么复用 CLI 而不是另写一套库调用：{@code check} 的「确定性基线翻译 → 统一门禁
 * （{@code IntentPlanValidator}）→ 结构化等价对账（{@code PlanEquivalence}）」是安全边界，
 * 桥接层不得绕过或复制；走 CLI 保证 IDE 与 CI 是同一条判定链。桥接层只补两件事：</p>
 * <ol>
 *   <li>把 tool 的字符串参数落成临时骨架文件 / stdin（CLI 只吃路径，且 prompt 不支持 stdin）；</li>
 *   <li><b>补齐 CLI 的结构化缺口</b>：{@code check} 原本只回 {@code accepted/equivalent} 文本、
 *       不回计划本体，IDE 还得手工复制。这里在门禁通过时把建议里的 StepPlan JSON 原样作为
 *       {@code plan} 对象回传，实现「一次会话拿到可采信 StepPlan、手工复制 0 次」。</li>
 * </ol>
 *
 * <p>所有 CLI 输出都被捕获进内存流，<b>不写协议 stdout</b>；临时骨架文件用完即删。
 * {@code --origin} 由 {@link McpConfig} 注入，{@code --keep-values/--out/--sources/--features}
 * 一律不暴露。</p>
 */
public final class SkeletonToolAdapter {

    /** 单个字符串参数上限，防 IDE client 用超大文本拖垮进程。 */
    public static final int MAX_INPUT_CHARS = 1_000_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一次工具执行的结论：CLI 退出码 + 回给 client 的文本 + 仅供 stderr 的诊断。 */
    public record Outcome(int cliExit, String text, String diagnostics) {
    }

    private record CliResult(int exit, String stdout, String stderr) {
    }

    @FunctionalInterface
    private interface SkeletonTask {
        Outcome run(Path skeletonFile) throws IOException;
    }

    public SkeletonToolAdapter() {
    }

    /** prompt 工具：返回与 CLI 逐字一致的提示词（默认脱敏）。 */
    public Outcome prompt(String skeletonJson) throws IOException {
        requireInput("skeletonJson", skeletonJson);
        return withSkeleton(skeletonJson, file -> {
            CliResult result = invoke(new String[]{"prompt", file.toString()},
                    new ByteArrayInputStream(new byte[0]));
            return new Outcome(result.exit(), result.stdout().trim(), result.stderr());
        });
    }

    /**
     * check 工具：门禁 + 对账，成功时把 StepPlan JSON 一并返回。
     *
     * <p>{@code plan} 直接取自助手原文中首个 {@code {} 至末个 {@code }} 的子串，与
     * {@code SuggestionIntake.parseSuggestion} 同口径；不重新序列化（避免与
     * {@code StepPlan.parse} 的白名单字段产生第二套格式），可被路径 C 的
     * {@code StepPlanCodegenCli --plan} 直接消费。</p>
     */
    public Outcome check(String skeletonJson, String suggestionText) throws IOException {
        requireInput("skeletonJson", skeletonJson);
        requireInput("suggestionText", suggestionText);
        String origins = McpConfig.originsArgument();
        if (origins.isEmpty()) {
            return new Outcome(SkeletonCli.EXIT_INPUT_ERROR, "",
                    "server is missing approved origins; set -D" + McpConfig.ORIGINS_PROPERTY
                            + " or " + McpConfig.ORIGINS_ENV);
        }
        return withSkeleton(skeletonJson, file -> {
            CliResult result = invoke(
                    new String[]{"check", file.toString(), "-", "--origin", origins},
                    new ByteArrayInputStream(suggestionText.getBytes(StandardCharsets.UTF_8)));

            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("ok", result.exit() == SkeletonCli.EXIT_OK);
            payload.put("exitCode", result.exit());
            payload.put("summary", result.stdout().trim());
            if (result.exit() == SkeletonCli.EXIT_OK) {
                payload.set("plan", extractPlan(suggestionText));
            } else {
                payload.putNull("plan");
            }
            if (!result.stderr().isBlank()) {
                payload.put("diagnostics", result.stderr().trim());
            }
            return new Outcome(result.exit(), MAPPER.writeValueAsString(payload), result.stderr());
        });
    }

    private static void requireInput(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required and must not be blank");
        }
        if (value.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_INPUT_CHARS + " characters");
        }
    }

    private static Outcome withSkeleton(String json, SkeletonTask task) throws IOException {
        Path file = Files.createTempFile("pw-mcp-skeleton-", ".json");
        file.toFile().deleteOnExit();
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return task.run(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static CliResult invoke(String[] args, InputStream in) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = SkeletonCli.run(args, in,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CliResult(exit,
                stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static JsonNode extractPlan(String suggestionText) {
        int start = suggestionText.indexOf('{');
        int end = suggestionText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("suggestion contains no JSON object");
        }
        try {
            return MAPPER.readTree(suggestionText.substring(start, end + 1));
        } catch (IOException e) {
            throw new IllegalArgumentException("suggestion is not valid JSON", e);
        }
    }
}

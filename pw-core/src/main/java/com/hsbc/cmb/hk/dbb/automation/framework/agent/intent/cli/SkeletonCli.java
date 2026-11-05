package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.cli;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.IntentTranslationException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedSkeleton;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RuleBasedIntentTranslator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.SkeletonPromptBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.SuggestionIntake;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令行入口：把「录制骨架 → 提示词」「IDE 助手回文 → 校验」两件事做成两条子命令，
 * 便于在 IDEA / VS Code 终端或 Run Configuration 里直接用（设计 §4.3 的 IDE 助手形态）。
 *
 * <pre>
 * ① 生成提示词（值默认占位化，可直接粘给 Copilot）
 *    SkeletonCli prompt target/skeleton.json --out target/prompt.md
 *
 * ② 校验助手回文（可整段粘贴，含代码围栏/说明文字）
 *    SkeletonCli check target/skeleton.json target/reply.md --origin www.example.com:8443
 *    type reply.md | SkeletonCli check target/skeleton.json - --origin www.example.com:8443
 * </pre>
 *
 * <p><b>职责边界</b>：本类只做「参数解析 + 文件/标准输入输出 + 退出码」，
 * 提示词组装、解析、门禁、等价对账全部复用既有类
 * （{@link SkeletonPromptBundle}、{@link SuggestionIntake}、{@link RecordedSkeleton#parse}），
 * 不重复实现任何策略——避免 CLI 与库两条路径行为漂移。</p>
 *
 * <p><b>退出码</b>（便于 CI / 脚本串联）：{@code 0} 成功或建议可采信；
 * {@code 1} 建议被拒或与基线不等价（内容问题）；{@code 2} 用法/IO/解析失败（输入问题）。</p>
 *
 * <p><b>编码</b>：文件读写一律 UTF-8；控制台输出用调用方传入的 {@link PrintStream}
 * （不强行改编码，避免 Windows 控制台出现乱码）。</p>
 */
public final class SkeletonCli {

    /** 成功 / 建议可采信。 */
    public static final int EXIT_OK = 0;
    /** 建议未通过门禁或与基线不等价（内容问题，可重试）。 */
    public static final int EXIT_REJECTED = 1;
    /** 用法错误、文件不可读写、骨架/建议无法解析（输入问题）。 */
    public static final int EXIT_INPUT_ERROR = 2;

    private static final String CMD_PROMPT = "prompt";
    private static final String CMD_CHECK = "check";
    private static final String FLAG_OUT = "--out";
    private static final String FLAG_KEEP_VALUES = "--keep-values";
    private static final String FLAG_ORIGIN = "--origin";
    private static final String STDIN = "-";

    private SkeletonCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /**
     * 可测试入口（不调用 {@link System#exit(int)}、不直接触碰全局流）。
     *
     * @return {@link #EXIT_OK} / {@link #EXIT_REJECTED} / {@link #EXIT_INPUT_ERROR}
     */
    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            usage(err);
            return EXIT_INPUT_ERROR;
        }
        String command = args[0];
        try {
            switch (command) {
                case CMD_PROMPT:
                    return runPrompt(slice(args), out, err);
                case CMD_CHECK:
                    return runCheck(slice(args), in, out, err);
                case "-h":
                case "--help":
                case "help":
                    usage(out);
                    return EXIT_OK;
                default:
                    err.println("未知子命令: " + command);
                    usage(err);
                    return EXIT_INPUT_ERROR;
            }
        } catch (IntentTranslationException e) {
            // 骨架/建议不合规：命令行只需要「为什么」，不需要堆栈
            err.println("[" + e.reason() + "] " + e.getMessage());
            return EXIT_INPUT_ERROR;
        } catch (IOException e) {
            err.println("[IO] " + e.getMessage());
            return EXIT_INPUT_ERROR;
        }
    }

    // ------------------------------------------------------------------
    // prompt：骨架 → 提示词
    // ------------------------------------------------------------------

    private static int runPrompt(List<String> args, PrintStream out, PrintStream err) throws IOException {
        String input = null;
        String outputPath = null;
        boolean redact = true;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (FLAG_OUT.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_OUT + " 需要一个文件路径");
                    return EXIT_INPUT_ERROR;
                }
                outputPath = args.get(++i);
            } else if (FLAG_KEEP_VALUES.equals(arg)) {
                redact = false;
            } else if (arg.startsWith("-")) {
                err.println("未知选项: " + arg);
                usage(err);
                return EXIT_INPUT_ERROR;
            } else if (input == null) {
                input = arg;
            } else {
                err.println("多余参数: " + arg);
                usage(err);
                return EXIT_INPUT_ERROR;
            }
        }
        if (input == null) {
            err.println("缺少骨架文件参数");
            usage(err);
            return EXIT_INPUT_ERROR;
        }
        RecordedSkeleton skeleton = readSkeleton(input);
        String bundle = SkeletonPromptBundle.of(skeleton, redact);
        if (outputPath == null) {
            out.println(bundle);
        } else {
            writeFile(outputPath, bundle);
            out.println("提示词已写入: " + outputPath
                    + (redact ? "（值已占位化）" : "（保留原值）"));
        }
        return EXIT_OK;
    }

    // ------------------------------------------------------------------
    // check：助手回文 → 门禁 + 等价对账
    // ------------------------------------------------------------------

    private static int runCheck(List<String> args, InputStream in, PrintStream out, PrintStream err)
            throws IOException {
        String skeletonPath = null;
        String suggestionSource = null;
        Set<String> origins = new LinkedHashSet<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (FLAG_ORIGIN.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_ORIGIN + " 需要已批准来源（逗号分隔）");
                    return EXIT_INPUT_ERROR;
                }
                for (String origin : args.get(++i).split(",")) {
                    if (!origin.isBlank()) {
                        origins.add(origin.trim());
                    }
                }
            } else if (arg.startsWith("-") && !STDIN.equals(arg)) {
                err.println("未知选项: " + arg);
                usage(err);
                return EXIT_INPUT_ERROR;
            } else if (skeletonPath == null) {
                skeletonPath = arg;
            } else if (suggestionSource == null) {
                suggestionSource = arg;
            } else {
                err.println("多余参数: " + arg);
                usage(err);
                return EXIT_INPUT_ERROR;
            }
        }
        if (skeletonPath == null || suggestionSource == null) {
            err.println("check 需要 <骨架文件> 与 <建议文件|->");
            usage(err);
            return EXIT_INPUT_ERROR;
        }
        if (origins.isEmpty()) {
            err.println("缺少 " + FLAG_ORIGIN + "（基线翻译器需要已批准来源，如 "
                    + "www.example.com:8443）");
            return EXIT_INPUT_ERROR;
        }
        RecordedSkeleton skeleton = readSkeleton(skeletonPath);
        String suggestion = STDIN.equals(suggestionSource)
                ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                : readFile(suggestionSource);

        SuggestionIntake.Result result = SuggestionIntake.check(skeleton,
                new RuleBasedIntentTranslator(origins), suggestion);
        out.println(result.summary());
        return result.ok() ? EXIT_OK : EXIT_REJECTED;
    }

    // ------------------------------------------------------------------
    // IO / 用法
    // ------------------------------------------------------------------

    private static RecordedSkeleton readSkeleton(String path) throws IOException {
        return RecordedSkeleton.parse(readFile(path));
    }

    private static String readFile(String path) throws IOException {
        Path file = Paths.get(path);
        if (!Files.isReadable(file)) {
            throw new IOException("文件不存在或不可读: " + path);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static void writeFile(String path, String content) throws IOException {
        Path file = Paths.get(path);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static List<String> slice(String[] args) {
        List<String> rest = new ArrayList<>(args.length - 1);
        for (int i = 1; i < args.length; i++) {
            rest.add(args[i]);
        }
        return rest;
    }

    private static void usage(PrintStream stream) {
        stream.println("用法:");
        stream.println("  SkeletonCli prompt <骨架.json> [--out <提示词.md>] [--keep-values]");
        stream.println("      生成可粘贴给 IDE 助手的提示词；默认把 value/option 换成 ${...} 占位符");
        stream.println("  SkeletonCli check <骨架.json> <建议文件|-> --origin <已批准来源[,更多]>");
        stream.println("      校验助手回文（可含代码围栏/说明文字）：门禁 + 与基线结构等价对账");
        stream.println("  SkeletonCli --help");
        stream.println();
        stream.println("退出码: 0=可采信 1=被拒/不等价 2=用法或解析失败");
        stream.println("示例:");
        stream.println("  SkeletonCli prompt target/skeleton.json --out target/prompt.md");
        stream.println("  SkeletonCli check target/skeleton.json target/reply.md --origin www.example.com:8443");
    }
}

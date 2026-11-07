package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.cli;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.impact.CaseCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.impact.CaseImpactScreener;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.impact.ChangeSignal;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.impact.CodeTargetScanner;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.impact.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.impact.FeatureCase;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.impact.ImpactReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.impact.SelectorAnalyzer;
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
 *
 * ③ 变更影响分诊（本次改了什么 → 哪些存量用例必须改、哪些可以不动、代码改哪几行）
 *    SkeletonCli impact --features src/test/resources/features --skeleton target/skeleton.json
 *    SkeletonCli impact --features src/test/resources/features --keyword logon,@login --only-actionable
 *    SkeletonCli impact --features ... --skeleton ... --sources src/test/java,src/test/resources
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
    private static final String CMD_IMPACT = "impact";
    private static final String FLAG_OUT = "--out";
    private static final String FLAG_KEEP_VALUES = "--keep-values";
    private static final String FLAG_ORIGIN = "--origin";
    private static final String FLAG_FEATURES = "--features";
    private static final String FLAG_SOURCES = "--sources";
    private static final String FLAG_SKELETON = "--skeleton";
    private static final String FLAG_TAG = "--tag";
    private static final String FLAG_KEYWORD = "--keyword";
    private static final String FLAG_SELECTOR = "--selector";
    private static final String FLAG_ONLY_ACTIONABLE = "--only-actionable";
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
                case CMD_IMPACT:
                    return runImpact(slice(args), out, err);
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
    // impact：本次变更 → 存量用例影响分诊
    // ------------------------------------------------------------------

    /**
     * 变更影响分诊：由「变更后的新骨架」+ 人工补充的关键词/tag 派生信号，
     * 对存量 feature 用例输出「必须维护 / 需复核 / 可不动」三级结论；
     * 给 {@code --sources} 时再扫代码层落点（{@code @RoleElement}/{@code @Element} 与资源键值）。
     *
     * <p>信号来源至少一项（骨架 / --keyword / --tag），否则报错——
     * 空信号会把全部用例判成「可不动」，属危险结论，故直接拒绝。</p>
     *
     * @return 存在「必须维护」用例时返回 {@link #EXIT_REJECTED}（便于 CI 拦门），否则 {@link #EXIT_OK}
     */
    private static int runImpact(List<String> args, PrintStream out, PrintStream err) throws IOException {
        List<Path> roots = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        String skeletonPath = null;
        String outputPath = null;
        boolean onlyActionable = false;
        Set<String> keywords = new LinkedHashSet<>();
        Set<String> tags = new LinkedHashSet<>();
        Set<String> selectors = new LinkedHashSet<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (FLAG_FEATURES.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_FEATURES + " 需要 feature 目录（逗号分隔可多个）");
                    return EXIT_INPUT_ERROR;
                }
                for (String raw : args.get(++i).split(",")) {
                    if (!raw.isBlank()) {
                        roots.add(Paths.get(raw.trim()));
                    }
                }
            } else if (FLAG_SOURCES.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_SOURCES + " 需要源码/资源目录（逗号分隔可多个）");
                    return EXIT_INPUT_ERROR;
                }
                for (String raw : args.get(++i).split(",")) {
                    if (!raw.isBlank()) {
                        sources.add(Paths.get(raw.trim()));
                    }
                }
            } else if (FLAG_SKELETON.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_SKELETON + " 需要一个骨架文件路径");
                    return EXIT_INPUT_ERROR;
                }
                skeletonPath = args.get(++i);
            } else if (FLAG_KEYWORD.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_KEYWORD + " 需要关键词（逗号分隔可多个）");
                    return EXIT_INPUT_ERROR;
                }
                addCsv(keywords, args.get(++i));
            } else if (FLAG_TAG.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_TAG + " 需要 tag（逗号分隔可多个）");
                    return EXIT_INPUT_ERROR;
                }
                addTags(tags, args.get(++i));
            } else if (FLAG_SELECTOR.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_SELECTOR + " 需要一个 CSS/XPath 选择器（可重复；不按逗号拆分，"
                            + "以免破坏 a,b 并列选择器）");
                    return EXIT_INPUT_ERROR;
                }
                String selector = args.get(++i);
                try {
                    SelectorAnalyzer.analyze(selector);
                } catch (IllegalArgumentException e) {
                    err.println("选择器不合法: " + e.getMessage());
                    return EXIT_INPUT_ERROR;
                }
                selectors.add(selector);
            } else if (FLAG_ONLY_ACTIONABLE.equals(arg)) {
                onlyActionable = true;
            } else if (FLAG_OUT.equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println(FLAG_OUT + " 需要一个文件路径");
                    return EXIT_INPUT_ERROR;
                }
                outputPath = args.get(++i);
            } else if (arg.startsWith("-")) {
                err.println("未知选项: " + arg);
                usage(err);
                return EXIT_INPUT_ERROR;
            } else if (roots.isEmpty()) {
                // 允许把 feature 目录直接作为位置参数
                roots.add(Paths.get(arg));
            } else {
                err.println("多余参数: " + arg);
                usage(err);
                return EXIT_INPUT_ERROR;
            }
        }

        if (roots.isEmpty()) {
            err.println("impact 需要 " + FLAG_FEATURES + " <feature 目录>");
            usage(err);
            return EXIT_INPUT_ERROR;
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                err.println("feature 目录不存在或不是目录: " + root);
                return EXIT_INPUT_ERROR;
            }
        }
        for (Path source : sources) {
            if (!Files.isDirectory(source)) {
                err.println(FLAG_SOURCES + " 目录不存在或不是目录: " + source);
                return EXIT_INPUT_ERROR;
            }
        }
        ChangeSignal signal = buildSignal(skeletonPath, keywords, tags).withSelectors(selectors);
        if (signal.isEmpty()) {
            err.println("变更信号为空：至少提供 " + FLAG_SKELETON + " / " + FLAG_KEYWORD + " / " + FLAG_TAG
                    + " / " + FLAG_SELECTOR + " 之一（空信号会把全部用例误判为可不动）");
            return EXIT_INPUT_ERROR;
        }
        List<FeatureCase> cases = new ArrayList<>();
        for (Path root : roots) {
            cases.addAll(CaseCatalog.scan(root));
        }
        if (cases.isEmpty()) {
            err.println("给定目录下未找到 *.feature 用例: " + roots);
            return EXIT_INPUT_ERROR;
        }

        if (!sources.isEmpty() && signal.elementRefs().isEmpty() && signal.selectors().isEmpty()) {
            err.println("[提示] 本次信号不含元素/选择器（请用 " + FLAG_SKELETON + "、" + FLAG_SELECTOR
                    + " 或元素名提供），代码层落点无法定位，仅输出用例侧分诊");
        }
        CaseImpactScreener.Report caseReport = CaseImpactScreener.screen(cases, signal);
        CodeTargetScanner.ScanResult scan = sources.isEmpty()
                ? new CodeTargetScanner.ScanResult(List.of(), 0, false)
                : CodeTargetScanner.scan(sources, signal);
        if (scan.truncated()) {
            err.println("[警告] 源码文件数超过 " + CodeTargetScanner.MAX_FILES
                    + " 上界，仅扫描前 " + CodeTargetScanner.MAX_FILES + " 个文件；请缩小 " + FLAG_SOURCES);
        }
        ImpactReport report = new ImpactReport(caseReport, scan.targets());
        String markdown = report.toMarkdown(onlyActionable);
        String counts = "（必须维护 " + caseReport.count(CaseImpactScreener.Verdict.MUST_MAINTAIN)
                + " / 需复核 " + caseReport.count(CaseImpactScreener.Verdict.REVIEW)
                + " / 可不动 " + caseReport.count(CaseImpactScreener.Verdict.NO_CHANGE)
                + "；代码落点 " + scan.targets().size()
                + (sources.isEmpty() ? "，未提供 " + FLAG_SOURCES : "，扫描 " + scan.scannedFiles() + " 文件")
                + "）";
        if (outputPath == null) {
            out.println(markdown);
        } else {
            writeFile(outputPath, markdown);
            out.println("影响报告已写入: " + outputPath + counts);
        }
        return caseReport.count(CaseImpactScreener.Verdict.MUST_MAINTAIN) > 0 ? EXIT_REJECTED : EXIT_OK;
    }

    /** 骨架（可选）派生信号 + 人工补充的关键词/tag，合并为一次筛选信号（保留元素角色）。 */
    private static ChangeSignal buildSignal(String skeletonPath, Set<String> keywords, Set<String> tags)
            throws IOException {
        Set<String> pages = new LinkedHashSet<>();
        Set<ElementRef> elementRefs = new LinkedHashSet<>();
        Set<String> mergedKeywords = new LinkedHashSet<>(keywords);
        Set<String> mergedTags = new LinkedHashSet<>(tags);
        if (skeletonPath != null) {
            ChangeSignal derived = ChangeSignal.fromSkeleton(readSkeleton(skeletonPath));
            pages.addAll(derived.pages());
            elementRefs.addAll(derived.elementRefs());
            mergedKeywords.addAll(derived.keywords());
            mergedTags.addAll(derived.tags());
        }
        return ChangeSignal.ofElementRefs(pages, elementRefs, mergedKeywords, mergedTags);
    }

    private static void addCsv(Set<String> target, String csv) {
        for (String raw : csv.split(",")) {
            if (!raw.isBlank()) {
                target.add(raw.trim());
            }
        }
    }

    /** tag 统一补 {@code @} 前缀（{@link ChangeSignal} 按 {@code @xxx} 与用例标签比对）。 */
    private static void addTags(Set<String> target, String csv) {
        for (String raw : csv.split(",")) {
            String tag = raw.trim();
            if (tag.isEmpty()) {
                continue;
            }
            target.add(tag.startsWith("@") ? tag : "@" + tag);
        }
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
        stream.println("  SkeletonCli impact --features <feature目录[,更多]> [--skeleton <新骨架.json>]");
        stream.println("                    [--keyword <词[,更多]>] [--tag <@tag[,更多]>] [--sources <源码目录[,更多]>]");
        stream.println("                    [--selector <css|xpath>]... [--only-actionable] [--out <报告.md>]");
        stream.println("      变更影响分诊：存量用例「必须维护 / 需复核 / 可不动」；至少给 骨架/关键词/tag/选择器 之一");
        stream.println("      --selector   role 定位不了时的兜底（可重复）；会自动剔除随机值并给稳定化建议");
        stream.println("      --sources 额外扫页面对象(@RoleElement/@Element)与资源键值，给出真正要改的代码行");
        stream.println("  SkeletonCli --help");
        stream.println();
        stream.println("退出码: 0=可采信（impact: 无必须维护用例） 1=被拒/不等价（impact: 存在必须维护用例）");
        stream.println("        2=用法、IO 或解析失败");
        stream.println("示例:");
        stream.println("  SkeletonCli prompt target/skeleton.json --out target/prompt.md");
        stream.println("  SkeletonCli check target/skeleton.json target/reply.md --origin www.example.com:8443");
        stream.println("  SkeletonCli impact --features src/test/resources/features --skeleton target/skeleton.json "
                + "--sources src/test/java,src/test/resources --only-actionable --out target/impact.md");
        stream.println("  SkeletonCli impact --features ... --selector \"[data-i18n='button_next']\" "
                + "--selector \"#userName\" --sources src/test/java");
    }
}

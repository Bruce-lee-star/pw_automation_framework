package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 代码层落点扫描器：把变更信号（{@link ChangeSignal#elementRefs()}）映射到「被测工程里可能要改的行」。
 *
 * <h3>两遍扫描（先声明、后资源）</h3>
 * <ol>
 *   <li><b>声明遍</b>（{@code *.java}）：{@code @RoleElement(...)} 的 {@code name=}/{@code key=} 取值、
 *       {@code @Element("选择器")} 的选择器原文。命中判据是
 *       <b>名字词边界匹配 + 角色一致</b>：{@code (button,next)} 能命中
 *       {@code @RoleElement(role = AriaRole.BUTTON, key = "button_next")}，但不会命中
 *       {@code @RoleElement(role = AriaRole.LINK, key = "next_help")}；信号无角色（人工
 *       {@code --keyword}/{@link ChangeSignal#of}）时退化为只按名字匹配。
 *       {@code @Element} 是 CSS/属性选择器、不携带 ARIA 角色，故始终只按名字匹配。</li>
 *   <li><b>资源遍</b>（{@code *.json} / {@code *.properties}）：只在两种情况下命中——
 *       键<b>精确等于</b>声明遍已命中的标识（如 {@code button_next}，从而覆盖该键的全部语言条目），
 *       或值（trim 后）<b>全等于</b>某个元素名。</li>
 * </ol>
 *
 * <p><b>为何角色收窄</b>：实测只按名字匹配时，{@code username} 会同时带出
 * {@code forgot_username}（BUTTON）、{@code title_username_page}（HEADING）等同名异控件声明，
 * 并把它们的 NLS 条目一并反查出来，报告 16 条里 9 条是噪音。骨架 target 里本就有 {@code role=}，
 * 补上后噪音断崖式下降。</p>
 *
 * <p><b>为何资源遍不做词边界匹配</b>：泛化匹配会让 {@code username} 命中
 * {@code push_button_next} 等十余个无关键；「声明 → 键反查」有唯一事实来源，精度高一个量级。</p>
 *
 * <p><b>有界</b>：文件数 {@value #MAX_FILES}、单文件 {@value #MAX_FILE_BYTES} 字节、
 * 跳过 {@code target/build/out/.git/.idea/node_modules/.mvn}；超限部分被忽略并由
 * {@link ScanResult#truncated()} 显式暴露，不静默吞掉。</p>
 *
 * <p><b>边界</b>：词面/键名命中，仍属「可能」而非「必须」；代码用常量或字符串拼接间接引用 NLS 键时
 * 可能命不中。报告固定带该声明。无状态、线程安全；只读文件，不写不执行。</p>
 */
public final class CodeTargetScanner {

    /** 单次扫描的文件数上界。 */
    public static final int MAX_FILES = 5000;
    /** 单文件字节上界（超过则跳过，避免被大 JSON/生成代码拖死）。 */
    public static final long MAX_FILE_BYTES = 1L << 20;

    private static final Set<String> SKIPPED_DIRS =
            Set.of("target", "build", "out", ".git", ".idea", "node_modules", ".mvn");
    private static final int SNIPPET_MAX = 160;

    private static final Pattern ROLE_ELEMENT =
            Pattern.compile("@RoleElement\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern NAME_OR_KEY =
            Pattern.compile("\\b(name|key)\\s*=\\s*\"([^\"]{1,200})\"");
    private static final Pattern ELEMENT_SELECTOR =
            Pattern.compile("@Element\\s*\\(\\s*\"([^\"]{1,200})\"");
    private static final Pattern DECLARED_ROLE =
            Pattern.compile("\\brole\\s*=\\s*(?:AriaRole\\.)?\"?([A-Za-z_]{1,40})\"?");
    private static final Pattern JSON_ENTRY =
            Pattern.compile("\"([^\"\\\\]{1,200})\"\\s*:\\s*\"([^\"\\\\]{0,400})\"");
    private static final Pattern PROPERTY_ENTRY =
            Pattern.compile("^\\s*([A-Za-z0-9._-]{1,200})\\s*[=:]\\s*(.{0,400})$");
    /** 可当作「资源键」使用的声明标识（排除选择器、含空格的显示名等）。 */
    private static final Pattern KEY_LIKE = Pattern.compile("[A-Za-z0-9._-]{2,200}");

    private CodeTargetScanner() {
    }

    /** 扫描结果：命中清单 + 统计（便于 CLI 如实报告「扫了多少、是否截断」）。 */
    public record ScanResult(List<CodeTarget> targets, int scannedFiles, boolean truncated) {
        public ScanResult {
            targets = List.copyOf(targets);
        }

        static ScanResult empty() {
            return new ScanResult(List.of(), 0, false);
        }
    }

    /** 预编译的元素匹配器（名字正则 + 期望角色）。 */
    private record ElementMatcher(ElementRef ref, Pattern namePattern) {
    }

    /**
     * 在给定源码/资源根下查找命中变更元素的代码位置。
     *
     * @param roots  源码或资源目录（不存在/非目录会被跳过，不抛异常——CLI 侧已单独校验）
     * @param signal 变更信号；{@link ChangeSignal#elementRefs()} 为空时返回空结果
     */
    public static ScanResult scan(List<Path> roots, ChangeSignal signal) throws IOException {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(signal, "signal");
        if (signal.elementRefs().isEmpty() && signal.selectors().isEmpty()) {
            return ScanResult.empty();
        }
        List<ElementMatcher> matchers = compileMatchers(signal.elementRefs());
        List<SelectorFacts> signalSelectors = new ArrayList<>(signal.selectors().size());
        for (String selector : signal.selectors()) {
            signalSelectors.add(SelectorAnalyzer.analyze(selector));
        }
        List<String> files = collectFiles(roots);
        boolean truncated = files.size() > MAX_FILES;
        if (truncated) {
            files = files.subList(0, MAX_FILES);
        }
        List<String> javaFiles = new ArrayList<>();
        List<String> resourceFiles = new ArrayList<>();
        for (String entry : files) {
            if (entry.toLowerCase(Locale.ROOT).endsWith(".java")) {
                javaFiles.add(entry);
            } else {
                resourceFiles.add(entry);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<CodeTarget> targets = new ArrayList<>();
        Set<String> declaredKeys = new LinkedHashSet<>();
        int scanned = 0;
        for (String entry : javaFiles) {
            Path file = Path.of(entry);
            String display = displayName(roots, file);
            scanned++;
            for (CodeTarget target : scanJava(Files.readString(file, StandardCharsets.UTF_8), display,
                    matchers, signalSelectors, declaredKeys)) {
                if (seen.add(keyOf(target))) {
                    targets.add(target);
                }
                if (target.kind() == CodeTarget.Kind.PAGE_OBJECT
                        && KEY_LIKE.matcher(target.identifier()).matches()) {
                    declaredKeys.add(target.identifier().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String entry : resourceFiles) {
            Path file = Path.of(entry);
            String display = displayName(roots, file);
            scanned++;
            for (CodeTarget target : scanResource(Files.readString(file, StandardCharsets.UTF_8), display,
                    signal.elements(), declaredKeys)) {
                if (seen.add(keyOf(target))) {
                    targets.add(target);
                }
            }
        }
        targets.sort(Comparator.comparing(CodeTarget::file)
                .thenComparingInt(CodeTarget::line)
                .thenComparing(CodeTarget::identifier));
        return new ScanResult(targets, scanned, truncated);
    }

    private static String keyOf(CodeTarget target) {
        return target.file() + ':' + target.line() + ':' + target.identifier();
    }

    // ------------------------------------------------------------------
    // 文件枚举
    // ------------------------------------------------------------------

    private static List<String> collectFiles(List<Path> roots) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (var walk = Files.walk(root)) {
                out.addAll(walk.filter(Files::isRegularFile)
                        .filter(p -> !inSkippedDir(root, p))
                        .filter(CodeTargetScanner::hasScannedSuffix)
                        .filter(CodeTargetScanner::withinSizeBound)
                        .map(p -> p.toAbsolutePath().normalize().toString())
                        .sorted()
                        .collect(Collectors.toList()));
            }
        }
        return out;
    }

    private static boolean hasScannedSuffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".json") || name.endsWith(".properties");
    }

    private static boolean withinSizeBound(Path path) {
        try {
            return Files.size(path) <= MAX_FILE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean inSkippedDir(Path root, Path file) {
        Path relative;
        try {
            relative = root.relativize(file);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path part : relative) {
            if (SKIPPED_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String displayName(List<Path> roots, Path file) {
        for (Path root : roots) {
            if (root != null && file.startsWith(root.toAbsolutePath().normalize())) {
                return root.toAbsolutePath().normalize().relativize(file).toString().replace('\\', '/');
            }
        }
        // 根路径（如 "/"）没有 fileName 段，getFileName() 返回 null；回退到完整路径字符串，
        // 避免对可能为空的返回值直接解引用（NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE）。
        Path fileName = file.getFileName();
        return fileName == null ? file.toString() : fileName.toString();
    }

    // ------------------------------------------------------------------
    // 声明遍 / 资源遍
    // ------------------------------------------------------------------

    private static List<CodeTarget> scanJava(String content, String display, List<ElementMatcher> matchers,
                                             List<SelectorFacts> signalSelectors, Set<String> declaredKeys) {
        List<CodeTarget> out = new ArrayList<>();
        Matcher roleElement = ROLE_ELEMENT.matcher(content);
        while (roleElement.find()) {
            String body = roleElement.group(1);
            String declaredRole = declaredRole(body);
            Matcher nameOrKey = NAME_OR_KEY.matcher(body);
            while (nameOrKey.find()) {
                String identifier = nameOrKey.group(2);
                if (hits(identifier, declaredRole, matchers)) {
                    out.add(new CodeTarget(CodeTarget.Kind.PAGE_OBJECT, display,
                            lineOf(content, roleElement.start()),
                            identifier, snippet(roleElement.group())));
                }
            }
        }
        Matcher selector = ELEMENT_SELECTOR.matcher(content);
        while (selector.find()) {
            String identifier = selector.group(1);
            SelectorFacts facts = SelectorAnalyzer.analyze(identifier);
            if (hits(identifier, null, matchers) || intersectsAny(signalSelectors, facts)) {
                out.add(new CodeTarget(CodeTarget.Kind.PAGE_OBJECT, display,
                        lineOf(content, selector.start()), identifier, snippet(selector.group()),
                        facts.stability().label(), facts.advice()));
                collectI18nKeys(facts, declaredKeys);
            }
        }
        return out;
    }

    private static boolean intersectsAny(List<SelectorFacts> signalSelectors, SelectorFacts declaration) {
        for (SelectorFacts facts : signalSelectors) {
            if (SelectorAnalyzer.intersects(facts, declaration)) {
                return true;
            }
        }
        return false;
    }

    /** 选择器里的 {@code data-i18n='button_next'} 之类属性直接给出 NLS 键，可用于反查资源条目。 */
    private static void collectI18nKeys(SelectorFacts facts, Set<String> declaredKeys) {
        for (SelectorAnchor anchor : facts.anchors()) {
            if ("attr".equals(anchor.kind()) && anchor.name().endsWith("i18n")
                    && KEY_LIKE.matcher(anchor.value()).matches()) {
                declaredKeys.add(anchor.value().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static List<CodeTarget> scanResource(String content, String display,
                                                 Set<String> elements, Set<String> declaredKeys) {
        List<CodeTarget> out = new ArrayList<>();
        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher json = JSON_ENTRY.matcher(line);
            while (json.find()) {
                addResourceHit(out, display, i, json.group(1), json.group(2), line, elements, declaredKeys);
            }
            Matcher property = PROPERTY_ENTRY.matcher(line);
            if (property.find()) {
                addResourceHit(out, display, i, property.group(1), property.group(2).trim(), line,
                        elements, declaredKeys);
            }
        }
        return out;
    }

    private static void addResourceHit(List<CodeTarget> out, String display, int index, String key,
                                       String value, String line,
                                       Set<String> elements, Set<String> declaredKeys) {
        boolean keyDeclared = declaredKeys.contains(key.trim().toLowerCase(Locale.ROOT));
        boolean valueIsElement = elements.contains(value.trim().toLowerCase(Locale.ROOT));
        if (keyDeclared || valueIsElement) {
            out.add(new CodeTarget(CodeTarget.Kind.RESOURCE, display, index + 1, key.trim(), snippet(line)));
        }
    }

    // ------------------------------------------------------------------
    // 匹配工具
    // ------------------------------------------------------------------

    private static List<ElementMatcher> compileMatchers(Set<ElementRef> refs) {
        List<ElementMatcher> matchers = new ArrayList<>(refs.size());
        for (ElementRef ref : refs) {
            if (ref == null || ref.name().isBlank()) {
                continue;
            }
            matchers.add(new ElementMatcher(ref, Pattern.compile(
                    "(?<![a-z0-9])" + Pattern.quote(ref.name()) + "(?![a-z0-9])")));
        }
        return matchers;
    }

    /**
     * 名字词边界命中，且角色兼容。
     *
     * @param declaredRole 声明的角色（小写）；{@code null} 表示该声明不携带角色（{@code @Element} 选择器）
     */
    private static boolean hits(String declaredName, String declaredRole, List<ElementMatcher> matchers) {
        if (declaredName == null || declaredName.isBlank()) {
            return false;
        }
        String lower = declaredName.toLowerCase(Locale.ROOT);
        for (ElementMatcher matcher : matchers) {
            if (!matcher.namePattern().matcher(lower).find()) {
                continue;
            }
            ElementRef ref = matcher.ref();
            if (!ref.hasRole() || declaredRole == null || declaredRole.equals(ref.role())) {
                return true;
            }
        }
        return false;
    }

    private static String declaredRole(String body) {
        Matcher m = DECLARED_ROLE.matcher(body);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        int limit = Math.min(offset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String snippet(String raw) {
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= SNIPPET_MAX ? collapsed : collapsed.substring(0, SNIPPET_MAX) + "…";
    }
}

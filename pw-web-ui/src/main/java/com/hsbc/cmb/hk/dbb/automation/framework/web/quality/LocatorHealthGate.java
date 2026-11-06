package com.hsbc.cmb.hk.dbb.automation.framework.web.quality;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.LocatorHealthRecorder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.LocatorHealthReportWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 定位健康度门禁：把 {@code locator-health.json} 与<b>基线</b>比对，出现回归即让 CI 失败。
 *
 * <p>为什么需要它：{@code locator-health.html} 只是"给人看"的报告，看没看无人验证；
 * 把本轮 strict 违例与基线对比，才能让「定位唯一」从口号变成可拦门的约束。
 *
 * <h3>判定口径</h3>
 * <ul>
 *   <li><b>NEW</b>：本轮出现违例的选择器基线里没有 → 回归；</li>
 *   <li><b>WORSENED</b>：违例次数或最大命中数比基线更高 → 回归；</li>
 *   <li><b>IMPROVED / FIXED</b>：数值下降或违例消失 → <b>不算失败</b>（提示更新基线锁定改善）；</li>
 *   <li>回归条数 &le; {@code --tolerance}（默认 0）才通过。</li>
 * </ul>
 *
 * <h3>基线</h3>
 * <p>只存"有违例的选择器"（{@code selector/kind/violations/maxMatchCount}），避免与用量类噪音一起膨胀。
 * 基线不存在时<b>直接判为输入错误（退出码 2）</b>——fail-closed，防止门禁被静默绕过；
 * 首次建立基线用 {@code --write-baseline}。
 *
 * <h3>自动门禁（业务零配置，默认生效）</h3>
 * <p>上述 CLI 是"人工/CI 显式调用"的形态。除此之外框架还提供一条<b>零配置</b>路径：
 * {@code pw-web-ui} 通过 Cucumber 的 {@code BackendProviderService} SPI 注入
 * {@code LocatorHealthGateBackend}，在每个场景收尾累积比对一次基线，
 * 首次发现回归即让该场景失败（异常信息直接进 surefire/failsafe 报告）。
 *
 * <p>企业级约束（均已在实现中固化）：
 * <ul>
 *   <li><b>首次接入不误伤</b>：基线缺失时返回 {@link AutoResult.Status#SEEDED}、<b>不判失败</b>，
 *       并在本次运行收尾按真实采集数据写出基线，日志提示提交；同一 JVM 内后续 Runner 不再判定，
 *       避免"第一个 Runner 播种、第二个 Runner 立刻判红"的自伤；</li>
 *   <li><b>只报一次</b>：回归只让<b>第一个</b>触碰到的场景失败，后续场景不连坐；</li>
 *   <li><b>自身故障不误红</b>：基线不可读 / 无采集数据 / 写盘失败一律降级（{@link AutoResult.Status#SKIPPED}
 *       或仅日志），绝不把门禁自身的 IO 问题变成用例失败；</li>
 *   <li><b>不干扰收尾</b>：判定挂在<b>场景</b>边界（Cucumber after hook），不占用
 *       {@code TestRunFinished}，因此报告插件/汇总产物照常生成；</li>
 *   <li><b>可关闭、可调容差</b>：{@link #ENABLED_PROPERTY}、{@link #TOLERANCE_PROPERTY}。</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * # 1) 首次建立基线（人工确认这批违例可接受后执行）
 * java -cp <classpath> ...web.quality.LocatorHealthGate \
 *      --current target/locator-health.json --baseline config/locator-health-baseline.json --write-baseline
 *
 * # 2) CI 校验（回归即退出码 1）
 * java -cp <classpath> ...web.quality.LocatorHealthGate \
 *      --current target/locator-health.json --baseline config/locator-health-baseline.json
 *
 * # 3) 本轮无采集产物时显式跳过（例如全部用例被跳过、或 pw.locator.health.enabled=false）
 * java -cp <classpath> ...web.quality.LocatorHealthGate \
 *      --current target/locator-health.json --baseline config/locator-health-baseline.json --allow-missing
 * }</pre>
 *
 * <p>退出码：{@value #EXIT_OK} 通过 / {@value #EXIT_REJECTED} 存在回归 / {@value #EXIT_INPUT_ERROR} 参数或文件错误。
 * 报告类产物的生成完全不受本类影响（本类只读文件、只决定退出码）。
 */
public final class LocatorHealthGate {

    /** 通过。 */
    public static final int EXIT_OK = 0;
    /** 存在回归（新增或恶化）。 */
    public static final int EXIT_REJECTED = 1;
    /** 参数 / IO / JSON 错误。 */
    public static final int EXIT_INPUT_ERROR = 2;

    /**
     * 默认基线路径（相对工作目录，即工程根）。
     *
     * <p>放工程根而非 {@code target/}：基线必须<b>跨构建持久</b>，而 CI 每次全新检出 + clean。
     * 业务第一次跑会自动生成该文件并打 WARN 提示提交；提交后门禁才真正生效。
     */
    public static final String DEFAULT_BASELINE = "locator-health-baseline.json";

    /** 基线路径覆盖（<b>可选</b>：不配就用 {@link #DEFAULT_BASELINE}）。 */
    public static final String BASELINE_PROPERTY = "pw.locator.health.baseline";

    /** 门禁开关（<b>可选</b>：默认开启；{@code -Dpw.locator.health.gate.enabled=false} 关闭）。 */
    public static final String ENABLED_PROPERTY = "pw.locator.health.gate.enabled";

    /**
     * 自动门禁的回归容忍条数（<b>可选</b>：默认 {@code 0}＝零容忍）。
     *
     * <p>非法值（非整数）与负数一律回落 {@code 0} 并<b>不放宽</b>——与框架其它数值配置同一纪律：
     * 配置错误不允许静默削弱质量门禁。CLI 的 {@code --tolerance} 优先级更高（显式参数覆盖属性）。
     */
    public static final String TOLERANCE_PROPERTY = "pw.locator.health.gate.tolerance";

    private static final String BASELINE_VERSION = "1";
    private static final String FLAG_CURRENT = "--current";
    private static final String FLAG_BASELINE = "--baseline";
    private static final String FLAG_TOLERANCE = "--tolerance";
    private static final String FLAG_WRITE_BASELINE = "--write-baseline";
    private static final String FLAG_ALLOW_MISSING = "--allow-missing";
    private static final String TAG = "[locator-health-gate]";

    private LocatorHealthGate() {
    }

    /** 单个选择器的违例事实（基线 / 本轮共用同一模型）。 */
    public record Entry(String selector, String kind, long violations, int maxMatchCount) {
    }

    /**
     * 一条回归。
     *
     * @param change {@code NEW}（基线无）或 {@code WORSENED}（数值变差）
     * @param baseline 基线事实；{@code NEW} 时为 {@code null}
     */
    public record Regression(String selector, String kind, String change,
                             Entry baseline, Entry current) {
    }

    /** 比对结果（不可变）。 */
    public record GateResult(List<Regression> regressions, List<Entry> fixed, List<Entry> improved) {

        public GateResult {
            regressions = regressions == null ? List.of() : List.copyOf(regressions);
            fixed = fixed == null ? List.of() : List.copyOf(fixed);
            improved = improved == null ? List.of() : List.copyOf(improved);
        }

        /** 回归条数是否在容忍范围内。 */
        public boolean passed(int tolerance) {
            return regressions.size() <= Math.max(0, tolerance);
        }

        /** 一行结论（CI 日志用）。 */
        public String summary(int tolerance) {
            // ASCII only：CI 控制台多为非 UTF-8，箭头等符号会显示为乱码
            return String.format("regressions=%d, improved=%d, fixed=%d, tolerance=%d -> %s",
                    regressions.size(), improved.size(), fixed.size(), Math.max(0, tolerance),
                    passed(tolerance) ? "PASS" : "FAIL");
        }
    }

    // ------------------------------------------------------------------ 核心

    /**
     * 比对本轮结果与基线（纯读取，不修改任何文件）。
     *
     * @param currentFile  本轮 {@code locator-health.json}
     * @param baselineFile 基线文件；<b>不存在则抛 {@link IOException}</b>（fail-closed）
     * @param tolerance    允许的回归条数（&ge;0）
     * @throws IOException 文件缺失 / JSON 非法 / 结构不符
     */
    public static GateResult compare(Path currentFile, Path baselineFile, int tolerance) throws IOException {
        Map<String, Entry> current = withViolations(currentFile);
        if (!Files.exists(baselineFile)) {
            throw new IOException("baseline not found: " + baselineFile
                    + " (create it once with " + FLAG_WRITE_BASELINE + ")");
        }
        Map<String, Entry> baseline = withViolations(baselineFile);
        return compareEntries(current, baseline);
    }

    /**
     * 纯内存比对（CLI 与自动门禁共用，保证两条路径判定完全一致）。
     *
     * @param current  本轮"有违例"的选择器
     * @param baseline 基线"有违例"的选择器
     */
    static GateResult compareEntries(Map<String, Entry> current, Map<String, Entry> baseline) {
        List<Regression> regressions = new ArrayList<>();
        List<Entry> improved = new ArrayList<>();
        List<Entry> fixed = new ArrayList<>();

        for (Entry cur : current.values()) {
            Entry base = baseline.get(cur.selector());
            if (base == null) {
                regressions.add(new Regression(cur.selector(), cur.kind(), "NEW", null, cur));
            } else if (cur.violations() > base.violations() || cur.maxMatchCount() > base.maxMatchCount()) {
                regressions.add(new Regression(cur.selector(), cur.kind(), "WORSENED", base, cur));
            } else if (cur.violations() < base.violations() || cur.maxMatchCount() < base.maxMatchCount()) {
                improved.add(cur);
            }
        }
        for (Entry base : baseline.values()) {
            if (!current.containsKey(base.selector())) {
                fixed.add(base);
            }
        }
        return new GateResult(regressions, fixed, improved);
    }

    /**
     * 用本轮结果重建基线（覆盖写）。
     *
     * <p>调用方应<b>人工确认</b>这些违例可接受后再执行，否则等于把问题固化成"正常"。
     *
     * @return 实际写入的基线路径
     */
    public static Path writeBaseline(Path currentFile, Path baselineFile) throws IOException {
        return writeEntries(withViolations(currentFile), baselineFile);
    }

    private static Path writeEntries(Map<String, Entry> entries, Path baselineFile) throws IOException {
        JsonArray selectors = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject node = new JsonObject();
            node.addProperty("selector", e.selector());
            node.addProperty("kind", e.kind());
            node.addProperty("violations", e.violations());
            node.addProperty("maxMatchCount", e.maxMatchCount());
            selectors.add(node);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", BASELINE_VERSION);
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("selectorsTotal", entries.size());
        root.add("selectors", selectors);

        Path parent = baselineFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(baselineFile, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(root), StandardCharsets.UTF_8);
        return baselineFile;
    }

    // ------------------------------------------------------------------ 自动门禁（业务零配置）

    /** 自动门禁结论。 */
    public record AutoResult(Status status, List<Regression> regressions, String message) {

        /** 门禁状态。 */
        public enum Status {
            /** 无回归。 */
            PASS,
            /** 基线缺失（首次采用），本轮不判失败，基线将在本次运行收尾按真实数据生成。 */
            SEEDED,
            /** 存在回归，应让构建失败。 */
            FAILED,
            /** 门禁关闭 / 基线不可读，跳过。 */
            SKIPPED
        }

        public AutoResult {
            regressions = regressions == null ? List.of() : List.copyOf(regressions);
        }

        public boolean failed() {
            return status == Status.FAILED;
        }
    }

    /** 门禁是否启用（默认启用；{@code -Dpw.locator.health.gate.enabled=false} 关闭）。 */
    public static boolean enabled() {
        String raw = System.getProperty(ENABLED_PROPERTY);
        return raw == null || !"false".equalsIgnoreCase(raw.trim());
    }

    /** 实际使用的基线路径（{@link #BASELINE_PROPERTY} 可覆盖，默认 {@link #DEFAULT_BASELINE}）。 */
    public static Path resolveBaseline() {
        String configured = System.getProperty(BASELINE_PROPERTY);
        return Paths.get(configured == null || configured.isBlank() ? DEFAULT_BASELINE : configured.trim());
    }

    /**
     * 自动门禁的容忍条数（{@link #TOLERANCE_PROPERTY}，默认 {@code 0}）。
     *
     * <p>非整数 / 负数一律回落 {@code 0}：配置错误不允许静默放宽质量门禁。
     */
    public static int resolveTolerance() {
        String raw = System.getProperty(TOLERANCE_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 本轮是否采集到任何选择器数据（用于区分"没有数据"与"数据干净"）。 */
    public static boolean hasCollectedData() {
        return !LocatorHealthRecorder.snapshot().isEmpty();
    }

    /**
     * 自动门禁（场景边界调用）：与基线比对，返回是否应判失败。
     *
     * <p>基线缺失时返回 {@link AutoResult.Status#SEEDED} 并<b>不失败</b>——否则首次接入就红，
     * 业务会直接把门禁关掉。基线由宿主在本次运行收尾调 {@link #seedIfMissing()} 生成，提示提交后即生效。
     */
    public static AutoResult autoCheck() {
        if (!enabled()) {
            return new AutoResult(AutoResult.Status.SKIPPED, List.of(), "gate disabled by "
                    + ENABLED_PROPERTY);
        }
        int tolerance = resolveTolerance();
        Map<String, Entry> current = currentEntries();
        Path baseline = resolveBaseline();
        if (!Files.exists(baseline)) {
            return new AutoResult(AutoResult.Status.SEEDED, List.of(), "baseline not found (" + baseline
                    + "); it will be generated when this run finishes - commit it to enable regression checks");
        }
        Map<String, Entry> base;
        try {
            base = withViolations(baseline);
        } catch (IOException | RuntimeException e) {
            //  基线损坏不应把构建判红（且难以自愈）；跳过 + 提示，保持"零配置不误伤"
            return new AutoResult(AutoResult.Status.SKIPPED, List.of(),
                    "baseline unreadable (" + e.getMessage() + ") - check skipped");
        }
        GateResult result = compareEntries(current, base);
        if (result.passed(tolerance)) {
            return new AutoResult(AutoResult.Status.PASS, List.of(), result.summary(tolerance));
        }
        return new AutoResult(AutoResult.Status.FAILED, result.regressions(),
                describe(result.regressions()) + " [tolerance=" + tolerance + "]");
    }

    /**
     * 基线不存在时按<b>当前采集数据</b>生成（运行收尾调用；已存在或门禁关闭则不动）。
     *
     * <p>调用前置：{@link #hasCollectedData()} 为真。没有任何采集数据时写出的空基线会
     * "假装已武装"——下一轮只要有违例就全判 NEW，属于自伤，故由宿主先判空。
     *
     * @return 基线路径（未被改动时返回既有路径）
     */
    public static Path seedIfMissing() throws IOException {
        Path baseline = resolveBaseline();
        if (Files.exists(baseline) || !enabled()) {
            return baseline;
        }
        return writeEntries(currentEntries(), baseline);
    }

    /** 本轮"有违例"的选择器（内存快照，不读文件）。 */
    static Map<String, Entry> currentEntries() {
        Map<String, Entry> out = new TreeMap<>();
        for (LocatorHealthRecorder.SelectorHealth health : LocatorHealthRecorder.snapshot()) {
            if (health.violations() <= 0) {
                continue;
            }
            out.put(health.selector(), new Entry(health.selector(), health.kind(),
                    health.violations(), health.maxMatchCount()));
        }
        return out;
    }

    /** 回归的可读描述（作为 JUnit 失败信息，直接进 surefire/failsafe 报告）。 */
    static String describe(List<Regression> regressions) {
        StringBuilder sb = new StringBuilder("Locator health regression(s): ");
        for (int i = 0; i < regressions.size(); i++) {
            Regression r = regressions.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            if (r.baseline() == null) {
                sb.append("NEW `").append(r.selector()).append("` (").append(r.kind())
                        .append(") resolved to ").append(r.current().maxMatchCount()).append(" elements");
            } else {
                sb.append("WORSENED `").append(r.selector()).append("` violations ")
                        .append(r.baseline().violations()).append(" -> ").append(r.current().violations());
            }
        }
        return sb.append(" - see locator-health.html for the full evidence").toString();
    }

    // ------------------------------------------------------------------ CLI

    /** CLI 入口（供 CI 直接调用）。 */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * 可测试的 CLI 主体（不调用 {@code System.exit}）。
     *
     * @return 退出码：{@value #EXIT_OK} / {@value #EXIT_REJECTED} / {@value #EXIT_INPUT_ERROR}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(TAG + " " + e.getMessage());
            err.println(TAG + " usage: --current <json> [--baseline <json>] [--tolerance N]"
                    + " [" + FLAG_WRITE_BASELINE + "] [" + FLAG_ALLOW_MISSING + "]");
            return EXIT_INPUT_ERROR;
        }

        try {
            if (!options.writeBaseline && options.allowMissing && !Files.exists(options.current)) {
                //  本轮没有采集产物（无元素操作 / 采集被关闭）：显式跳过而不是误报回归
                out.println(TAG + " SKIP: no locator-health file at " + options.current);
                return EXIT_OK;
            }
            if (options.writeBaseline) {
                Path written = writeBaseline(options.current, options.baseline);
                out.println(TAG + " baseline written: " + written
                        + " (" + withViolations(written).size() + " selectors)");
                return EXIT_OK;
            }
            GateResult result = compare(options.current, options.baseline, options.tolerance);
            printReport(options, result, out);
            return result.passed(options.tolerance) ? EXIT_OK : EXIT_REJECTED;
        } catch (IOException | RuntimeException e) {
            err.println(TAG + " ERROR: " + e.getMessage());
            return EXIT_INPUT_ERROR;
        }
    }

    private static void printReport(Options options, GateResult result, PrintStream out) {
        out.println(TAG + " current  = " + options.current);
        out.println(TAG + " baseline = " + options.baseline);
        for (Regression r : result.regressions()) {
            if (r.baseline() == null) {
                out.printf("[NEW]      %s (%s) violations=%d, maxMatches=%d%n",
                        r.selector(), r.kind(), r.current().violations(), r.current().maxMatchCount());
            } else {
                out.printf("[WORSENED] %s (%s) violations=%d -> %d, maxMatches=%d -> %d%n",
                        r.selector(), r.kind(),
                        r.baseline().violations(), r.current().violations(),
                        r.baseline().maxMatchCount(), r.current().maxMatchCount());
            }
        }
        for (Entry e : result.improved()) {
            out.printf("[IMPROVED] %s (%s) violations=%d (better than baseline)%n",
                    e.selector(), e.kind(), e.violations());
        }
        for (Entry e : result.fixed()) {
            out.printf("[FIXED]    %s (%s) no longer violates (baseline violations=%d)"
                    + " — refresh the baseline to lock it in%n", e.selector(), e.kind(), e.violations());
        }
        out.println(TAG + " " + result.summary(options.tolerance));
    }

    // ------------------------------------------------------------------ 内部

    private static Map<String, Entry> withViolations(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("locator-health file not found: " + file);
        }
        JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IOException("unexpected JSON (expected an object): " + file);
        }
        JsonElement selectors = parsed.getAsJsonObject().get("selectors");
        Map<String, Entry> out = new TreeMap<>();
        if (selectors == null || !selectors.isJsonArray()) {
            return out;
        }
        for (JsonElement element : selectors.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            String selector = text(node, "selector");
            long violations = number(node, "violations");
            if (selector.isBlank() || violations <= 0) {
                continue; // 只关心"有违例"的选择器：用量类噪音不进基线
            }
            out.put(selector, new Entry(selector, text(node, "kind"), violations,
                    (int) number(node, "maxMatchCount")));
        }
        return out;
    }

    private static String text(JsonObject node, String key) {
        JsonElement value = node.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject node, String key) {
        JsonElement value = node.get(key);
        if (value == null || value.isJsonNull()) {
            return 0L;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            return 0L; // 容忍人工编辑过的基线里出现非数字
        }
    }

    /** CLI 参数。 */
    private static final class Options {
        private Path current = Paths.get(LocatorHealthReportWriter.DEFAULT_OUTPUT);
        private Path baseline = Paths.get(DEFAULT_BASELINE);
        private int tolerance;
        private boolean writeBaseline;
        private boolean allowMissing;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String inline = null;
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    inline = arg.substring(eq + 1);
                    arg = arg.substring(0, eq);
                }
                switch (arg) {
                    case FLAG_CURRENT -> options.current = Paths.get(value(FLAG_CURRENT, inline, args, ++i));
                    case FLAG_BASELINE -> options.baseline = Paths.get(value(FLAG_BASELINE, inline, args, ++i));
                    case FLAG_TOLERANCE -> options.tolerance = intValue(value(FLAG_TOLERANCE, inline, args, ++i));
                    case FLAG_WRITE_BASELINE -> options.writeBaseline = true;
                    case FLAG_ALLOW_MISSING -> options.allowMissing = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + arg);
                }
                if (inline != null) {
                    i--; // 值已内联，未消耗下一个 token
                }
            }
            return options;
        }

        private static String value(String flag, String inline, String[] args, int index) {
            if (inline != null) {
                return inline;
            }
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            return args[index];
        }

        private static int intValue(String raw) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed < 0) {
                    throw new IllegalArgumentException("tolerance must be >= 0");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("tolerance must be an integer: " + raw);
            }
        }
    }
}

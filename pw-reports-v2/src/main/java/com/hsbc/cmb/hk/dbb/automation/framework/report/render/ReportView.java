package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新报告设计（对齐 docs/template-design）的视图辅助：结果键/文案、百分比、秒、运行环境等。
 *
 * <p><b>为什么要 key 这一层</b>：模板的徽章类名与文案以 {@code PASS} 为准，而本库枚举是
 * {@link TestResult#SUCCESS}；二者是同一语义的两种命名。所有对外（模板/JSON）统一经
 * {@link #key(TestResult)} 归一为 {@code PASS/FAILURE/ERROR/PENDING/SKIPPED/IGNORED/UNDEFINED}，
 * 避免模板里散落 {@code SUCCESS→PASS} 的特判。</p>
 *
 * <p>格式化一律钉 {@link Locale#ROOT}，避免不同机器 locale 导致报告数字漂移（与 golden 基线同理）。</p>
 */
public final class ReportView {

    /** Maven 发行目录名里的版本号（如 {@code D:\Software\apache-maven-3.9.9}）。 */
    private static final Pattern MAVEN_HOME_VERSION = Pattern.compile("apache-maven-(\\d+(?:\\.\\d+)+)");

    private ReportView() {
    }

    /** 结果键：{@code SUCCESS} 归一为模板约定的 {@code PASS}；null 视为 UNDEFINED。 */
    public static String key(TestResult r) {
        if (r == null) {
            return "UNDEFINED";
        }
        return r == TestResult.SUCCESS ? "PASS" : r.name();
    }

    /** 结果文案（英文，与“输出使用英文”一致）。 */
    public static String label(String key) {
        if (key == null) {
            return "Unknown";
        }
        switch (key) {
            case "PASS":
                return "Passed";
            case "FAILURE":
                return "Failed";
            case "ERROR":
                return "Error";
            case "PENDING":
                return "Pending";
            case "SKIPPED":
                return "Skipped";
            case "IGNORED":
                return "Ignored";
            case "UNDEFINED":
                return "Undefined";
            default:
                return key;
        }
    }

    /** 百分比（0–100，保留 1 位小数）；空跑记 0。 */
    public static double pct(int part, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(part * 1000.0 / total) / 10.0;
    }

    /** 毫秒 → 秒字符串（一位小数）；0/负返回空串（模板渲染为 —）。 */
    public static String durationSec(long ms) {
        if (ms <= 0) {
            return "";
        }
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }

    /** 按结果键计数。 */
    public static int count(List<TestOutcome> outcomes, String key) {
        int n = 0;
        for (TestOutcome o : outcomes) {
            if (key.equals(key(o.getResult()))) {
                n++;
            }
        }
        return n;
    }

    /**
     * 环形图分段（只保留 value&gt;0 的段，与模板 JS 行为一致：空段不画）。
     *
     * @param entries 顺序即绘制顺序（顺时针，12 点起）
     */
    public static List<Map<String, Object>> segments(Object... keyColorValue) {
        List<Map<String, Object>> segs = new ArrayList<>();
        for (int i = 0; i + 2 < keyColorValue.length; i += 3) {
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("key", String.valueOf(keyColorValue[i]));
            seg.put("color", String.valueOf(keyColorValue[i + 1]));
            seg.put("value", ((Number) keyColorValue[i + 2]).intValue());
            segs.add(seg);
        }
        return segs;
    }

    /**
     * 耗时分布直方图（横轴=执行时间分桶，纵轴=用例数）。
     *
     * <p>分桶固定（<b>分钟</b>为单位）：&lt;1m / 1-2m / 2-5m / 5-10m / 10-30m / &gt;30m。
     * 每桶给出 count 与 pct（相对最高桶的百分比，用于 CSS 柱高），避免前端再做换算。</p>
     */
    public static List<Map<String, Object>> durationBuckets(List<TestOutcome> outcomes) {
        String[] labels = {"<1m", "1-2m", "2-5m", "5-10m", "10-30m", ">30m"};
        long[] upperBounds = {
                60_000L,        // 1 分钟
                120_000L,       // 2 分钟
                300_000L,       // 5 分钟
                600_000L,       // 10 分钟
                1_800_000L,     // 30 分钟
                Long.MAX_VALUE};
        int[] counts = new int[labels.length];
        for (TestOutcome o : outcomes) {
            long ms = o.getDuration();
            for (int i = 0; i < upperBounds.length; i++) {
                if (ms < upperBounds[i]) {
                    counts[i]++;
                    break;
                }
            }
        }
        int max = 0;
        for (int c : counts) {
            max = Math.max(max, c);
        }
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("label", labels[i]);
            b.put("count", counts[i]);
            b.put("pct", max == 0 ? 0 : Math.round(counts[i] * 1000.0 / max) / 10.0);
            buckets.add(b);
        }
        return buckets;
    }

    /** Feature 页文件名（与用例页 <outcomeId>.html 同目录）。 */
    public static String featureSlug(String feature) {
        String base = feature == null ? "unknown" : feature;
        String slug = base.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return "feature-" + (slug.isEmpty() ? "unknown" : slug);
    }

    /**
     * 运行环境（取自 JVM 系统属性与框架配置）。
     *
     * <p>空值一律不进入结果集（2026-10-18 用户反馈：Environment 出现一排 “—” 很难看），
     * 模板只渲染有值行，不再需要占位符。浏览器分布需按用例统计，当前模型未记录每用例浏览器，
     * 故仅在显式配置时给出，否则空列表。</p>
     */
    public static List<Map<String, Object>> environment() {
        List<Map<String, Object>> rows = new ArrayList<>();
        add(rows, "JDK", join(System.getProperty("java.version"), System.getProperty("java.vm.name")));
        add(rows, "Build tool", buildTool());
        add(rows, "Test framework", System.getProperty("pw.report.test.framework", "Cucumber · JUnit"));
        add(rows, "Browser driver", playwrightDriver());
        add(rows, "OS", join(System.getProperty("os.name"), System.getProperty("os.version")));
        add(rows, "Target", firstNonBlank(System.getProperty("pw.report.target"),
                System.getProperty("base.url"), System.getenv("BASE_URL")));
        rows.removeIf(row -> row.get("v") == null);
        return rows;
    }

    /** 浏览器分布；无数据时返回空列表（模板据此隐藏分布条）。 */
    public static List<Map<String, Object>> browsers() {
        List<Map<String, Object>> out = new ArrayList<>();
        String configured = System.getProperty("pw.report.browsers");
        if (configured != null && !configured.isBlank()) {
            for (String part : configured.split(",")) {
                String[] kv = part.split(":", 2);
                if (kv[0].isBlank()) {
                    continue;
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("name", kv[0].trim());
                b.put("version", kv.length > 1 ? kv[1].trim() : "");
                out.add(b);
            }
        }
        return out;
    }

    /**
     * 构建工具：优先 {@code maven.version} / {@code maven.build.version} 系统属性；
     * Surefire fork 通常不传，退而解析 {@code maven.home} 目录名（如 {@code apache-maven-3.9.9}）。
     * 两者都取不到则返回 {@code null}（环境面板不展示该行）。
     */
    private static String buildTool() {
        String v = firstNonBlank(System.getProperty("maven.version"),
                System.getProperty("maven.build.version"));
        if (v != null) {
            return v.startsWith("Maven") ? v : "Maven " + v;
        }
        String home = firstNonBlank(System.getProperty("maven.home"),
                System.getenv("MAVEN_HOME"), System.getenv("M2_HOME"));
        if (home == null) {
            return null;
        }
        Matcher m = MAVEN_HOME_VERSION.matcher(home);
        return m.find() ? "Maven " + m.group(1) : "Maven";
    }

    /**
     * 浏览器驱动：{@code pw.report.browser.driver} / {@code playwright.version} 系统属性优先；
     * 否则反射探测 Playwright JAR 的 {@code Implementation-Version}（本模块不依赖 Playwright，
     * 探测失败视为未知——只是不展示该行，绝不影响报告生成）。
     */
    private static String playwrightDriver() {
        String v = firstNonBlank(System.getProperty("pw.report.browser.driver"),
                System.getProperty("playwright.version"));
        if (v == null) {
            v = probePlaywrightVersion();
        }
        if (v == null) {
            return null;
        }
        return v.startsWith("Playwright") ? v : "Playwright " + v;
    }

    private static String probePlaywrightVersion() {
        try {
            Class<?> type = Class.forName("com.microsoft.playwright.Playwright");
            Package pkg = type.getPackage();
            String v = pkg == null ? null : pkg.getImplementationVersion();
            return v == null || v.isBlank() ? null : v;
        } catch (Throwable ignored) {
            return null; // 可选集成探测：缺类/链接错误一律视为未知
        }
    }

    private static void add(List<Map<String, Object>> rows, String k, String v) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("k", k);
        row.put("v", v == null || v.isBlank() ? null : v);
        rows.add(row);
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        return b == null || b.isBlank() ? a : a + " (" + b + ")";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}

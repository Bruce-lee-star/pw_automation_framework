package com.hsbc.cmb.hk.dbb.automation.framework.report.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * pw-reports 配置——{@code pw.report.*} 属性，来源：系统属性 &gt; 环境变量 &gt; 默认值。
 *
 * <p>环境变量名 = 属性名大写、点换下划线（{@code pw.report.output} → {@code PW_REPORT_OUTPUT}）。
 *
 * <h2>配置面设计：只有 4 项需要决策</h2>
 * <p>本类刻意把配置面压到最小——<b>开关与参数不分离</b>（没有 {@code x.enabled} + {@code x.url}
 * 这种二连），<b>同族旋钮合并成档位</b>（三个 {@code max-*} 收为一个 {@code limits}）。
 * 零配置即可跑，剩下 4 项各管一件事：</p>
 *
 * <table border="1">
 *   <caption>配置项（4 项）</caption>
 *   <tr><th>属性</th><th>默认</th><th>作用</th></tr>
 *   <tr><td>{@code pw.report.enabled}</td><td>{@code true}</td><td>kill-switch：false 时整体静默</td></tr>
 *   <tr><td>{@code pw.report.output}</td><td>{@code target/reports}</td><td>产物目录</td></tr>
 *   <tr><td>{@code pw.report.screenshot}</td><td>{@code FOR_FAILURES}</td><td>截图策略（见 {@link ScreenshotMode}）</td></tr>
 *   <tr><td>{@code pw.report.push.url}</td><td>空</td><td>非空即启用推送（不再需要单独的 enabled）</td></tr>
 * </table>
 *
 * <p>其余三项<b>通常不用动</b>，只在特殊场景覆盖：{@code pw.report.scrub.extra-patterns}
 * （补脱敏规则）、{@code pw.report.limits}（限额档位）、{@code jenkins.publish.html.report.name}
 * （Jenkins publishHTML 的 reportName，默认 {@code Test Report}；与页面标题 {@code project.report.name} 独立）。</p>
 *
 * <p>历史兼容：旧的 {@code push.enabled} / {@code history.enabled} / {@code screenshot.mode}
 * / {@code max-evidence-lines} 等平铺旋钮已收敛为上述形态——旧键被<b>忽略</b>而非报错
 * （不认识的键静默是配置系统的常态，避免升级即崩），需要迁移的见 README。</p>
 */
public final class PwReportsConfig {

    private static final Logger log = LoggerFactory.getLogger(PwReportsConfig.class);

    private static final String PREFIX = "pw.report.";

    /**
     * 产物体量限额：三合一档位（原先是 {@code max-evidence-lines} / {@code max-evidence-bytes} /
     * {@code max-api-operations} 三个独立旋钮）。这三个值同属「防单例报告爆量」这一个目的，
     * 放三个旋钮只会诱导逐项微调；实践中它们总是同向调整，故收为一个档位。
     *
     * @param evidenceLines 单步骤证据最大行数
     * @param evidenceBytes 单行证据最大字节（超长截断）
     * @param apiOperations 单用例 API 操作记录最大条数
     */
    public record Limits(int evidenceLines, int evidenceBytes, int apiOperations) {

        public static final Limits DEFAULT = new Limits(50, 2048, 500);

        public Limits {
            if (evidenceLines <= 0 || evidenceBytes <= 0 || apiOperations <= 0) {
                throw new IllegalArgumentException("limits must be positive: " + evidenceLines
                        + "/" + evidenceBytes + "/" + apiOperations);
            }
        }

        /**
         * 解析 {@code lines,bytes,apis}（逗号分隔，缺项沿用默认）。
         *
         * <p>格式故意做成<b>位置参数</b>而非 {@code key=value}：值域窄（三个正整数），
         * 写 {@code 80,4096,1000} 比 {@code evidenceLines=80,evidenceBytes=4096} 省一半字符，
         * 而非法输入（个数不对/非数字/非正）一律整体回退默认并告警——不会半途生效半个值。</p>
         */
        static Limits parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DEFAULT;
            }
            String[] parts = raw.split(",");
            if (parts.length > 3) {
                log.warn("pw.report.limits: expected up to 3 values 'lines,bytes,apis' but got {} - using default {}",
                        raw, DEFAULT);
                return DEFAULT;
            }
            int[] vals = new int[] {
                    DEFAULT.evidenceLines, DEFAULT.evidenceBytes, DEFAULT.apiOperations };
            for (int i = 0; i < parts.length; i++) {
                String t = parts[i].trim();
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    vals[i] = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    log.warn("pw.report.limits: invalid number '{}' - using default {}", raw, DEFAULT);
                    return DEFAULT;
                }
            }
            try {
                return new Limits(vals[0], vals[1], vals[2]);
            } catch (IllegalArgumentException e) {
                log.warn("pw.report.limits: {} (must be positive) - using default {}", raw, DEFAULT);
                return DEFAULT;
            }
        }
    }

    private final boolean enabled;

    private final Path outputDir;

    private final ScreenshotMode screenshotMode;

    private final List<Pattern> extraScrubPatterns;

    private final Limits limits;

    private final String pushUrl;

    private PwReportsConfig(Builder b) {
        this.enabled = b.enabled;
        this.outputDir = b.outputDir;
        this.screenshotMode = b.screenshotMode;
        this.extraScrubPatterns = List.copyOf(b.extraScrubPatterns);
        this.limits = b.limits;
        this.pushUrl = b.pushUrl;
    }

    /** 从系统属性 / 环境变量加载。 */
    public static PwReportsConfig load() {
        Builder b = new Builder();
        b.enabled = bool("enabled", true);
        b.outputDir = Paths.get(resolveOutputDir());
        b.screenshotMode = ScreenshotMode.parse(str("screenshot", null),
                msg -> log.warn("pw.report.screenshot: {}", msg));
        b.extraScrubPatterns = parsePatterns(str("scrub.extra-patterns", null));
        b.limits = Limits.parse(str("limits", null));
        b.pushUrl = str("push.url", null);
        return new PwReportsConfig(b);
    }

    /** 测试/框架内嵌构造入口。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 产物目录只由报告模块专用键 {@code pw.report.output}（系统属性 / 环境变量 {@code PW_REPORT_OUTPUT}）
     * 决定，缺省为 {@code target/reports}。
     *
     * <p>旧的通用键 {@code framework.output.directory} 兼容回落已移除：报告目录归属报告模块单一配置，
     * 不再跨模块回落，避免两处配置含义重叠、优先级难辨。</p>
     */
    private static String resolveOutputDir() {
        String out = str("output", null);
        return (out != null && !out.isBlank()) ? out.trim() : "target/reports";
    }

    // ---------------------------------------------------------------- getters

    public boolean isEnabled() {
        return enabled;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public ScreenshotMode getScreenshotMode() {
        return screenshotMode;
    }

    public List<Pattern> getExtraScrubPatterns() {
        return extraScrubPatterns;
    }

    public Limits getLimits() {
        return limits;
    }

    public int getMaxEvidenceLines() {
        return limits.evidenceLines();
    }

    public int getMaxEvidenceLineBytes() {
        return limits.evidenceBytes();
    }

    public int getMaxApiOperations() {
        return limits.apiOperations();
    }

    /** 推送目标；<b>非空即启用</b>（原 {@code push.enabled} 冗余开关已移除）。 */
    public String getPushUrl() {
        return pushUrl;
    }

    public boolean isPushEnabled() {
        return pushUrl != null && !pushUrl.isBlank();
    }

    // ---------------------------------------------------------------- hosted base url

    /**
     * 报告托管基准 URL：CI（Jenkins）环境<b>自动</b>由构建信息推导，本地返回空串（渲染为相对链接 /
     * {@code file://} 链接），因此本地与 CI 都<b>不需要额外配置</b>。
     *
     * <p>推导规则：Jenkins 通过 {@code publishHTML}（HTML Publisher）把报告发布到
     * {@code BUILD_URL/<reportName>/}；{@code reportName} 取自配置项
     * {@code jenkins.publish.html.report.name}（环境变量 {@code JENKINS_PUBLISH_HTML_REPORT_NAME}），
     * 默认 {@code Test Report}（URL 段编码为 {@code Test%20Report}），故基准为
     * {@code https://jenkins.example/job/regression/123/Test%20Report/}。
     * 该键专指 Jenkins publishHTML 的 reportName，与页面标题 {@code project.report.name} 相互独立。</p>
     *
     * <p>非空时，渲染器会在每个 HTML 页 {@code <head>} 注入 {@code <base href>}，使页内相对链接
     * （index.html / feature-*.html / 用例页 / 截图）解析为绝对地址；值被规范化成以 {@code /} 结尾。</p>
     */
    public static String baseUrl() {
        return baseUrl(System.getenv());
    }

    /** 测试 / 嵌入式重载：注入环境变量映射（生产传 {@link System#getenv()}）。 */
    public static String baseUrl(Map<String, String> env) {
        if (env == null) {
            return "";
        }
        String buildUrl = firstNonBlank(env.get("BUILD_URL"));
        if (buildUrl == null) {
            return "";
        }
        String base = buildUrl.endsWith("/") ? buildUrl : buildUrl + "/";
        String reportName = firstNonBlank(env.get("JENKINS_PUBLISH_HTML_REPORT_NAME"),
                ConfigReader.resolve(ConfigRegistry.JENKINS_PUBLISH_HTML_REPORT_NAME.key(),
                        ConfigRegistry.JENKINS_PUBLISH_HTML_REPORT_NAME.defaultValue()));
        return base + encodeSegment(reportName) + "/";
    }

    /** URL 路径段编码：空格编码为 {@code %20}（Jenkins publishHTML 的 reportName 段）。 */
    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * Playwright Trace Viewer 基准 URL（可选，默认 {@code https://trace.playwright.dev}）。
     * 报告内 "Play trace" 链接 = {@code <base>/?trace=<path>}；自托管 Trace Viewer 时覆盖。
     *
     * <p>对应属性 {@code pw.report.trace.viewer.base.url} / 环境变量
     * {@code PW_REPORT_TRACE_VIEWER_BASE_URL}。</p>
     */
    public static String traceViewerBaseUrl() {
        return str("trace.viewer.base.url", "https://trace.playwright.dev");
    }

    /**
     * 报告展示时区（默认 JVM 时区）。
     *
     * <p>对应属性 {@code pw.report.timezone} / 环境变量 {@code PW_REPORT_TIMEZONE}，
     * 例如 {@code Asia/Shanghai}、{@code +08:00}。报告里的 Generated / Started / Ended
     * 统一按该时区渲染并带 {@code (UTC+08:00)} 后缀——CI（常为 UTC）与本机看同一份报告时
     * 口径一致，不会出现"差 8 小时"。非法值回退 JVM 默认时区并告警。</p>
     */
    public static ZoneId reportZone() {
        String raw = str("timezone", "");
        if (raw.isEmpty()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException e) {
            log.warn("invalid pw.report.timezone '{}', falling back to JVM default {}",
                    raw, ZoneId.systemDefault());
            return ZoneId.systemDefault();
        }
    }



    // ---------------------------------------------------------------- helpers

    private static String str(String key, String def) {
        String v = ConfigReader.getProperty(PREFIX + key);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return def;
    }

    private static boolean bool(String key, boolean def) {
        String v = str(key, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static List<Pattern> parsePatterns(String raw) {
        List<Pattern> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Pattern.compile(t));
            } catch (PatternSyntaxException e) {
                log.warn("pw.report.scrub.extra-patterns: invalid regex '{}' ignored", t);
            }
        }
        return out;
    }

    /** 构建器（测试用；生产走 {@link #load()}）。 */
    public static final class Builder {

        private boolean enabled = true;
        private Path outputDir = Paths.get("target/reports");
        private ScreenshotMode screenshotMode = ScreenshotMode.FOR_FAILURES;
        private List<Pattern> extraScrubPatterns = new ArrayList<>();
        private Limits limits = Limits.DEFAULT;
        private String pushUrl;

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder outputDir(Path v) {
            this.outputDir = Objects.requireNonNull(v);
            return this;
        }

        public Builder screenshotMode(ScreenshotMode v) {
            this.screenshotMode = v;
            return this;
        }

        public Builder extraScrubPatterns(List<Pattern> v) {
            this.extraScrubPatterns = new ArrayList<>(v);
            return this;
        }

        /** 三合一限额（见 {@link Limits}）。 */
        public Builder limits(Limits v) {
            this.limits = Objects.requireNonNull(v);
            return this;
        }

        /** 三合一限额的便捷重载（位置参数）。 */
        public Builder limits(int evidenceLines, int evidenceBytes, int apiOperations) {
            return limits(new Limits(evidenceLines, evidenceBytes, apiOperations));
        }

        public Builder pushUrl(String v) {
            this.pushUrl = v;
            return this;
        }

        public PwReportsConfig build() {
            return new PwReportsConfig(this);
        }
    }
}

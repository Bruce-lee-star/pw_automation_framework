package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 采集相关配置的统一解析入口（<b>内部使用</b>）。三项配置的读取优先级：
 *
 * <ol>
 *   <li><b>JVM 系统属性</b>：{@code -Droute.capture.all.type=true}（最高优先，<b>每次调用现读</b>，
 *       因此运行中/单测中可随时切换）；</li>
 *   <li><b>环境变量</b>（适合 CI/容器）：{@code ROUTE_CAPTURE_ALL_TYPE} /
 *       {@code ROUTE_CAPTURE_INLINE_MAX_BYTES} / {@code ROUTE_CAPTURE_GZIP_MAX_BYTES}；</li>
 *   <li><b>classpath 配置文件</b> {@code route-capture.properties}（放在业务工程的
 *       {@code src/test/resources} 或框架 {@code src/main/resources} 均可被读到，仅首次加载一次）；</li>
 *   <li><b>默认值</b>（见各方法）。</li>
 * </ol>
 *
 * <p>配置键与默认值：
 * <pre>
 * route.capture.all.type            = false        # 是否采集 MONITOR 之外的 DELAY / MODIFY / MOCK
 * route.capture.inline.max.bytes    = 262144       # 不超过则原字节持内存
 * route.capture.gzip.max.bytes      = 8388608      # 文本型且不超过则 gzip 持内存，否则落盘临时文件
 * </pre>
 */
final class RouteCaptureConfig {

    private static final String ALL_TYPES_KEY = "route.capture.all.type";
    private static final String INLINE_KEY = "route.capture.inline.max.bytes";
    private static final String GZIP_KEY = "route.capture.gzip.max.bytes";

    private static final String ALL_TYPES_ENV = "ROUTE_CAPTURE_ALL_TYPE";
    private static final String INLINE_ENV = "ROUTE_CAPTURE_INLINE_MAX_BYTES";
    private static final String GZIP_ENV = "ROUTE_CAPTURE_GZIP_MAX_BYTES";

    private static final String CONFIG_FILE = "route-capture.properties";

    private static final int DEFAULT_INLINE_MAX = 262_144;
    private static final int DEFAULT_GZIP_MAX = 8_388_608;

    /** classpath 配置文件（只读一次；不存在则视为未配置）。 */
    private static final Properties FILE_PROPS = loadFileProps();

    private RouteCaptureConfig() {
    }

    /** 是否采集 MONITOR 之外的三种类型（DELAY / MODIFY / MOCK）。 */
    static boolean allTypes() {
        String raw = lookup(ALL_TYPES_KEY, ALL_TYPES_ENV);
        return raw != null && Boolean.parseBoolean(raw.trim());
    }

    /** 内联阈值：不超过它就直接持原字节。 */
    static int inlineMaxBytes() {
        return positiveInt(lookup(INLINE_KEY, INLINE_ENV), DEFAULT_INLINE_MAX, INLINE_KEY);
    }

    /** 文本型 gzip 上限：不超过它就用内存 gzip（超过则落盘）。 */
    static int gzipMaxBytes() {
        return positiveInt(lookup(GZIP_KEY, GZIP_ENV), DEFAULT_GZIP_MAX, GZIP_KEY);
    }

    // ---------------------------------------------------------------- 内部工具

    /** 系统属性 → 环境变量 → 配置文件；空串视为未配置。 */
    private static String lookup(String key, String envKey) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(envKey);
        }
        if (value == null || value.isBlank()) {
            value = FILE_PROPS.getProperty(key);
        }
        return (value == null || value.isBlank()) ? null : value;
    }

    /** 解析正整数；非法值退回默认（配置错误不应让采集失败）。 */
    private static int positiveInt(String raw, int fallback, String key) {
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Properties loadFileProps() {
        Properties props = new Properties();
        try (InputStream in = RouteCaptureConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            // 配置文件读取失败等价于"未配置"，不影响任何采集语义
            props.clear();
        }
        return props;
    }
}

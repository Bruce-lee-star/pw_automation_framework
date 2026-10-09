package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigKeys;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.FrameworkFlags;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 动态路由采集配置解析（<b>内部使用</b>）。
 *
 * <p>解析优先级（高 → 低，命中即返回）：
 * <ol>
 *   <li>环境变量（{@code System.getenv}，键名经 {@link FrameworkFlags#toEnvKey} 归一）；</li>
 *   <li>properties —— 系统属性 {@code System.getProperty}（{@code -Dkey=value}）；</li>
 *   <li>配置文件属性 —— 框架配置源 SPI（{@link ConfigResolver}，web 侧即 Serenity 合并源）；</li>
 *   <li>默认值 —— {@link RouteConfig} 的 {@code DEFAULT_CAPTURE_*}（最低兜底）。</li>
 * </ol>
 *
 * <p><b>空值语义</b>：每级取到的值 {@code null} 或 {@code trim()} 后为空 ⇒ 视为「未命中」，
 * 继续下一级；命中值 {@code trim()} 后返回（沿用原 RouteCaptureConfig / FrameworkFlags 约定）。
 *
 * <p>每级现读（SPI 实现自带缓存），运行期 / 单测可切换；无共享可变状态。故<b>调用方不得把
 * 结果缓存</b>为实例快照 —— {@link RouteConfig} 已不再承载采集三项。
 *
 * @apiNote framework-internal：仅 route 采集域使用，业务代码不应直接依赖。
 * @implNote <b>本域优先级与框架既有的 {@code ConfigSource.resolve} / {@link FrameworkFlags#resolve}
 * 相反</b>（那两者均为「系统属性 → 环境变量」）。此处按采集域约定以<b>环境变量优先</b>，
 * 属有意为之的特例：请勿为「统一风格」把它改接 {@code ConfigSource}，那会静默反转优先级语义。
 */
final class RouteCaptureSettings {

    /** 配置文件属性层（框架配置源 SPI），惰性解析一次；无实现时为空列表。 */
    private static volatile List<ConfigResolver> resolvers;

    private RouteCaptureSettings() {
    }

    /** 是否采集 MONITOR 之外的 DELAY / MODIFY / MOCK。 */
    static boolean allTypes() {
        return Boolean.parseBoolean(resolve(ConfigKeys.ROUTE_CAPTURE_ALL_TYPE.key(),
                Boolean.toString(RouteConfig.DEFAULT_CAPTURE_ALL_TYPES)));
    }

    /** 响应体内联阈值（字节）。 */
    static int inlineMaxBytes() {
        return positiveInt(resolve(ConfigKeys.ROUTE_CAPTURE_INLINE_MAX_BYTES.key(),
                        Integer.toString(RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES)),
                RouteConfig.DEFAULT_CAPTURE_INLINE_MAX_BYTES);
    }

    /** 响应体文本型 gzip 上限（字节）。 */
    static int gzipMaxBytes() {
        return positiveInt(resolve(ConfigKeys.ROUTE_CAPTURE_GZIP_MAX_BYTES.key(),
                        Integer.toString(RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES)),
                RouteConfig.DEFAULT_CAPTURE_GZIP_MAX_BYTES);
    }

    /**
     * 三级回退解析：环境变量 → 系统属性 → 配置文件属性 → 默认值。
     *
     * @param key          配置键（注册表原样，如 {@code route.capture.all.type}）
     * @param defaultValue 全部未命中时的兜底值（调用方传 {@link RouteConfig} 默认值常量）
     * @return 命中值（已 trim）或 {@code defaultValue}
     */
    static String resolve(String key, String defaultValue) {
        String fromEnv = System.getenv(FrameworkFlags.toEnvKey(key));
        if (isPresent(fromEnv)) {
            return fromEnv.trim();
        }
        String fromProps = System.getProperty(key);
        if (isPresent(fromProps)) {
            return fromProps.trim();
        }
        for (ConfigResolver r : resolvers()) {
            String fromFile = r.resolve(key);
            if (isPresent(fromFile)) {
                return fromFile.trim();
            }
        }
        return defaultValue;
    }

    /** 空值语义：null 或纯空白视为未命中。 */
    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 正整数：命中但 ≤0 / 非法 ⇒ 回落该键默认值（配置错误不让采集失败）。 */
    private static int positiveInt(String raw, int fallback) {
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

    /** SPI 配置文件源（无实现 / 加载异常 ⇒ 空列表，降级为默认值）。 */
    private static List<ConfigResolver> resolvers() {
        if (resolvers == null) {
            synchronized (RouteCaptureSettings.class) {
                if (resolvers == null) {
                    List<ConfigResolver> list = new ArrayList<>();
                    try {
                        for (ConfigResolver r : ServiceLoader.load(ConfigResolver.class)) {
                            list.add(r);
                        }
                    } catch (Throwable ignored) {
                        // SPI 不可用不影响环境变量 / 系统属性两级解析
                        list.clear();
                    }
                    resolvers = list;
                }
            }
        }
        return resolvers;
    }
}

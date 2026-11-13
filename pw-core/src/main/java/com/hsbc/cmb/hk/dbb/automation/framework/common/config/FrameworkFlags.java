package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import java.util.Locale;

/**
 * 框架自有开关的<b>确定性</b>解析门面 —— D2-1 附带治理。
 *
 * <p><b>设计约束</b>
 * <ol>
 *   <li><b>键名必须与业务 / 上游配置隔离</b>：框架自有开关<b>一律加 {@link #PREFIX}</b>，避免撞车。</li>
 *   <li><b>SPI 合并源会缓存属性快照，导致开关不可预期</b>：实测
 *       {@code setProperty(k,"true")} 之后再 {@code clearProperty(k)}，
 *       {@link ConfigReader} 的 框架 SPI 源<b>仍返回旧值 "true"</b>。
 *       对"默认必须关闭"的安全 / 诊断开关，这种非确定性不可接受。
 *       ⇒ 本门面<b>只认系统属性与环境变量</b>，不经过 SPI，行为确定。</li>
 * </ol>
 *
 * <p><b>适用范围</b>：框架自身的开关位（诊断 / 安全 / 基础设施）。
 * 业务配置（如 {@code environment}、数据源地址等仍可能写在 {@code pw.conf} 里）
 * 请继续用 {@link ConfigReader} —— 那里需要 SPI 合并源的能力。
 *
 * <p>优先级：系统属性 → 环境变量 → 默认值（与 {@link ConfigReader} 前两级一致）。
 *
 * <p><b>2026-11-12 递归缺陷修复（务必勿改回）</b>：本类<b>绝不</b>调用
 * {@link ConfigReader#getProperty(String)}。原因是日志格式化链路会经过本类：
 * {@code logback %msg → SanitizingMessageConverter → FrameworkFlags.isEnabled → ConfigReader}
 * 的第三层是 SPI 文件配置源，而 {@code FileConfigResolver} 在加载时会打 INFO 日志，
 * 该日志又回到 {@code %msg} —— 形成无限递归并抛 {@code StackOverflowError}
 * （线上表现为 Cucumber Runner 的 {@code initializationError}，一个 scenario 都跑不到）。
 * 框架自有开关按本类契约本就只认系统属性与环境变量，直读即可，<b>不需要也不允许</b>经过文件层。
 * 同理，本类不参与 {@code ENC(...)} 透明解密：开关是布尔字面量，密文无意义，且解密路径
 * （{@code SecretValue}）同样会经 {@code ConfigReader}，会把环重新接上。
 */
public final class FrameworkFlags {

    /** 框架自有开关的统一键前缀。 */
    public static final String PREFIX = "framework.";

    private FrameworkFlags() {
        // 纯静态门面，禁止实例化
    }

    /**
     * 解析布尔开关。
     *
     * @param key          开关键（建议以 {@link #PREFIX} 开头）
     * @param defaultValue 未配置时的默认值
     */
    public static boolean isEnabled(String key, boolean defaultValue) {
        String v = resolve(key, null);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    /**
     * 解析字符串开关。
     *
     * @param key          开关键（建议以 {@link #PREFIX} 开头）
     * @param defaultValue 未配置时的默认值
     */
    public static String resolve(String key, String defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        // 直读 JVM 启动态：无 SPI、无文件 IO、无解密、无日志。见类注释的递归缺陷说明。
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(toEnvKey(key));
        }
        if (v != null && !v.trim().isEmpty()) {
            return v.trim();
        }
        return defaultValue;
    }

    /** 配置键 → 环境变量名：大写，点/连字符转下划线（与 {@link ConfigReader#toEnvKey} 同源）。 */
    public static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}

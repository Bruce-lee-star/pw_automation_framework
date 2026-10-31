package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigResolver;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 框架自有「读配置文件」的 {@link ConfigResolver} SPI 实现（DEV-V5 / M1a）。
 *
 * <p><b>为什么需要它</b>：去 框架（CORE-P0-1）时，原「框架 合并源」（读
 * {@code serenity.conf} + {@code serenity.properties} + private）被换成只读系统属性 / 环境变量的
 * {@link FrameworkConfigResolver}，<b>配置文件这一来源随之丢失</b>——导致配置值虽仍在
 * {@code serenity.conf}/{@code serenity.properties} 里，框架 {@code ConfigReader} 却读不到
 * （曾表现为迁 {@code BDDUtils} 时从「类缺失」变成「配置读不到」）。本实现恢复该来源，且完全
 * 框架自有、不依赖 框架。</p>
 *
 * <p><b>读取来源与优先级</b>（classpath 资源；下方数组<b>从低到高</b>，后读覆盖先读）：
 * <ol>
 *   <li>{@code serenity.properties} —— legacy 兼容（扁平 key=value）</li>
 *   <li>{@code serenity.conf}      —— legacy 兼容（HOCON）</li>
 *   <li>{@code pw.properties}      —— 框架原生（预留，扁平 key=value）</li>
 *   <li>{@code pw.conf}            —— 框架原生（预留，HOCON）</li>
 * </ol>
 * 更高的系统属性 / 环境变量覆盖由 {@code ConfigReader} 的 {@code SYSTEM_PROPERTY} /
 * {@code ENVIRONMENT} 层保证，与本 resolver 无关；本 resolver 只填补 {@code CONFIG_FILE} 层。</p>
 *
 * <p><b>HOCON 语义对齐 thucydides</b>（同一 Typesafe Config 解析器）：嵌套对象与点分叶子键
 * （如 {@code token.security.url}）统一递归扁平化为点分键，例如
 * {@code userinfo_O63 { WP7UAT2_2 { token.security.url: "..." } }} →
 * {@code userinfo_O63.WP7UAT2_2.token.security.url}。缺文件 / 解析失败均<b>静默跳过该来源</b>，
 * 返回 {@code null} 由上层降级，绝不影响其它来源与内置层。</p>
 *
 * <p>解密不在本层：值经 {@code ConfigReader} 统一做 {@code ENC(...)} 透明解密，本 resolver 只原样返回原始串。</p>
 */
public final class FileConfigResolver implements ConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(FileConfigResolver.class);

    /** 读取来源（classpath 资源名），<b>从低到高</b>排列，后读覆盖先读。 */
    private static final String[] SOURCES = {
            "serenity.properties", // legacy 兼容（扁平）
            "serenity.conf",       // legacy 兼容（HOCON）
            "pw.properties",       // 框架原生（预留，扁平）
            "pw.conf"              // 框架原生（预留，HOCON）
    };

    /** 懒加载 + 缓存的一次性扁平化结果；线程安全（双重检查）。 */
    private volatile Map<String, String> flattened;

    @Override
    public String resolve(String key) {
        if (key == null) {
            return null;
        }
        return snapshot().get(key);
    }

    private Map<String, String> snapshot() {
        Map<String, String> local = flattened;
        if (local == null) {
            synchronized (this) {
                local = flattened;
                if (local == null) {
                    local = load();
                    flattened = local;
                }
            }
        }
        return local;
    }

    /** 按 {@link #SOURCES} 顺序读取并合并（后覆盖先）；任一来源缺 / 坏均跳过。 */
    private static Map<String, String> load() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String source : SOURCES) {
            if (source.endsWith(".properties")) {
                merged.putAll(loadProperties(source));
            } else {
                merged.putAll(loadHocon(source));
            }
        }
        if (log.isDebugEnabled() && !merged.isEmpty()) {
            log.debug("[FileConfigResolver] loaded {} file config key(s) from classpath", merged.size());
        }
        return merged;
    }

    /** 读取扁平 properties 资源（Java Properties 语义）；缺文件 / IO 错 → 空 Map。 */
    private static Map<String, String> loadProperties(String resource) {
        Properties props = new Properties();
        try (InputStream in = openResource(resource)) {
            if (in == null) {
                return Map.of();
            }
            props.load(in);
        } catch (IOException | RuntimeException e) {
            log.warn("[FileConfigResolver] failed to read properties '{}', skipped: {}", resource, e.toString());
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            out.put(name, props.getProperty(name));
        }
        return out;
    }

    /** 读取并扁平化 HOCON 资源；缺文件 / 语法错 → 空 Map。 */
    private static Map<String, String> loadHocon(String resource) {
        String content = readResource(resource);
        if (content == null) {
            return Map.of();
        }
        try {
            Config parsed = ConfigFactory.parseString(content);
            // 容忍未定义 substitution（legacy conf 可能引用缺省项），其余原样保留
            Config resolved = parsed.resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            Map<String, String> out = new LinkedHashMap<>();
            flatten(resolved, "", out);
            return out;
        } catch (ConfigException e) {
            log.warn("[FileConfigResolver] failed to parse HOCON '{}', skipped: {}", resource, e.toString());
            return Map.of();
        }
    }

    /**
     * 递归扁平化：对象下钻，叶子（含点分键如 {@code token.security.url}）记为 {@code 点分键=字符串值}。
     * package-private 供单测直接验证（不依赖 classpath 夹具）。
     */
    static void flatten(Config config, String prefix, Map<String, String> out) {
        if (config == null) {
            return;
        }
        for (Map.Entry<String, ConfigValue> e : config.root().entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            ConfigValue value = e.getValue();
            if (value.valueType() == ConfigValueType.OBJECT) {
                flatten(((ConfigObject) value).toConfig(), key, out);
            } else {
                out.put(key, render(value));
            }
        }
    }

    private static String render(ConfigValue value) {
        Object unwrapped = value.unwrapped();
        return unwrapped == null ? null : unwrapped.toString();
    }

    private static InputStream openResource(String resource) {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        InputStream in = tccl == null ? null : tccl.getResourceAsStream(resource);
        return in != null ? in : FileConfigResolver.class.getClassLoader().getResourceAsStream(resource);
    }

    private static String readResource(String resource) {
        try (InputStream in = openResource(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("[FileConfigResolver] failed to read '{}', skipped: {}", resource, e.toString());
            return null;
        }
    }
}

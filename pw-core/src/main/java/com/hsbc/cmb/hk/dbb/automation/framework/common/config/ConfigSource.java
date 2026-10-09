package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.ConfigCipher;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SecretValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * 极薄配置源工具——统一"按 key 解析 + ENC(...) 透明解密"，供 Web(Serenity) 与 API(Typesafe) 两域复用，
 * 避免各自重复实现解密 / 解析逻辑。
 *
 * <p>解析优先级（CORE-P0-1：core 不再耦合任何配置框架）：
 * <ol>
 *   <li>实时系统属性（最高优先级）：支持运行时动态覆盖 / 测试 toggle，且保证
 *       {@code WebFrameworkConfig.setValue(key, v)}（本质是 System.setProperty）即时生效
 *       —— 即<b>「设置」永远实时</b>，不受任何框架启动期快照影响；</li>
 *   <li>环境变量：点/连字符转下划线 + 大写（与 Serenity 的 ENV 映射一致），补齐"仅直读
 *       System.getProperty 会漏掉 ENV"的缺口，使所有 WebFrameworkConfig / ApiFrameworkConfig 均支持 ENV 覆盖；</li>
 *   <li>SPI 扩展配置源：由上层模块（web）通过 JDK {@link ServiceLoader} 提供
 *       {@link ConfigResolver} 实现（如 Serenity 的 serenity.conf/private 合并源）。
 *       core 在编译期与运行期均不依赖 Serenity；无上层实现时该来源为空，自然降级；</li>
 *   <li>默认值。</li>
 * </ol>
 *
 * <p><b>边界（实测，2026-09-28，勿踩）</b>：第 1 步只覆盖「当前存在的系统属性」——
 * <b>{@code System.clearProperty} 之后不保证回落到文件 / 默认值</b>。原因在第 3 步：SPI 源
 * （web 侧即 Serenity 合并源）是<b>启动期系统属性快照 + Typesafe {@code ConfigFactory} 静态缓存</b>，
 * 某键只要在快照期存在过，之后即使清除该属性，该源仍会返回<b>旧值</b>
 * （实测：清除后 {@code getValue()} 仍为 {@code "true"}，且新建
 * {@code SystemEnvironmentVariables} 也无法刷新）。
 * 因此<b>「回退」必须用 {@code setValue}(目标值) 显式表达</b>，不要依赖 {@code clearProperty}；
 * 生产代码中不存在该调用形态，主要是单测需要显式写默认值以建立确定前提。</p>
 *
 * <p>API 域走 Typesafe Config 自有解析，仅对字符串值复用 {@link #decrypt(String)} 同一解密能力。</p>
 *
 * <p>解密严格限定为字符串值：来自系统属性 / 环境变量 / SPI 配置源的值凡 {@code ENC(<base64>)}
 * 或裸 {@code <base64>} 密文均经 {@link SecretValue#decryptIfNeeded(String)} 透明解密，非密文原样返回；
 * 兜底 {@code defaultValue}（代码内字面量）仅显式 {@code ENC(...)} 才解密（C-9）。解密失败
 * （主密钥缺失 / 密文损坏）保留原串，绝不中断配置加载。</p>
 */
public final class ConfigSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigSource.class);

    /**
     * 配置来源层（解析顺序由<b>层序声明</b>决定，高 → 低）。
     */
    public enum Layer {
        /** 环境变量（键名经 {@link #toEnvKey} 归一）。 */
        ENVIRONMENT,
        /** 系统属性（{@code -Dkey=value}；{@code FrameworkConfig.setValue} 的落地层）。 */
        SYSTEM_PROPERTY,
        /** 配置文件属性（SPI {@link ConfigResolver}；web 侧为 Serenity 合并源）。 */
        CONFIG_FILE,
        /** 代码内默认值（最低兜底，非"来源"而是兜底）。 */
        DEFAULT
    }

    /** 框架默认层序：系统属性 → 环境变量 → 配置文件 → 默认值（保持既有 C-9 语义不变）。 */
    public static final List<Layer> DEFAULT_PRECEDENCE =
            List.of(Layer.SYSTEM_PROPERTY, Layer.ENVIRONMENT, Layer.CONFIG_FILE, Layer.DEFAULT);

    /** 采集/环境优先层序：环境变量 → 系统属性 → 配置文件 → 默认值（route 采集三键声明使用）。 */
    public static final List<Layer> ENV_FIRST_PRECEDENCE =
            List.of(Layer.ENVIRONMENT, Layer.SYSTEM_PROPERTY, Layer.CONFIG_FILE, Layer.DEFAULT);

    /** 已上报过「键@来源层」的组合（仅已登记键写入，容量有界 = 注册表规模，杜绝刷屏）。 */
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 单次解析结果：生效值 + 来源层 + 键（供类型转换层与可观测使用）。 */
    public record Resolved(String key, String value, Layer layer) {
    }

    private ConfigSource() {
    }

    /** SPI 发现的配置解析器缓存（懒加载、线程安全；缺实现/异常时降级为空列表）。 */
    private static volatile List<ConfigResolver> resolvers;

    private static List<ConfigResolver> resolvers() {
        if (resolvers == null) {
            synchronized (ConfigSource.class) {
                if (resolvers == null) {
                    List<ConfigResolver> list = new ArrayList<>();
                    try {
                        for (ConfigResolver r : ServiceLoader.load(ConfigResolver.class)) {
                            list.add(r);
                        }
                    } catch (Throwable e) {
                        // SPI 不可用不影响内置解析（系统属性 / 环境变量 / 默认值），但不得静默（D7-3）
                        LOGGER.debug("[ConfigSource] ConfigResolver SPI unavailable, "
                                + "fallback to builtin sources: {}", e.toString());
                    }
                    resolvers = list;
                }
            }
        }
        return resolvers;
    }

    /**
     * 按 key 解析配置值，并对 {@code ENC(<base64>)} / 裸 base64 密文透明解密。
     *
     * <p>解析层序取该键的<b>声明式层序</b>（{@link #precedenceFor}）：已登记键用其
     * {@link ConfigKeys#precedence()} 声明；未登记键用 {@link #DEFAULT_PRECEDENCE}。
     * 语义与改造前完全一致（系统属性优先、仅 {@code null} 视为未命中、默认值按 C-9 解密）。
     *
     * @param key          配置键（如 {@code playwright.browser.type}）
     * @param defaultValue 键缺失时的兜底默认值
     * @return 解析并解密后的配置值
     */
    public static String resolve(String key, String defaultValue) {
        return resolveWithLayer(key, defaultValue).value();
    }

    /**
     * 键的声明式层序：已登记键取其在 {@link ConfigKeys} 的声明，未登记键取框架默认层序。
     *
     * @param key 配置键
     * @return 该键的解析层序（高 → 低，含末位 {@link Layer#DEFAULT}）
     */
    public static List<Layer> precedenceFor(String key) {
        ConfigKeys registered = ConfigKeys.of(key);
        return registered == null ? DEFAULT_PRECEDENCE : registered.precedence();
    }

    /**
     * 显式层序解析（未登记键 / 单测可指定层序；空白仍视为命中，与既有语义一致）。
     *
     * @param key          配置键
     * @param defaultValue 兜底默认值
     * @param precedence   显式层序（高 → 低）
     * @return 解析并解密后的配置值
     */
    public static String resolve(String key, String defaultValue, List<Layer> precedence) {
        return resolveWithLayer(key, defaultValue, precedence, false).value();
    }

    /**
     * 显式层序 + 空白语义解析。
     *
     * @param key               配置键
     * @param defaultValue      兜底默认值
     * @param precedence        显式层序（高 → 低）
     * @param blankFallsThrough 空白值是否视为「未命中」并继续下一层（false = 仅 null 未命中）
     * @return 解析并解密后的配置值
     */
    public static String resolve(String key, String defaultValue,
                                 List<Layer> precedence, boolean blankFallsThrough) {
        return resolveWithLayer(key, defaultValue, precedence, blankFallsThrough).value();
    }

    /**
     * 解析并返回生效值 + 来源层（层序按 {@link #precedenceFor} 声明）。
     *
     * @param key          配置键
     * @param defaultValue 兜底默认值
     * @return 生效值与其来源层
     */
    public static Resolved resolveWithLayer(String key, String defaultValue) {
        return resolveWithLayer(key, defaultValue, precedenceFor(key), false);
    }

    /**
     * 逐层回退解析：命中即解密返回，未命中继续下一层，全部未命中取兜底默认值。
     *
     * <p>C-9：兜底 {@code defaultValue} 为<b>代码内受信字面量</b>，不作为"用户配置的密文"参与
     * 裸密文启发式解密；仅当其显式以 {@code ENC(...)} 标记时才解密。
     *
     * @param key               配置键
     * @param defaultValue      兜底默认值
     * @param precedence        层序（高 → 低）
     * @param blankFallsThrough 空白值是否视为「未命中」并继续下一层
     * @return 生效值与其来源层
     */
    public static Resolved resolveWithLayer(String key, String defaultValue,
                                            List<Layer> precedence, boolean blankFallsThrough) {
        for (Layer layer : precedence) {
            String raw = rawFrom(layer, key);
            if (raw == null || (blankFallsThrough && raw.trim().isEmpty())) {
                continue;
            }
            String value = SecretValue.decryptIfNeeded(raw);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[ConfigSource] resolved {} (layer={})", key, layer);
            }
            reportOnce(key, layer);
            return new Resolved(key, value, layer);
        }
        return new Resolved(key, defaultDecrypted(defaultValue), Layer.DEFAULT);
    }

    /**
     * 单层原始取值（不解密；{@code DEFAULT} 恒返回 null，由兜底分支处理）。
     */
    private static String rawFrom(Layer layer, String key) {
        switch (layer) {
            case ENVIRONMENT:
                // 与 Serenity 的 ENV 映射一致（点/连字符转下划线 + 大写）
                return System.getenv(toEnvKey(key));
            case SYSTEM_PROPERTY:
                // 实时系统属性：支持 FrameworkConfig.setValue(key, v) 运行期即时生效
                return System.getProperty(key);
            case CONFIG_FILE:
                for (ConfigResolver r : resolvers()) {
                    String raw = r.resolve(key);
                    if (raw != null) {
                        return raw;
                    }
                }
                return null;
            default:
                return null;
        }
    }

    /** C-9：兜底默认值为代码内受信字面量，仅显式 {@code ENC(...)} 标记才解密。 */
    private static String defaultDecrypted(String defaultValue) {
        return ConfigCipher.isEncrypted(defaultValue)
                ? SecretValue.decryptIfNeeded(defaultValue)
                : defaultValue;
    }

    /**
     * 首次生效上报：仅对<b>已登记键</b>记录一次「键 + 来源层」（不含值，避免敏感值入日志），
     * 容量有界（≤ 注册表规模 × 层数），热路径不刷屏。
     */
    private static void reportOnce(String key, Layer layer) {
        if (ConfigKeys.of(key) == null || !REPORTED.add(key + '@' + layer)) {
            return;
        }
        LOGGER.info("[ConfigSource] effective {} (layer={})", key, layer);
    }

    /**
     * 配置键 → 环境变量名：大写，并将 {@code .} 与 {@code -} 规范为 {@code _}
     * （与 Serenity 的 ENV 映射保持一致）。
     * 例：{@code serenity.playwright.headless} → {@code SERENITY_PLAYWRIGHT_HEADLESS}。
     */
    static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /**
     * 对任意原始配置值做透明解密（{@code ENC(<base64>)} 或裸 base64 密文）；Web/API 两域通用。
     *
     * @param raw 原始配置值（可能为密文）
     * @return 解密后的值；非密文或解密失败时原样返回
     */
    public static String decrypt(String raw) {
        return SecretValue.decryptIfNeeded(raw);
    }
}

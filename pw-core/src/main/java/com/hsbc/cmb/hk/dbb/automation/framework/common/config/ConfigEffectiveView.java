package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置<b>生效值单一视图</b>（P2-1 门禁）。
 *
 * <p><b>要解决的问题</b>：同一个键可被三层配置影响 —— ① 系统属性（{@code -D}）、② 环境变量、
 * ③ 注册表默认值（{@link ConfigKeys}）。此前没有任何入口能回答「这个键现在到底生效什么值、来自哪一层」，
 * 于是「同一键三层不同值」无法被观察、更无法被门禁拦住（P2-1 点名的状态）。</p>
 *
 * <p><b>本类是唯一答案</b>：{@link #snapshot()} 逐键给出 {@code 生效值 + 来源}；来源为
 * {@link ConfigSource.Layer} 四层之一（系统属性 / 环境变量 / 配置文件属性 / 注册表默认值），
 * 层序<b>不再本类自实现</b>，而是复用 {@link ConfigSource} 的声明式层序
 * （{@link ConfigKeys#precedence()}）——彻底消除此前"漏掉配置文件属性层"的口径分裂。</p>
 *
 * <p><b>可观测性副产品</b>：{@link #envNameCollisions()} 检出「两个不同登记键映射到同一环境变量名」
 * —— 这种情况下两层键会<b>静默共享</b>同一个值，是「同一键多层不同值」的 env 变体，且无法从键名看出。</p>
 *
 * @apiNote framework-internal：治理/诊断用途（门禁与排障），不参与业务配置读取路径。
 */
public final class ConfigEffectiveView {

    /** 生效值来源层。 */
    public enum Source {
        /** 系统属性（{@code -Dkey=value}）。 */
        SYSTEM_PROPERTY,
        /** 环境变量（键名经 {@link FrameworkFlags#toEnvKey} 归一）。 */
        ENVIRONMENT,
        /** 配置文件属性（SPI 配置源，如 Serenity 合并源）。 */
        CONFIG_FILE,
        /** {@link ConfigKeys} 注册表默认值（未配置时）。 */
        REGISTRY_DEFAULT
    }

    /**
     * 单个键的生效视图。
     *
     * @param key    配置键（注册表原样）
     * @param value  生效值
     * @param source 生效值来源层
     */
    public record Entry(String key, String value, Source source) {
    }

    private ConfigEffectiveView() {
    }

    /** 全量登记键的生效视图（键序与注册表一致，便于逐键 diff）。 */
    public static Map<String, Entry> snapshot() {
        Map<String, Entry> view = new LinkedHashMap<>();
        for (ConfigKeys key : ConfigKeys.values()) {
            view.put(key.key(), entry(key));
        }
        return view;
    }

    /**
     * 单键生效视图：按该键<b>声明的层序</b>经 {@link ConfigSource#resolveWithLayer} 解析
     * （含 ③ 配置文件属性层）；空白视为未命中（与改造前一致）。
     */
    public static Entry entry(ConfigKeys key) {
        ConfigSource.Resolved resolved = ConfigSource.resolveWithLayer(key.key(), key.defaultValue(),
                ConfigSource.precedenceFor(key.key()), true);
        return new Entry(key.key(), value(resolved), source(resolved.layer()));
    }

    /** 生效值：默认值层为注册表字面量（不 trim），其余层 trim（与改造前一致）。 */
    private static String value(ConfigSource.Resolved resolved) {
        return resolved.layer() == ConfigSource.Layer.DEFAULT
                ? resolved.value() : resolved.value().trim();
    }

    /** 来源层 → 视图来源。 */
    private static Source source(ConfigSource.Layer layer) {
        switch (layer) {
            case ENVIRONMENT:
                return Source.ENVIRONMENT;
            case SYSTEM_PROPERTY:
                return Source.SYSTEM_PROPERTY;
            case CONFIG_FILE:
                return Source.CONFIG_FILE;
            default:
                return Source.REGISTRY_DEFAULT;
        }
    }

    /**
     * 环境变量名冲突清单（空表示无冲突）。
     *
     * <p>两个不同键归一后同名 ⇒ 它们只能同时拿到同一个 env 值，其中一个的语义必然被静默改变。</p>
     *
     * @return 形如 {@code "FRAMEWORK_A_B ← [a.b, framework.a-b]"} 的可读行
     */
    public static List<String> envNameCollisions() {
        Map<String, List<String>> byEnvName = new LinkedHashMap<>();
        for (ConfigKeys key : ConfigKeys.values()) {
            byEnvName.computeIfAbsent(FrameworkFlags.toEnvKey(key.key()), k -> new ArrayList<>())
                    .add(key.key());
        }
        List<String> collisions = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byEnvName.entrySet()) {
            if (e.getValue().size() > 1) {
                collisions.add(e.getKey() + " ← " + e.getValue());
            }
        }
        return collisions;
    }

}

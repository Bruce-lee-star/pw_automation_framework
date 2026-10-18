package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigResolver;

/**
 * 配置解析器（{@link ConfigResolver} 的 SPI 实现，下沉 web —— CORE-P0-1）。
 *
 * <p>core 的 {@link ConfigSource} 通过 JDK {@link java.util.ServiceLoader} 发现本实现。
 * 在移除 框架（DEV-V5）后，本实现不再依赖 {@code net.thucydides.model.environment
 * .SystemEnvironmentVariables}，改为直接从 JVM 系统属性与环境变量解析 key，保留原 框架 合并源的
 * 主导解析路径（命令行 {@code -Dkey=value} 覆盖 + 环境变量 {@code KEY} 兜底）。</p>
 *
 * <p>实现语义：先取系统属性 {@code key}；若无则尝试环境变量（key 的 {@code .} 替换为 {@code _} 并转大写，
 * 与 框架 历史行为一致）；否则返回 {@code null}，由 {@link ConfigSource} 降级到下一来源或默认值。</p>
 */
public class FrameworkConfigResolver implements ConfigResolver {

    @Override
    public String resolve(String key) {
        if (key == null) {
            return null;
        }
        String value = System.getProperty(key);
        if (value != null) {
            return value;
        }
        // 框架 历史行为：点分 key 映射为下划线大写的环境变量
        String envKey = key.replace('.', '_').toUpperCase();
        return System.getenv(envKey);
    }
}

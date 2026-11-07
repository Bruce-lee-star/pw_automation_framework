package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * 非功能基线出口的 SPI 解析 + 双检锁缓存 + no-op 回落（T9）。
 *
 * <p>与 R9 {@code AgentTraceRegistry} 同构。{@code resolve()} 的
 * {@code catch (Throwable)} 必须覆盖 {@link java.util.ServiceConfigurationError}：
 * 它是 {@link Error} 子类而非 {@link LinkageError}，普通 {@code catch (Exception)}
 * 抓不住，会让调用方类初始化永久失败、真实原因被后续 {@link NoClassDefFoundError} 掩盖。</p>
 *
 * <p>无贡献者或加载失败时回落 {@link AgentPerfSink#NO_OP}，执行链行为与采集开关关闭时一致。</p>
 */
public final class AgentPerfRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentPerfRegistry.class);

    private static volatile AgentPerfSink instance;

    private AgentPerfRegistry() {
    }

    /** 当前出口；永不为 {@code null}。 */
    public static AgentPerfSink get() {
        AgentPerfSink sink = instance;
        if (sink == null) {
            synchronized (AgentPerfRegistry.class) {
                sink = instance;
                if (sink == null) {
                    sink = resolve();
                    instance = sink;
                }
            }
        }
        return sink;
    }

    /** 测试 / 宿主可注入替身；传 {@code null} 复位为下次按 SPI 重新解析。 */
    public static void install(AgentPerfSink sink) {
        synchronized (AgentPerfRegistry.class) {
            instance = sink;
        }
    }

    /** 复位，便于测试 teardown。 */
    public static void reset() {
        install(null);
    }

    private static AgentPerfSink resolve() {
        try {
            for (AgentPerfSink candidate : ServiceLoader.load(AgentPerfSink.class)) {
                if (candidate != null) {
                    return candidate;
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[agent-perf] SPI provider unavailable, collection disabled: {}", t.toString());
        }
        return AgentPerfSink.NO_OP;
    }
}

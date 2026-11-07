package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * AgentTraceSink 的 ServiceLoader 解析 + 双检锁单例 + no-op 回落（R9）。
 *
 * <p>与 {@link LocatorHealthReportRegistry} 同构；{@code resolve()} 的 {@code catch (Throwable)}
 * 必须覆盖 {@link java.util.ServiceConfigurationError}——它是 {@link Error} 子类，常规
 * {@code catch (Exception)} 抓不住，会让本类 {@code <clinit>} 失败并导致此后每次访问都抛
 * {@link NoClassDefFoundError}，且真实原因被掩盖。</p>
 */
public final class AgentTraceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTraceRegistry.class);

    private static final AgentTraceSink NO_OP = event -> { };

    private static volatile AgentTraceSink instance;

    private AgentTraceRegistry() {
    }

    public static AgentTraceSink get() {
        AgentTraceSink sink = instance;
        if (sink == null) {
            synchronized (AgentTraceRegistry.class) {
                sink = instance;
                if (sink == null) {
                    sink = resolve();
                    instance = sink;
                }
            }
        }
        return sink;
    }

    /** 测试/宿主可注入替身；传 null 复位为下次按 SPI 重新解析。 */
    public static void install(AgentTraceSink sink) {
        synchronized (AgentTraceRegistry.class) {
            instance = sink;
        }
    }

    private static AgentTraceSink resolve() {
        try {
            for (AgentTraceSink candidate : ServiceLoader.load(AgentTraceSink.class)) {
                return candidate;
            }
        } catch (Throwable t) {
            LOGGER.debug("[agent-trace] SPI provider unavailable, section disabled: {}", t.toString());
        }
        return NO_OP;
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * {@link ReportingBridge} 的运行时解析器。
 *
 * <p>经 {@link ServiceLoader} 发现 classpath 上唯一的 SPI 实现；无实现时回落到
 * 默认 no-op 桥（接口默认方法已全为 no-op），调用方无需判空、无需感知"是否装配了报告模块"。
 *
 * <p>解析结果惰性缓存（首个 {@link #get()} 调用时定格），避免每次调用都走 ServiceLoader 扫描。
 */
public final class ReportingBridgeRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportingBridgeRegistry.class);

    private static volatile ReportingBridge instance;

    private ReportingBridgeRegistry() {
    }

    /**
     * 取得报告桥（已装配 SPI 实现则返回之，否则返回 no-op 默认桥）。
     */
    public static ReportingBridge get() {
        ReportingBridge b = instance;
        if (b == null) {
            synchronized (ReportingBridgeRegistry.class) {
                b = instance;
                if (b == null) {
                    b = resolve();
                    instance = b;
                }
            }
        }
        return b;
    }

    private static ReportingBridge resolve() {
        try {
            for (ReportingBridge candidate : ServiceLoader.load(ReportingBridge.class)) {
                //  取首个实现即生效（报告引擎实现应为单例）
                return candidate;
            }
        } catch (Throwable t) {
            //  SPI 装配失败（实现类不可加载 / 构造异常 / ServiceConfigurationError）⇒ 回落 no-op 桥。
            //  注意：classpath 上「没有实现」时 ServiceLoader 只是迭代为空、<b>不会</b>进 catch，
            //  故此分支必属真实异常（含 Error 子类，如 ServiceConfigurationError 并非 LinkageError）。
            //  按 checkstyle D7-3，catch 内不得只有注释，须有真实动作（这里以 WARN 以上级别留痕）。
            LOGGER.warn("ReportingBridge SPI resolution failed; falling back to no-op bridge: {}", t.toString());
        }
        return new ReportingBridge() {
        };
    }

    /**
     * 测试 / 装配入口：显式覆盖默认解析结果（如单测注入 v2 实现做断言）。
     *
     * @param bridge 桥实现；{@code null} 复位为重新按 SPI 解析
     */
    public static void install(ReportingBridge bridge) {
        synchronized (ReportingBridgeRegistry.class) {
            instance = bridge;
        }
    }
}

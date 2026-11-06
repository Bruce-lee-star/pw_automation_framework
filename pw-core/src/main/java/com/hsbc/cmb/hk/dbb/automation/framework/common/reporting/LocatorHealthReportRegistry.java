package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * {@link LocatorHealthReportSink} 的运行时解析器。
 *
 * <p>经 {@link ServiceLoader} 发现 classpath 上唯一的实现（框架里由 pw-web-ui 注册）；无实现时
 * 回落到返回空数据的默认实现，报告侧据此<b>整块不渲染</b>——报告模块无需判空、也无需感知
 * "是否装配了采集模块"。
 *
 * <p>解析结果惰性缓存（首个 {@link #get()} 定格），避免每次渲染都走 ServiceLoader 扫描。
 *
 * <p><b>解耦口径</b>：{@code install(null)} 可复位为"下次调用重新按 SPI 解析"，
 * 供单测注入替身后清理，避免跨测试类污染。
 *
 * <p><b>容错口径（重要）</b>：SPI 发现属尽力而为，捕获 {@link Throwable} ——
 * 服务文件存在但实现类不可加载时抛的是 {@code ServiceConfigurationError}（{@code Error} 子类，
 * 非 {@code LinkageError}），只抓 {@code Exception} 会让 {@code <clinit>} 失败并把真实原因
 * 掩盖成后续的 {@code NoClassDefFoundError}。
 */
public final class LocatorHealthReportRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocatorHealthReportRegistry.class);

    private static final LocatorHealthReportSink NO_OP = LocatorHealthReportData::empty;

    private static volatile LocatorHealthReportSink instance;

    private LocatorHealthReportRegistry() {
    }

    /** 取得数据源（已装配 SPI 实现则返回之，否则返回空数据的默认实现）。 */
    public static LocatorHealthReportSink get() {
        LocatorHealthReportSink sink = instance;
        if (sink == null) {
            synchronized (LocatorHealthReportRegistry.class) {
                sink = instance;
                if (sink == null) {
                    sink = resolve();
                    instance = sink;
                }
            }
        }
        return sink;
    }

    /** 便捷方法：直接取数据（无实现时返回 {@link LocatorHealthReportData#empty()}）。 */
    public static LocatorHealthReportData collect() {
        try {
            LocatorHealthReportData data = get().collectData();
            return data == null ? LocatorHealthReportData.empty() : data;
        } catch (Throwable t) {
            // 采集模块的读取异常绝不影响报告主流程
            LOGGER.debug("[locator-health] collect failed, section skipped: {}", t.toString());
            return LocatorHealthReportData.empty();
        }
    }

    private static LocatorHealthReportSink resolve() {
        try {
            for (LocatorHealthReportSink candidate : ServiceLoader.load(LocatorHealthReportSink.class)) {
                return candidate;
            }
        } catch (Throwable t) {
            LOGGER.debug("[locator-health] SPI provider unavailable, section disabled: {}", t.toString());
        }
        return NO_OP;
    }

    /**
     * 测试 / 装配入口：显式覆盖解析结果。
     *
     * @param sink 数据源实现；{@code null} 复位为下次重新按 SPI 解析
     */
    public static void install(LocatorHealthReportSink sink) {
        synchronized (LocatorHealthReportRegistry.class) {
            instance = sink;
        }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

/**
 * 定位健康度数据源 SPI：采集模块实现、报告模块消费。
 *
 * <p>角色定位与 {@link MonitorFailureReportSink} 相同——把「运行时采集」与「报告渲染」解耦，
 * 使 pw-reports 不必依赖 pw-web-ui（依赖方向恒为 web → reports → core）。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>{@link #collectData()} 只<b>读取</b>，不落盘、不复位采集器（复位由采集模块自行负责）；</li>
 *   <li>无采集能力（classpath 上没装采集模块）时由 {@link LocatorHealthReportRegistry}
 *       回落到默认实现，返回 {@link LocatorHealthReportData#empty()}，报告侧不渲染区块；</li>
 *   <li>实现必须是线程安全的：报告渲染可能与用例线程并发。</li>
 * </ul>
 */
@FunctionalInterface
public interface LocatorHealthReportSink {

    /**
     * 收集供 HTML 报告展示的定位健康度数据。
     *
     * @return 汇总数据（非 {@code null}；无数据时用 {@link LocatorHealthReportData#empty()}）
     */
    LocatorHealthReportData collectData();
}

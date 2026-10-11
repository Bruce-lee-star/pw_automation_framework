package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;

import java.util.List;

/**
 * API 监控失败报告的写出契约（依赖倒置点）。
 *
 * <p>框架级报告聚合器（SummaryReportGenerator）仅依赖本接口，不依赖 route 的具体实现；
 * 具体实现由 route 模块通过 {@code META-INF/services} SPI 提供，运行时由
 * {@link java.util.ServiceLoader} 发现。借此打破 reporting ↔ route 的编译期循环依赖。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #record(List)} — 归集断言失败（默认 no-op；route 生产实现覆盖）</li>
 *   <li>{@link #write()} — 写出失败报告，返回失败 owner 数（&gt;0 表示需通知）</li>
 *   <li>{@link #clear()} — 写出后清空归集器，防止多套件同 JVM 运行时的跨套件累积</li>
 * </ul>
 */
public interface MonitorFailureReportSink {

    /**
     * 归集一批已定案的 route 断言失败（探针喂入点；默认 no-op，reporting 测试桩无需改动）。
     *
     * <p>设计意图：断言失败是<b>消费式</b>的（web 侧经探针 drain 后即清空、用于抛错/标记），
     * 归集发生在「失败被取走的同时」——一份失败两用：即时抛错 + 套件收尾聚合报告，
     * 不引入第二次消费，也就不存在与既有消费路径的时序竞争。
     *
     * @param failures 已定案断言失败（可为空；实现必须容忍跨线程调用）
     */
    default void record(List<RouteAssertionFailure> failures) {
        // no-op：不强制 sink 实现提供归集能力
    }

    /**
     * 写出 API 监控失败报告（如 {@code target/monitor-failures-by-owner.json}）。
     *
     * @return 失败 owner 数（&gt;0 表示有失败需要通知）
     */
    int write();

    /**
     * 清空内部归集状态（测试套件结束时调用）。
     */
    void clear();

    /**
     * 收集供 HTML 报告展示的监控失败 / 数据丢失汇总数据。
     *
     * <p>默认返回 {@link MonitorFailureReportData#empty()}；route 侧实现返回真实聚合。
     * 本方法<b>仅读取</b>、不写出文件、也不复位归集器（复位由 {@link #clear()} 负责），
     * 须先于 {@link #clear()} 调用，以免数据被复位后丢失。
     *
     * @return HTML 报告展示所需的汇总数据（非 {@code null}）
     */
    default MonitorFailureReportData collectData() {
        return MonitorFailureReportData.empty();
    }
}

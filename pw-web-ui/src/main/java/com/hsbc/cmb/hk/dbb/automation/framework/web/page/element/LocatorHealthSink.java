package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportData;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthReportSink;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.LocatorHealthViolation;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link LocatorHealthReportSink} 的框架实现：把 {@link LocatorHealthRecorder} 的内存快照
 * 翻译成跨模块 DTO，供报告模块渲染「定位健康度」区块。
 *
 * <p>经 {@code META-INF/services} 注册，报告模块用 {@code ServiceLoader} 发现；
 * 与 web 模块解耦（pw-reports 只依赖 pw-core 的 SPI/DTO）。
 *
 * <p><b>只读、不落盘、不复位</b>采集器（{@link LocatorHealthReportWriter} 负责落盘）。
 * 读取失败 fail-open 返回空数据：报告是旁路，绝不允许因它抛错而影响报告生成。
 */
public final class LocatorHealthSink implements LocatorHealthReportSink {

    @Override
    public LocatorHealthReportData collectData() {
        try {
            List<LocatorHealthRecorder.SelectorHealth> selectors = LocatorHealthRecorder.snapshot();
            List<LocatorHealthEntry> entries = new ArrayList<>(selectors.size());
            for (LocatorHealthRecorder.SelectorHealth health : selectors) {
                entries.add(new LocatorHealthEntry(health.selector(), health.kind(),
                        health.uses(), health.violations(), health.maxMatchCount()));
            }

            List<LocatorHealthRecorder.Violation> recorded = LocatorHealthRecorder.violations();
            List<LocatorHealthViolation> violations = new ArrayList<>(recorded.size());
            for (LocatorHealthRecorder.Violation v : recorded) {
                violations.add(new LocatorHealthViolation(v.scenario(), v.selector(), v.kind(),
                        v.operation(), v.matchCount(), v.thread(), v.at().toString()));
            }
            return new LocatorHealthReportData(entries, violations, LocatorHealthRecorder.truncated());
        } catch (RuntimeException e) {
            // 采集快照读取异常不影响报告主流程（诊断旁路，fail-open）
            return LocatorHealthReportData.empty();
        }
    }
}

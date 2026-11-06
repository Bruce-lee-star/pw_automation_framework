package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import java.util.Collections;
import java.util.List;

/**
 * 定位健康度汇总（dependency-inversion 载体，供 HTML 报告展示）。
 *
 * <p>由采集模块经 {@link LocatorHealthReportSink#collectData()} 产出；报告模块据此渲染区块，
 * 从而无需编译期依赖采集模块（pw-reports 不得反向依赖 pw-web-ui）。
 *
 * <p>集合字段恒非 {@code null}（{@link #empty()} 以空集合表示"无内容"），
 * 方便 Freemarker 安全遍历；{@link #hasContent()} 供模板决定整块渲染与否。
 */
public class LocatorHealthReportData {

    private final List<LocatorHealthEntry> selectors;
    private final List<LocatorHealthViolation> violations;
    private final boolean truncated;

    public LocatorHealthReportData(List<LocatorHealthEntry> selectors,
                                   List<LocatorHealthViolation> violations,
                                   boolean truncated) {
        // 防御性拷贝为不可变集合，避免返回内部可变引用（EI_EXPOSE_REP）
        this.selectors = selectors == null ? List.of() : List.copyOf(selectors);
        this.violations = violations == null ? List.of() : List.copyOf(violations);
        this.truncated = truncated;
    }

    /** 按选择器聚合的健康度（违例降序 → 用量降序）。 */
    public List<LocatorHealthEntry> getSelectors() {
        return selectors;
    }

    /** 逐条 strict 违例证据（按发生顺序）。 */
    public List<LocatorHealthViolation> getViolations() {
        return violations;
    }

    /** 采集条目是否曾达到上界被截断（报告需提示"数据不完整"）。 */
    public boolean isTruncated() {
        return truncated;
    }

    /** 采集到的选择器条数。 */
    public int getSelectorsTotal() {
        return selectors.size();
    }

    /** strict 违例条数。 */
    public int getViolationsTotal() {
        return violations.size();
    }

    /**
     * 是否有可展示内容。
     *
     * <p>只看有无采集样本（{@code selectors} 非空）：本轮全是"零违例"也是有效结论
     * ——说明定位稳定，报告应展示该结论而不是静默隐藏区块。
     */
    public boolean hasContent() {
        return !selectors.isEmpty() || !violations.isEmpty();
    }

    public static LocatorHealthReportData empty() {
        return new LocatorHealthReportData(Collections.emptyList(), Collections.emptyList(), false);
    }
}

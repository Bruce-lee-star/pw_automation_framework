package com.hsbc.cmb.hk.dbb.automation.framework.report.migration;

import java.util.List;

/**
 * 四一致门禁结果。{@link #failed()} 表示存在 ERROR 级违例（应阻断迁移双跑）。
 */
public final class GateReport {

    private final List<GateViolation> violations;

    public GateReport(List<GateViolation> violations) {
        this.violations = List.copyOf(violations);
    }

    public List<GateViolation> violations() {
        return violations;
    }

    public boolean failed() {
        return violations.stream().anyMatch(v -> v.severity() == GateViolation.Severity.ERROR);
    }

    public List<GateViolation> errors() {
        return violations.stream()
                .filter(v -> v.severity() == GateViolation.Severity.ERROR)
                .toList();
    }

    public List<GateViolation> warnings() {
        return violations.stream()
                .filter(v -> v.severity() == GateViolation.Severity.WARN)
                .toList();
    }

    @Override
    public String toString() {
        return "GateReport{failed=" + failed() + ", errors=" + errors().size()
                + ", warnings=" + warnings().size() + "}";
    }
}

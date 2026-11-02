package com.hsbc.cmb.hk.dbb.automation.framework.report.migration;

/**
 * 四一致门禁的单项违例。
 */
public record GateViolation(Dimension dimension, Severity severity, String message, String outcomeId) {

    public enum Dimension {
        COVERAGE,   // 用例集合对称差（某一侧多出/少了 scenario）
        RESULT,     // 同一 scenario 的 result 不一致
        STEP_TREE,  // 步骤树（扁平描述序列）不一致
        SUMMARY     // 汇总计数（count / failures）不一致
    }

    public enum Severity {
        ERROR, // 阻断迁移的门禁失败
        WARN   // 提示性，不阻断
    }

    static GateViolation error(Dimension d, String msg, String id) {
        return new GateViolation(d, Severity.ERROR, msg, id);
    }

    static GateViolation warn(Dimension d, String msg, String id) {
        return new GateViolation(d, Severity.WARN, msg, id);
    }
}

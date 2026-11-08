package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation;

import java.util.List;

/**
 * 资产生成校验报告（设计 §4.6 ④ 确定性校验）。
 *
 * <p>校验器产出结构化报告：是否通过、问题清单（错误阻断落库，警告仅提示）、摘要。
 * 校验不通过 → 流水线失败关闭转人工，<b>不自动回退</b>（§4.4 铁律）。</p>
 *
 * @param passed  是否通过（存在 ERROR 级问题即为 false）
 * @param issues  问题清单
 * @param summary 摘要
 */
public record ValidationReport(boolean passed, List<Issue> issues, String summary) {

    public ValidationReport {
        issues = issues == null ? List.of()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(issues));
    }

    @Override
    public List<Issue> issues() {
        return java.util.Collections.unmodifiableList(issues);
    }

    public static ValidationReport ok(String summary) {
        return new ValidationReport(true, List.of(), summary);
    }

    public static ValidationReport fail(List<Issue> issues, String summary) {
        return new ValidationReport(false, issues, summary);
    }

    /** 问题条目。 */
    public record Issue(Severity severity, String message) {
        public enum Severity { ERROR, WARNING }
        public static Issue error(String m) { return new Issue(Severity.ERROR, m); }
        public static Issue warning(String m) { return new Issue(Severity.WARNING, m); }
    }
}

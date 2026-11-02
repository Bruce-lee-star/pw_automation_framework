package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.ValidationReport;

/**
 * 流水线校验失败异常（设计 §4.6 ④ / §4.4 铁律）。
 *
 * <p>校验不通过时抛出，<b>失败关闭转人工</b>，不自动回退、不自动落库为可信资产（R6）。
 * 携带校验报告供诊断。</p>
 */
public final class PipelineValidationException extends RuntimeException {

    private final transient ValidationReport report;

    public PipelineValidationException(ValidationReport report) {
        super("Asset pipeline validation failed: " + report.summary());
        this.report = report;
    }

    public ValidationReport report() {
        return report;
    }
}

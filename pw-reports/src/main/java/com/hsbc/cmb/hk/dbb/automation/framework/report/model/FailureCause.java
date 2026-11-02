package com.hsbc.cmb.hk.dbb.automation.framework.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hsbc.cmb.hk.dbb.automation.framework.report.analysis.FailureAnalysis;

/**
 * 失败原因——JSON 结构对齐 Serenity {@code FailureCause}（{@code message} + {@code errorType}）。
 *
 * <p>消费方实证：框架 {@code SummaryReportGenerator} 读取
 * {@code testFailureCause.message} / {@code testFailureCause.errorType}，缺省回退
 * {@code errorMessage}——故用例级同时输出 {@code testFailureCause} 与
 * {@code testFailureMessage} 两个字段，双口径兼容。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FailureCause {

    private String message;

    /** 语义错误类别（{@code FAILURE} / {@code ERROR} / {@code COMPROMISED}），用于分组展示。 */
    private String errorType;

    public FailureCause() {
    }

    public FailureCause(String message, String errorType) {
        this.message = message;
        this.errorType = errorType;
    }

    /** 从 Throwable 构造；null 入参返回 null（调用方按需判空）。 */
    public static FailureCause from(Throwable t) {
        if (t == null) {
            return null;
        }
        FailureAnalysis analysis = FailureAnalysis.analyse(t);
        return new FailureCause(analysis.getMessage(), analysis.getErrorType().name());
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }
}

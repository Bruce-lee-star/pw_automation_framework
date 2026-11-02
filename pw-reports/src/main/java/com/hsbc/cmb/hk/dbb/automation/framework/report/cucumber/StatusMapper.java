package com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import io.cucumber.plugin.event.Status;

/**
 * Cucumber Status → 报告 TestResult 映射（R7 全枚举，防漏）。
 *
 * <p>实证口径（cucumber-plugin 7.34.9，Status 共 7 个枚举值）：
 * <table border="1">
 *   <tr><th>Cucumber</th><th>TestResult</th><th>说明</th></tr>
 *   <tr><td>PASSED</td><td>SUCCESS</td><td></td></tr>
 *   <tr><td>FAILED</td><td>FAILURE</td><td></td></tr>
 *   <tr><td>SKIPPED</td><td>SKIPPED</td><td>前置失败后跳过</td></tr>
 *   <tr><td>PENDING</td><td>PENDING</td><td>步骤定义标记 pending</td></tr>
 *   <tr><td>UNDEFINED</td><td>UNDEFINED</td><td>步骤定义缺失</td></tr>
 *   <tr><td>AMBIGUOUS</td><td>FAILURE</td><td>步骤定义歧义——按失败上报，附注见
 *       {@link #AMBIGUOUS_NOTE}</td></tr>
 *   <tr><td>UNUSED</td><td>null</td><td>不产生 session（调用方收到 null 应跳过）</td></tr>
 * </table>
 */
public final class StatusMapper {

    /** AMBIGUOUS 附注——挂到步骤描述，说明失败根因是定义歧义而非业务断言。 */
    public static final String AMBIGUOUS_NOTE = "(ambiguous step definition)";

    private StatusMapper() {
    }

    /** 映射；UNUSED → null（调用方必须判空）。 */
    public static TestResult toTestResult(Status status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PASSED -> TestResult.SUCCESS;
            case FAILED -> TestResult.FAILURE;
            case SKIPPED -> TestResult.SKIPPED;
            case PENDING -> TestResult.PENDING;
            case UNDEFINED -> TestResult.UNDEFINED;
            case AMBIGUOUS -> TestResult.FAILURE;
            case UNUSED -> null;
        };
    }
}

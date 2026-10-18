package com.hsbc.cmb.hk.dbb.automation.framework.report.model;

/**
 * 测试结果枚举——JSON 取值与 Serenity {@code TestResult} 对齐（DR-1：镜像 schema）。
 *
 * <p>与框架自有 {@code com.hsbc...common.result.TestResult}（D4-2，语义枚举）的转换由
 * 换靶后的 {@code ResultAdapter} 完成；本枚举只服务于报告产物口径。
 *
 * <p>与 Serenity 的差异说明（additive-only 约束下有意为之）：
 * <ul>
 *   <li>不引入 {@code COMPROMISED / UNSUCCESSFUL / ABORTED}——本库无对应语义来源，
 *       新增枚举值属 additive 变更，留待真有消费诉求时再加；</li>
 *   <li>{@code ERROR} 与 {@code FAILURE} 分立（异常 vs 断言失败），与 Serenity 一致。</li>
 * </ul>
 */
public enum TestResult {

    /** Cucumber 步骤定义缺失 / 未识别状态。 */
    UNDEFINED,

    /** 通过。 */
    SUCCESS,

    /** 待实现 / 挂起（Cucumber PENDING）。 */
    PENDING,

    /** 被忽略。 */
    IGNORED,

    /** 因前置失败被跳过。 */
    SKIPPED,

    /** 断言失败。 */
    FAILURE,

    /** 执行异常（非断言）。 */
    ERROR;

    /** 是否失败（FAILURE / ERROR）。 */
    public boolean isFailure() {
        return this == FAILURE || this == ERROR;
    }

    /** 是否未完成（PENDING / SKIPPED / IGNORED / UNDEFINED）。 */
    public boolean isPending() {
        return this == PENDING || this == SKIPPED || this == IGNORED || this == UNDEFINED;
    }
}

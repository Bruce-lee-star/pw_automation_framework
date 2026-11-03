package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

/**
 * 候选生命周期状态（设计 §4.4）：<b>提出 → 验证 → 审批 → 失效</b>。
 *
 * <p>{@link #VALIDATED} 常被误读成"可用"。这里刻意不提供"验证通过即 ACTIVE"的捷径：
 * 只有 {@link #APPROVED}（人工审批）才可能被 {@code productionLocator} 取出，
 * 且一旦 {@link #RETIRED} 就永久退出，不参与回退。
 */
public enum CandidateState {
    /** 已提出，尚未验证（AI/录制/人工的任何提议都从这里开始）。 */
    PROPOSED,
    /** 五条确定性规则全部通过；<b>仍不是生产定位器</b>。 */
    VALIDATED,
    /** 验证未通过：保留证据以便追溯，不得审批。 */
    REJECTED,
    /** 人工审批通过（记录审批者与应用版本），可被生产取出。 */
    APPROVED,
    /** 失效（元素/页面改版、定位漂移、人工下架）：终态，不参与任何回退。 */
    RETIRED
}

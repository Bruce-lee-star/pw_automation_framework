package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.util.Locale;

/**
 * <b>业务后果等级</b>（设计 §9 P4「按业务后果授权」）：决定一条定位器要几人批、能不能自审、批准多久有效。
 *
 * <h2>为什么按后果分级，而不是"所有审批一律两人"</h2>
 * <p>一律两人会让人绕过流程（凑人头），一律一人则让"转账确认按钮"和"页脚版权链接"拿到同一档授权。
 * 分级的实质是把审批强度<b>对齐到点错之后的业务代价</b>：点错登录按钮只是跑不过用例，
 * 点错转账确认按钮是真实资金风险。
 *
 * <h2>为什么没有默认值</h2>
 * <p>{@link ApprovalPolicy} 要求审批请求<b>显式</b>给出等级。若允许缺省，凡是"忘了标"的候选都会自动落进最松的档，
 * 分级就退化成"谁漏填谁受益"——这正是治理机制失效的典型方式。
 */
public enum RiskTier {

    /** 只影响用例可读性（如展示性链接）：1 人，可自审，最长 180 天。 */
    LOW(1, false, 180),

    /** 影响流程走向（如查询提交）：1 人且不得自审，最长 90 天。 */
    MEDIUM(1, true, 90),

    /** 影响数据写入（如开户信息保存）：1 人且不得自审，最长 30 天。 */
    HIGH(1, true, 30),

    /** 影响资金 / 不可逆操作（如转账确认、放款提交）：2 名不同审批人且均不得自审，最长 7 天。 */
    CRITICAL(2, true, 7);

    private final int requiredApprovals;
    private final boolean separationOfDuties;
    private final int maxValidityDays;

    RiskTier(int requiredApprovals, boolean separationOfDuties, int maxValidityDays) {
        this.requiredApprovals = requiredApprovals;
        this.separationOfDuties = separationOfDuties;
        this.maxValidityDays = maxValidityDays;
    }

    /** 至少需要的<b>不同</b>审批人数。 */
    public int requiredApprovals() {
        return requiredApprovals;
    }

    /** 是否要求审批人与提出者不是同一个人（职责分离）。 */
    public boolean separationOfDuties() {
        return separationOfDuties;
    }

    /** 审批有效期上限（天）。超过上限的请求被拒绝，不允许"给关键定位器批一年"。 */
    public int maxValidityDays() {
        return maxValidityDays;
    }

    /** 宽松解析：大小写不敏感，接受 {@code CRITICAL} / {@code critical}。 */
    public static RiskTier parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少业务后果等级（risk）：审批强度必须显式声明，不提供默认档");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知业务后果等级：" + raw + "（可选 LOW/MEDIUM/HIGH/CRITICAL）");
        }
    }

    /** 人类可读说明，进审计报告与 CLI 输出。 */
    public String describe() {
        switch (this) {
            case LOW:      return "影响可读性：1 人审批，允许自审，≤" + maxValidityDays + " 天";
            case MEDIUM:   return "影响流程走向：1 人审批，须职责分离，≤" + maxValidityDays + " 天";
            case HIGH:     return "影响数据写入：1 人审批，须职责分离，≤" + maxValidityDays + " 天";
            case CRITICAL: return "影响资金/不可逆操作：2 人审批，须职责分离，≤" + maxValidityDays + " 天";
            default:       return "";
        }
    }
}

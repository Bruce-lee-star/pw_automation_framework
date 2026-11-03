package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.util.Locale;

/**
 * 审计报告的<b>读取者角色</b>，决定其可被授予的最高导出脱敏级别（R9 访问控制 / 读取侧鉴权）。
 *
 * <p>同一份候选库，按读取者的信任等级给出不同脱敏程度的报告：
 * <ul>
 *   <li>{@link #EXTERNAL}：外部 / 弱信任 / 匿名 —— 只能看 {@link AuditExposure#SANITIZED}（最小权限，fail-safe 默认）；</li>
 *   <li>{@link #ENGINEER}：内部工程师 —— 可看 {@link AuditExposure#INTERNAL}（实名但证据路径已收敛）；</li>
 *   <li>{@link #AUDITOR}：合规 / 审计员 —— 可看 {@link AuditExposure#FULL}（完整信息）。</li>
 * </ul>
 *
 * <p>角色<b>解析</b>与<b>授权判定</b>分离：本枚举只负责"某角色最高能看到哪一级"；
 * 读取者<b>是谁</b>（身份认证）由运行时 / 调用方提供（CLI 的 {@code --reader}，或 pw-web-ui 注入的已认证主体），
 * 库本身只做授权决策，不替调用方做身份验证。未知或空角色一律按 {@link #EXTERNAL} 处理，
 * 保证"未断言身份 = 最小权限"，而非默认全量可见。
 */
public enum AuditReaderRole {

    /** 外部 / 弱信任 / 匿名：只能看脱敏报告。 */
    EXTERNAL(AuditExposure.SANITIZED),
    /** 内部工程师：可看内部共享级。 */
    ENGINEER(AuditExposure.INTERNAL),
    /** 合规 / 审计员：可看完整信息。 */
    AUDITOR(AuditExposure.FULL);

    private final AuditExposure maxExposure;

    AuditReaderRole(AuditExposure maxExposure) {
        this.maxExposure = maxExposure;
    }

    /** 该角色可被授予的最高导出级别。 */
    public AuditExposure maxExposure() {
        return maxExposure;
    }

    /**
     * 按名解析角色；未知或空角色一律回退 {@link #EXTERNAL}（fail-safe，最小权限）。
     * 不抛异常——宁可降级到最小权限，也不要因"认不出角色"就默认放行全量。
     */
    public static AuditReaderRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return EXTERNAL;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EXTERNAL;
        }
    }
}

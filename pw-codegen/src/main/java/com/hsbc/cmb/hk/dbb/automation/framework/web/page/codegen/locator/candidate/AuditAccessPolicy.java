package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

/**
 * 审计报告的<b>读取侧鉴权策略</b>（R9 访问控制 / 数据最小化）。
 *
 * <p>把"谁能导出哪个脱敏级别"从口头约定固化为可单测的决策：给定读取者角色与其请求的导出级别，
 * 决定允许的导出级别。
 *
 * <p>规则（fail-closed）：
 * <ul>
 *   <li>请求级别 {@code rank} ≤ 角色允许上限 {@code rank} → 允许，按<b>请求级别</b>导出；</li>
 *   <li>请求级别 {@code rank} &gt; 角色允许上限 {@code rank} → <b>拒绝</b>（越权，不静默降级）；</li>
 *   <li>角色为 {@code null} 或未知 → 按 {@link AuditReaderRole#EXTERNAL} 处理（最小权限，只能 SANITIZED）。</li>
 * </ul>
 *
 * <p>本类只做<b>授权决策</b>；读取者<b>身份由谁认定</b>（CLI 的 {@code --reader}，或 pw-web-ui 注入的已认证主体）
 * 是调用方的职责。库不替调用方做身份验证，只回答"给定这个身份，他能不能看 FULL/INTERNAL/SANITIZED"。
 */
public final class AuditAccessPolicy {

    private AuditAccessPolicy() {}

    /**
     * 判定一次审计导出请求。
     *
     * @param role     读取者角色（可为 {@code null}，按 EXTERNAL 处理）
     * @param requested 请求的导出级别（不可为 {@code null}）
     * @return 允许时携带生效级别；拒绝时携带原因
     */
    public static AuditAccessDecision decide(AuditReaderRole role, AuditExposure requested) {
        if (requested == null) {
            throw new IllegalArgumentException("请求的导出级别不能为空");
        }
        AuditReaderRole effectiveRole = role == null ? AuditReaderRole.EXTERNAL : role;
        int allowedRank = effectiveRole.maxExposure().rank();
        int requestedRank = requested.rank();

        if (requestedRank <= allowedRank) {
            return AuditAccessDecision.allow(requested);
        }
        return AuditAccessDecision.deny(
                "请求导出级别 " + requested + " 超出读取者角色 " + effectiveRole + " 的许可上限 "
                        + effectiveRole.maxExposure() + "（读取侧鉴权 fail-closed，越权请求被拒绝）");
    }

    /** 便捷重载：直接吃角色名（未知/空按 EXTERNAL）。 */
    public static AuditAccessDecision decide(String roleName, AuditExposure requested) {
        return decide(AuditReaderRole.parse(roleName), requested);
    }
}

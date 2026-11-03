package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.util.Locale;

/**
 * 审计报告的<b>导出敏感级别</b>（设计 §4.4 / R1·R7 数据最小化）。
 *
 * <p>同一份候选库，按受众不同给出不同脱敏程度的报告：
 * <ul>
 *   <li>{@link #FULL}：完整信息（内部评审/合规自用，含审批人实名、origin 全文、证据路径）；</li>
 *   <li>{@link #INTERNAL}：内部可共享，实名保留，但<b>证据细节里的文件系统路径收敛为文件名</b>
 *       （避免把 {@code C:\Users\alice\...} 这类带用户名/目录的现场带出）；</li>
 *   <li>{@link #SANITIZED}：对外/弱信任场景，审批人确定性假名化、origin 仅留 host、note 与证据引用隐去、
 *       事件链自由文本丢弃（只留状态名）——审计"谁批的、批了什么、还能用多久"仍可读，但认不出具体的人与环境。</li>
 * </ul>
 *
 * <p>脱敏是<b>投影</b>：不改写候选库本身，每次导出按请求的级别现算。
 */
public enum AuditExposure {

    /** 完整信息（默认）。 */
    FULL,
    /** 内部共享：实名保留，剥离证据里的绝对路径。 */
    INTERNAL,
    /** 对外脱敏：审批人假名、origin 仅 host、note/引用隐去、事件链自由文本丢弃。 */
    SANITIZED;

    /** 解析导出级别；缺省（null/空）回退到 {@link #FULL}，不接受未知值。 */
    public static AuditExposure parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知导出级别：" + raw + "（可选 full/internal/sanitized）");
        }
    }

    /** 一句话说明，便于写入报告头，让读报告的人知道他看的是哪一级。 */
    public String describe() {
        switch (this) {
            case FULL:      return "完整（含审批人实名与原环境）";
            case INTERNAL:  return "内部共享（实名，已剥离证据路径）";
            case SANITIZED: return "脱敏（审批人假名、origin 仅 host、已隐去 note/引用）";
            default:        return name();
        }
    }

    /**
     * 敏感级别排序：数值越大越敏感。约定 {@code SANITIZED < INTERNAL < FULL}。
     * 供读取侧鉴权比较"请求级别"与"角色允许上限"使用。
     */
    public int rank() {
        switch (this) {
            case SANITIZED: return 0;
            case INTERNAL:  return 1;
            case FULL:      return 2;
            default:        return 0;
        }
    }
}

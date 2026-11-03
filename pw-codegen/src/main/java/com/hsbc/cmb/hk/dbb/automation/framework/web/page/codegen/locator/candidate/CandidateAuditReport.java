package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 候选库的<b>审计报告</b>（Markdown）：把"这条定位器为什么可信"渲染成可贴进评审文档的东西。
 *
 * <p>审计要回答的三件事，正好对应三块内容：
 * <ol>
 *   <li><b>现在有什么</b>——状态分布（多少条还停在 VALIDATED 没审批，多少条已失效）；</li>
 *   <li><b>每条是什么</b>——候选清单（目标语义、定位符、审批人、绑定的应用版本）；</li>
 *   <li><b>怎么变成现在这样</b>——逐条事件链（谁在什么时候把它推到哪个状态、为什么）。</li>
 * </ol>
 *
 * <p>报告是<b>只读投影</b>：不改状态、不落盘（落盘由 CLI 决定），因此可以随时重跑。
 */
public final class CandidateAuditReport {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private CandidateAuditReport() {}

    /** 渲染整个候选库的报告（默认完整级别）。 */
    public static String toMarkdown(CandidateLibrary library, Path libraryFile, Instant generatedAt) {
        return toMarkdown(library, libraryFile, generatedAt, AuditExposure.FULL);
    }

    /**
     * 渲染整个候选库的报告（指定脱敏级别）。
     *
     * <p>脱敏是<b>投影</b>：不改写候选库，每次按请求的 {@link AuditExposure} 现算。
     * {@link #FULL} 原样；{@link #INTERNAL} 保留实名但剥离证据里的文件系统路径；
     * {@link #SANITIZED} 审批人假名、origin 仅留 host、note/引用隐去、事件链自由文本丢弃。
     */
    public static String toMarkdown(CandidateLibrary library, Path libraryFile, Instant generatedAt,
                                    AuditExposure exposure) {
        AuditRedactor redactor = new AuditRedactor(exposure);
        List<CandidateRecord> records = library.all();
        StringBuilder sb = new StringBuilder();

        sb.append("# 候选定位器审计报告\n\n");
        sb.append("- 生成时间：").append(ts(generatedAt)).append('\n');
        sb.append("- 导出级别：").append(exposure.name()).append("（").append(exposure.describe()).append("）\n");
        sb.append("- 候选库：`").append(libraryFile == null ? "(未指定)" : libraryFile.toAbsolutePath()).append("`\n");
        sb.append("- 候选总数：").append(records.size()).append('\n');
        sb.append("- 状态为 APPROVED：").append(count(records, CandidateState.APPROVED)).append('\n');
        sb.append("- 其中授权当前有效（可回注）：").append(usableCount(records, generatedAt)).append("\n\n");

        sb.append("## 状态分布\n\n");
        sb.append("| 状态 | 数量 | 含义 |\n|---|---:|---|\n");
        Map<CandidateState, Integer> counts = new EnumMap<>(CandidateState.class);
        for (CandidateRecord r : records) {
            counts.merge(r.state(), 1, Integer::sum);
        }
        for (CandidateState state : CandidateState.values()) {
            sb.append("| ").append(state).append(" | ").append(counts.getOrDefault(state, 0))
                    .append(" | ").append(meaning(state)).append(" |\n");
        }
        sb.append('\n');

        sb.append("## 候选清单\n\n");
        if (records.isEmpty()) {
            sb.append("_（空库）_\n\n");
        } else {
            sb.append("| ID | 目标语义 | 定位符 | 状态 | 审批人 | 应用版本 | 记录版本 |\n");
            sb.append("|---|---|---|---|---|---|---:|\n");
            for (CandidateRecord r : records) {
                sb.append("| ").append(cell(r.id()))
                        .append(" | ").append(cell(r.candidate().targetRef()))
                        .append(" | `").append(cell(r.candidate().canonical())).append('`')
                        .append(" | ").append(r.state())
                        .append(" | ").append(cell(redactor.approver(orDash(r.approvedBy()))))
                        .append(" | ").append(cell(orDash(r.approvalAppVersion())))
                        .append(" | v").append(r.version())
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 授权绑定与到期\n\n");
        List<CandidateRecord> approved = records.stream().filter(CandidateRecord::approved).toList();
        if (approved.isEmpty()) {
            sb.append("_（没有处于 APPROVED 的候选）_\n\n");
        } else {
            sb.append("| ID | 目标语义 | 业务后果 | 审批人 | origin | 应用版本 | 到期 | 状态 | 备注 |\n");
            sb.append("|---|---|---|---|---|---|---|---|---|\n");
            for (CandidateRecord r : approved) {
                ApprovalGrant g = r.grant();
                sb.append("| ").append(cell(r.id()))
                        .append(" | ").append(cell(r.candidate().targetRef()))
                        .append(" | ").append(g == null ? "-" : g.riskTier().name())
                        .append(" | ").append(cell(g == null ? redactor.approver(orDash(r.approvedBy()))
                                : String.join(", ", redactor.approverAll(g.approvers()))))
                        .append(" | ").append(cell(g == null ? "-" : redactor.origin(g.origin())))
                        .append(" | ").append(cell(g == null ? orDash(r.approvalAppVersion()) : g.appVersion()))
                        .append(" | ").append(g == null ? "-" : ts(g.expiresAt()))
                        .append(" | ").append(cell(grantStatus(g, generatedAt)))
                        .append(" | ").append(cell(g == null ? "-" : redactor.note(g.note())))
                        .append(" |\n");
            }
            sb.append('\n');
            sb.append("> `有效` 才可能被回注；`已过期` 须重新取证续期；`无授权凭据` 是旧版手工审批，回注一律拒绝。\n\n");
        }

        sb.append("## 事件链\n\n");
        if (records.isEmpty()) {
            sb.append("_（空库）_\n\n");
        } else {
            for (CandidateRecord r : records) {
                sb.append("### ").append(r.id()).append(" · ").append(r.candidate().targetRef())
                        .append(" · ").append(r.state()).append("\n\n");
                sb.append("| 版本 | 状态 | 操作者 | 说明 | 时间 |\n|---|---|---|---|---|\n");
                for (CandidateRecord.Event e : r.history()) {
                    sb.append("| v").append(e.version())
                            .append(" | ").append(e.state())
                            .append(" | ").append(cell(redactor.actor(orDash(e.actor()))))
                            .append(" | ").append(cell(redactor.eventDetail(e.state(), orDash(e.detail()))))
                            .append(" | ").append(ts(e.at()))
                            .append(" |\n");
                }
                sb.append('\n');
            }
        }

        sb.append("---\n\n");
        if (exposure == AuditExposure.SANITIZED) {
            sb.append("> 本报告为<b>脱敏</b>导出：审批人已假名化、origin 仅留 host、note 与证据引用已隐去、")
                    .append("事件链自由文本已丢弃。可对外共享，但仍能核对\"谁批的、批了什么、还能用多久\"。\n\n");
        }
        sb.append("> 审计口径：`VALIDATED` 只代表五条确定性规则通过，**不代表生产可用**；")
                .append("`APPROVED` 也只在**审批绑定的 origin + 应用版本 + 内容摘要**范围内、且在**有效期内**可用。")
                .append("同一目标同一版本存在多条可用授权时回注失败（歧义即失败），不按时间或分数择一。\n");
        return sb.toString();
    }

    private static String meaning(CandidateState state) {
        switch (state) {
            case PROPOSED:  return "已提出，尚未验证";
            case VALIDATED: return "验证通过（可提交审批，仍非生产定位器）";
            case REJECTED:  return "验证未通过，保留证据以便追溯";
            case APPROVED:  return "人工审批已通过（仍需在绑定范围内且未过期才可回注）";
            case RETIRED:   return "已失效，终态，不参与回退";
            default:        return "";
        }
    }

    private static long count(List<CandidateRecord> records, CandidateState state) {
        return records.stream().filter(r -> r.state() == state).count();
    }

    /** 当前真正能被回注的数量：APPROVED + 有凭据 + 未过期。 */
    private static long usableCount(List<CandidateRecord> records, Instant now) {
        return records.stream()
                .filter(CandidateRecord::approved)
                .filter(r -> r.grant() != null && !r.grant().expiredAt(now))
                .count();
    }

    /** 凭据状态：有效 / 已过期 / 无授权凭据，临期单独提示便于提前续期。 */
    private static String grantStatus(ApprovalGrant g, Instant now) {
        if (g == null) {
            return "无授权凭据";
        }
        if (g.expiredAt(now)) {
            return "已过期";
        }
        return g.daysUntilExpiry(now) <= 7 ? "有效（7 天内到期）" : "有效";
    }

    private static String ts(Instant instant) {
        return instant == null ? "-" : TS.format(instant);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** 表格单元转义：竖线与换行会把表格列冲散。 */
    private static String cell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}

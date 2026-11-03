package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 候选库<b>保留/删除策略</b>（设计 §4.4 / R9 最小化审计）。
 *
 * <p>不同状态的候选，过期后多久可以安全清除：
 * <ul>
 *   <li>{@code PROPOSED}：30 天（长期停在"已提出未验证"的，多半是废提议）；</li>
 *   <li>{@code VALIDATED}：180 天（验证过但一直没审批，可能已过时）；</li>
 *   <li>{@code REJECTED}：90 天（被拒的留痕一段时间以便追溯，之后无价值）；</li>
 *   <li>{@code RETIRED}：180 天（已下架，终态）；</li>
 *   <li>{@code APPROVED}：授权<b>过期后再宽限 365 天</b>——宁可多留审计轨迹，也绝不误删仍在有效期内的定位器。</li>
 * </ul>
 *
 * <p>铁律：清除只针对<b>已过期 + 宽限期已过</b>的记录；任何仍可使用（授权未过期）的 APPROVED 记录，
 * 其清除时点永远在未来，因此 {@link CandidateLibrary#purgeExpired} 不会动到它。
 */
public final class RetentionPolicy {

    private final Map<CandidateState, Long> retentionDays;
    private final long approvedGraceDays;

    public RetentionPolicy(Map<CandidateState, Long> retentionDays, long approvedGraceDays) {
        this.retentionDays = new EnumMap<>(Objects.requireNonNull(retentionDays, "retentionDays"));
        this.approvedGraceDays = approvedGraceDays;
    }

    /** 默认策略（见类注释）。 */
    public static RetentionPolicy defaults() {
        Map<CandidateState, Long> d = new EnumMap<>(CandidateState.class);
        d.put(CandidateState.PROPOSED, 30L);
        d.put(CandidateState.VALIDATED, 180L);
        d.put(CandidateState.REJECTED, 90L);
        d.put(CandidateState.APPROVED, 0L); // APPROVED 由"过期 + 宽限"控制，见 deadline
        d.put(CandidateState.RETIRED, 180L);
        return new RetentionPolicy(d, 365L);
    }

    /** 该记录<b>变得可被清除</b>的时点；{@code null} 表示当前不可清除（如 APPROVED 且授权未到期）。 */
    public Instant deadline(CandidateRecord r, Instant now) {
        Objects.requireNonNull(r, "record");
        if (r.state() == CandidateState.APPROVED) {
            Instant base = r.grant() == null ? r.lastEventAt() : r.grant().expiresAt();
            if (base == null) {
                base = r.lastEventAt();
            }
            if (base == null) {
                return null;
            }
            return base.plus(approvedGraceDays, ChronoUnit.DAYS);
        }
        Long days = retentionDays.get(r.state());
        if (days == null) {
            return null;
        }
        Instant since = r.lastEventAt();
        if (since == null) {
            return null;
        }
        return since.plus(days, ChronoUnit.DAYS);
    }

    /** 当前是否已达到清除条件。 */
    public boolean isExpired(CandidateRecord r, Instant now) {
        Instant d = deadline(r, now);
        return d != null && now.isAfter(d);
    }

    public long approvedGraceDays() {
        return approvedGraceDays;
    }

    /** 一段可读的策略描述，供 CLI 干跑时打印。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("PROPOSED≤").append(retentionDays.getOrDefault(CandidateState.PROPOSED, 0L)).append("d, ")
                .append("VALIDATED≤").append(retentionDays.getOrDefault(CandidateState.VALIDATED, 0L)).append("d, ")
                .append("REJECTED≤").append(retentionDays.getOrDefault(CandidateState.REJECTED, 0L)).append("d, ")
                .append("RETIRED≤").append(retentionDays.getOrDefault(CandidateState.RETIRED, 0L)).append("d; ")
                .append("APPROVED=授权过期后再宽限 ").append(approvedGraceDays).append("d");
        return sb.toString();
    }
}

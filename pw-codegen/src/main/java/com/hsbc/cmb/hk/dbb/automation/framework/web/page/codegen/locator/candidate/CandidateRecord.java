package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 候选库中的一条记录：候选 + 当前状态 + 验证结论/证据 + 审批信息 + 版本历史（设计 §4.4）。
 *
 * <p>版本号每次状态变更 +1，且每次变更都追加一条 {@link Event}（谁、为什么、何时），
 * 于是"这条定位器为什么可信"可以完整回溯到审批链，而不是只有一个结果字段。
 *
 * <p>状态变更方法全部是<b>包级私有</b>：只有 {@link CandidateLibrary} 能改状态，外部只能读。
 */
public final class CandidateRecord {

    private final String id;
    private final LocatorCandidate candidate;
    private CandidateState state;
    private CandidateValidator.Validation validation;
    private CandidateEvidence evidence;
    private ApprovalGrant grant;
    /** 旧版落盘里的审批人（无授权凭据时仅用于审计展示，不参与任何回注判断）。 */
    private String legacyApprovedBy;
    private String retireReason;
    private int version;
    private final List<Event> history = new ArrayList<>();

    /** 新建候选（PROPOSED，version=1）。 */
    CandidateRecord(String id, LocatorCandidate candidate, Instant at) {
        this.id = id;
        this.candidate = candidate;
        this.state = CandidateState.PROPOSED;
        this.version = 1;
        this.history.add(new Event(1, CandidateState.PROPOSED, candidate.proposedBy(), "proposed", at));
    }

    /** 从持久化恢复（不做迁移校验，状态以落盘为准）。 */
    CandidateRecord(String id, LocatorCandidate candidate, CandidateState state,
                    CandidateValidator.Validation validation, CandidateEvidence evidence,
                    ApprovalGrant grant, String legacyApprovedBy, String retireReason,
                    int version, List<Event> history) {
        this.id = id;
        this.candidate = candidate;
        this.state = state;
        this.validation = validation;
        this.evidence = evidence;
        this.grant = grant;
        this.legacyApprovedBy = grant == null ? legacyApprovedBy : null;
        this.retireReason = retireReason;
        this.version = version;
        if (history != null) {
            this.history.addAll(history);
        }
    }

    public String id() {
        return id;
    }

    public LocatorCandidate candidate() {
        return candidate;
    }

    public CandidateState state() {
        return state;
    }

    /** 最近一次验证结论（未验证为 null）。 */
    public CandidateValidator.Validation validation() {
        return validation;
    }

    /** 最近一次验证所用证据（未验证为 null）。 */
    public CandidateEvidence evidence() {
        return evidence;
    }

    /**
     * 审批者（仅 APPROVED 且有授权凭据时有值）。
     *
     * <p>多人审批（CRITICAL）时返回首位审批人；完整名单见 {@link #grant()}。
     * 旧库中被手工改成 APPROVED 的记录返回旧字段里记的名字，仅用于审计展示。
     */
    public String approvedBy() {
        return grant == null ? legacyApprovedBy : grant.primaryApprover();
    }

    /**
     * 审批时的目标应用版本（生产取出须与当前版本一致）。
     *
     * <p>历史库中被手工改成 APPROVED、但没有授权凭据的记录，这里返回 {@code null}——
     * 回注出口据此拒用，不会把"没有凭据的审批"当成有效授权。
     */
    public String approvalAppVersion() {
        return grant == null ? null : grant.appVersion();
    }

    /** 授权凭据（未经 {@link ApprovalPolicy} 签发时为 {@code null}）。 */
    public ApprovalGrant grant() {
        return grant;
    }

    /** 是否持有有效授权凭据（不含有效期判断，有效期在回注时按当前时间核对）。 */
    public boolean hasGrant() {
        return grant != null;
    }

    public String retireReason() {
        return retireReason;
    }

    public int version() {
        return version;
    }

    public List<Event> history() {
        return List.copyOf(history);
    }

    /** 最近一次状态变更的时间（事件链的末条）。用于保留策略计算"自上次变更起过了多久"。 */
    public Instant lastEventAt() {
        return history.isEmpty() ? null : history.get(history.size() - 1).at();
    }

    public boolean approved() {
        return state == CandidateState.APPROVED;
    }

    // ------------------------------------------------------------------
    // 状态迁移：仅 CandidateLibrary 可调用
    // ------------------------------------------------------------------

    void markValidated(CandidateValidator.Validation v, CandidateEvidence e, Instant at) {
        this.validation = v;
        this.evidence = e;
        bump(CandidateState.VALIDATED, candidate.proposedBy(), "验证通过（可提交审批，仍非生产定位器）", at);
    }

    void markRejected(CandidateValidator.Validation v, CandidateEvidence e,
                      String detail, String actor, Instant at) {
        this.validation = v;
        this.evidence = e;
        bump(CandidateState.REJECTED, actor == null ? candidate.proposedBy() : actor, detail, at);
    }

    /**
     * 落审批：只接受策略签发的授权凭据。
     *
     * <p>刻意不提供 {@code markApproved(String approver, String appVersion)} 这样的简写：
     * 一旦存在"只给名字就能过"的重载，前面的分级、职责分离、origin 与有效期绑定全部可以被一行调用绕过。
     */
    void markApproved(ApprovalGrant grant, Instant at) {
        if (grant == null) {
            throw new IllegalArgumentException("grant 不可为空：审批必须带可核对的授权凭据");
        }
        this.grant = grant;
        this.legacyApprovedBy = null;
        bump(CandidateState.APPROVED, String.join(", ", grant.approvers()),
                "人工审批通过（risk=" + grant.riskTier()
                        + ", origin=" + grant.origin()
                        + ", appVersion=" + grant.appVersion()
                        + ", digest=" + grant.planDigest()
                        + ", 有效期至 " + grant.expiresAt() + "）", at);
    }

    void markRetired(String reason, String actor, Instant at) {
        this.retireReason = reason;
        bump(CandidateState.RETIRED, actor, "失效：" + reason, at);
    }

    private void bump(CandidateState next, String actor, String detail, Instant at) {
        this.state = next;
        this.version++;
        this.history.add(new Event(this.version, next, actor, detail, at));
    }

    /**
     * 一次状态变更事件（审计链）。
     *
     * @param version 该事件产生的记录版本
     * @param state   变更后的状态
     * @param actor   操作者（提出者 / 审批者 / 系统）
     * @param detail  变更原因
     * @param at      时间（由注入的 Clock 提供，便于测试固定时间）
     */
    public record Event(int version, CandidateState state, String actor, String detail, Instant at) {
    }

    @Override
    public String toString() {
        return id + "[" + state + " v" + version + "] " + candidate;
    }
}

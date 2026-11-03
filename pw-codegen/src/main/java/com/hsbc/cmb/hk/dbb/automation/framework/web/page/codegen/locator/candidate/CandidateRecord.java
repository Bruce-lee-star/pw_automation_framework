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
    private String approvedBy;
    private String approvalAppVersion;
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
                    String approvedBy, String approvalAppVersion, String retireReason,
                    int version, List<Event> history) {
        this.id = id;
        this.candidate = candidate;
        this.state = state;
        this.validation = validation;
        this.evidence = evidence;
        this.approvedBy = approvedBy;
        this.approvalAppVersion = approvalAppVersion;
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

    /** 审批者（仅 APPROVED 后有值）。 */
    public String approvedBy() {
        return approvedBy;
    }

    /** 审批时的目标应用版本（生产取出须与当前版本一致）。 */
    public String approvalAppVersion() {
        return approvalAppVersion;
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

    void markRejected(CandidateValidator.Validation v, CandidateEvidence e, String reason, Instant at) {
        this.validation = v;
        this.evidence = e;
        bump(CandidateState.REJECTED, candidate.proposedBy(), "验证未通过：" + reason, at);
    }

    void markApproved(String approver, String appVersion, Instant at) {
        this.approvedBy = approver;
        this.approvalAppVersion = appVersion;
        bump(CandidateState.APPROVED, approver, "人工审批通过（appVersion=" + appVersion + "）", at);
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

package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;

import java.util.Objects;

/**
 * 一条<b>候选定位器</b>：目标语义 + 具体定位符 + 来源与出处（设计 §4.4 定位器生命周期）。
 *
 * <p>候选是"提议"，不是"结论"：即使 {@link CandidateValidator} 全部通过，也只意味着可以进入审批，
 * 绝不等于生产可用。生产可用由 {@link CandidateLibrary#approve} 显式审批后才成立
 * （{@link CandidateLibrary#productionLocator} 只认 APPROVED）。
 *
 * <p>{@code expectedRole} 是<b>目标语义</b>的锚点：录制/LLM 提出的候选若用 CSS 定位一个"按钮"，
 * 但目标语义是 {@code role=button}，验证期即判不通过——防止"能点到就算对"。
 */
public final class LocatorCandidate {

    private final String targetRef;
    private final LocatorSpec spec;
    private final String expectedRole;
    private final String source;
    private final String evidenceRef;
    private final String proposedBy;

    private LocatorCandidate(String targetRef, LocatorSpec spec, String expectedRole,
                             String source, String evidenceRef, String proposedBy) {
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef 不可为空：候选必须绑定一个目标语义");
        }
        this.targetRef = targetRef.trim();
        this.spec = Objects.requireNonNull(spec, "spec");
        this.expectedRole = blankToNull(expectedRole);
        this.source = blankToNull(source);
        this.evidenceRef = blankToNull(evidenceRef);
        this.proposedBy = blankToNull(proposedBy);
    }

    /** 目标语义引用（如 {@code login.submit} / 元素 refId）。 */
    public String targetRef() {
        return targetRef;
    }

    public LocatorSpec spec() {
        return spec;
    }

    /** 目标语义要求的角色（可空；非空时强制候选必须是同角色的 ROLE 定位）。 */
    public String expectedRole() {
        return expectedRole;
    }

    /** 来源（recorder / agent / manual / semantic …）。 */
    public String source() {
        return source;
    }

    /** 证据出处（trace 文件、快照 id、录制会话 id）。 */
    public String evidenceRef() {
        return evidenceRef;
    }

    public String proposedBy() {
        return proposedBy;
    }

    public String canonical() {
        return spec.canonical();
    }

    public static LocatorCandidate of(String targetRef, LocatorSpec spec) {
        return new LocatorCandidate(targetRef, spec, null, null, null, null);
    }

    public LocatorCandidate withExpectedRole(String role) {
        return new LocatorCandidate(targetRef, spec, role, source, evidenceRef, proposedBy);
    }

    public LocatorCandidate withSource(String source) {
        return new LocatorCandidate(targetRef, spec, expectedRole, source, evidenceRef, proposedBy);
    }

    public LocatorCandidate withEvidenceRef(String evidenceRef) {
        return new LocatorCandidate(targetRef, spec, expectedRole, source, evidenceRef, proposedBy);
    }

    public LocatorCandidate withProposedBy(String proposedBy) {
        return new LocatorCandidate(targetRef, spec, expectedRole, source, evidenceRef, proposedBy);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Override
    public String toString() {
        return targetRef + " ← " + canonical();
    }
}

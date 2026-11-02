package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import java.util.Collections;
import java.util.List;

/**
 * 验证报告（设计 §3.1.2.1）。由 AssetVerifier 产出，回写 AssetRepository 的置信度 / 稳定性（§4.6 量化验收）。
 */
public final class VerificationReport {
    public enum Status { PASS, FAIL, FLAKY }

    private final Status status;
    private final double stability;      // N 次命中率（0..1）
    private final List<VerificationItem> items;
    private final String evidence;       // trace 引用（R9 审计）

    public VerificationReport(Status status, double stability, List<VerificationItem> items, String evidence) {
        this.status = status;
        this.stability = stability;
        this.items = items == null ? Collections.emptyList() : items;
        this.evidence = evidence;
    }

    public Status status() { return status; }
    public double stability() { return stability; }
    public List<VerificationItem> items() { return Collections.unmodifiableList(items); }
    public String evidence() { return evidence; }

    /** 单条联动的验证结果。 */
    public static final class VerificationItem {
        private final String ruleId;
        private final boolean passed;
        private final String detail;

        public VerificationItem(String ruleId, boolean passed, String detail) {
            this.ruleId = ruleId; this.passed = passed; this.detail = detail;
        }
        public String ruleId() { return ruleId; }
        public boolean passed() { return passed; }
        public String detail() { return detail; }
    }
}

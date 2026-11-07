package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多步验证结果聚合（验证驱动最小闭环）。
 *
 * <p>承载每步 {@link SnapshotVerificationResult} 并给出整体结论与跨步计数。不可变。</p>
 */
public final class StepVerificationResult {

    private final List<SnapshotVerificationResult> perStep;
    private final boolean allPassed;
    private final int steps;
    private final int passedSteps;
    private final int checkedExpectations;
    private final int passedExpectations;

    public StepVerificationResult(List<SnapshotVerificationResult> perStep) {
        this.perStep = Collections.unmodifiableList(new ArrayList<>(perStep));
        this.allPassed = perStep.stream().allMatch(SnapshotVerificationResult::allPassed);
        this.steps = perStep.size();
        this.passedSteps = (int) perStep.stream().filter(SnapshotVerificationResult::allPassed).count();
        this.checkedExpectations = perStep.stream().mapToInt(SnapshotVerificationResult::checked).sum();
        this.passedExpectations = perStep.stream().mapToInt(SnapshotVerificationResult::passed).sum();
    }

    public boolean allPassed() { return allPassed; }
    public List<SnapshotVerificationResult> perStep() { return perStep; }
    public int steps() { return steps; }
    public int passedSteps() { return passedSteps; }
    public int checkedExpectations() { return checkedExpectations; }
    public int passedExpectations() { return passedExpectations; }

    /** 跨步汇总的全部失败明细。 */
    public List<AssertionFailure> allFailures() {
        List<AssertionFailure> all = new ArrayList<>();
        for (SnapshotVerificationResult r : perStep) {
            all.addAll(r.failures());
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * 可读 diff（T7：失败输出 diff）：逐步行给出通过结论与后态指纹，并对失败步列出
     * 每条断言的「期望 vs 实际」，便于在重跑 / 幂等跳过后快速定位状态漂移。
     */
    public String diff() {
        StringBuilder sb = new StringBuilder();
        sb.append("step-verification: allPassed=").append(allPassed)
                .append(", steps=").append(steps)
                .append(", passedSteps=").append(passedSteps).append('\n');
        for (int i = 0; i < perStep.size(); i++) {
            SnapshotVerificationResult s = perStep.get(i);
            sb.append("  [").append(i).append("] passed=").append(s.allPassed());
            if (s.actualDigest() != null) {
                sb.append(" actualDigest=").append(s.actualDigest());
            }
            sb.append('\n');
            for (AssertionFailure f : s.failures()) {
                sb.append("      FAIL ").append(f.describe()).append('\n');
            }
        }
        return sb.toString();
    }
}

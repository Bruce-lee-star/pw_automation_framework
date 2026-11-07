package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;

import java.util.Collections;
import java.util.List;

/**
 * 快照校验结果（验证驱动最小构成块）。
 *
 * <p>聚合多条 {@link SnapshotExpectation} 的校验结论：整体是否通过、逐条失败明细与计数。不可变。</p>
 */
public final class SnapshotVerificationResult {

    private final boolean allPassed;
    private final List<AssertionFailure> failures;
    private final int checked;
    private final int passed;
    private final String actualDigest; // 后态快照指纹（用于失败 diff），可能 null

    /** 兼容构造（无后态指纹）。 */
    public SnapshotVerificationResult(boolean allPassed, List<AssertionFailure> failures, int checked, int passed) {
        this(allPassed, failures, checked, passed, null);
    }

    /** 完整构造。 */
    public SnapshotVerificationResult(boolean allPassed, List<AssertionFailure> failures, int checked, int passed, String actualDigest) {
        this.allPassed = allPassed;
        this.failures = Collections.unmodifiableList(List.copyOf(failures));
        this.checked = checked;
        this.passed = passed;
        this.actualDigest = actualDigest;
    }

    public boolean allPassed() { return allPassed; }
    public List<AssertionFailure> failures() { return failures; }
    public int checked() { return checked; }
    public int passed() { return passed; }
    /** 后态快照的稳定结构指纹（T7 失败 diff 用；幂等跳过的步会携带缓存指纹）。 */
    public String actualDigest() { return actualDigest; }

    /** 便捷构造：全部通过（无失败明细）。 */
    public static SnapshotVerificationResult pass(int checked) {
        return pass(checked, null);
    }

    /** 便捷构造：全部通过（携带后态指纹）。 */
    public static SnapshotVerificationResult pass(int checked, String actualDigest) {
        return new SnapshotVerificationResult(true, List.of(), checked, checked, actualDigest);
    }
}

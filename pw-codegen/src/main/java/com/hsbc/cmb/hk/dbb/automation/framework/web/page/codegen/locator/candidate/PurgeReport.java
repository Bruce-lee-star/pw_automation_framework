package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.util.List;
import java.util.Map;

/**
 * {@link CandidateLibrary#purgeExpired} 的结果：这次清除了什么、留下了什么。
 *
 * <p>清除是<b>不可逆</b>的，因此报告把被清除的 id 与按状态计数都带上，便于事后核对"为什么这条没了"。
 */
public final class PurgeReport {

    private final int removedCount;
    private final int retainedCount;
    private final Map<CandidateState, Integer> removedByState;
    private final List<String> removedIds;

    public PurgeReport(int removedCount, int retainedCount,
                       Map<CandidateState, Integer> removedByState, List<String> removedIds) {
        this.removedCount = removedCount;
        this.retainedCount = retainedCount;
        this.removedByState = Map.copyOf(removedByState);
        this.removedIds = List.copyOf(removedIds);
    }

    public int removedCount() {
        return removedCount;
    }

    public int retainedCount() {
        return retainedCount;
    }

    public Map<CandidateState, Integer> removedByState() {
        return removedByState;
    }

    public List<String> removedIds() {
        return removedIds;
    }

    @Override
    public String toString() {
        return "PurgeReport{removed=" + removedCount + ", retained=" + retainedCount
                + ", byState=" + removedByState + ", ids=" + removedIds + '}';
    }
}

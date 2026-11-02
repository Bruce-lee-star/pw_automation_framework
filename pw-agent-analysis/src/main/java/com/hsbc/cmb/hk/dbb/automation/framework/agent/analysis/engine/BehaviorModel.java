package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine;

import java.util.List;

/**
 * 操作行为模型资产（设计 §4.6 ②）。
 *
 * <p>每个元素的合法操作、预期结果类别与业务断言，操作对应需求里的业务步骤 / 验收标准。</p>
 */
public record BehaviorModel(List<BehaviorEntry> entries) {

    public BehaviorModel {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public List<BehaviorEntry> entries() {
        return entries;
    }

    public long incompleteCount() {
        return entries.stream().filter(e -> e.requirementId() == null || e.requirementId().isBlank()).count();
    }
}

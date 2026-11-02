package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine;

import java.util.List;

/**
 * 元素清单资产（设计 §4.6 ①）。
 *
 * <p>页面可交互元素的角色、类型、稳定定位器候选、可见文本、语义用途，每元素回链需求条目。</p>
 */
public record ElementInventory(List<ElementInventoryEntry> entries) {

    public ElementInventory {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public List<ElementInventoryEntry> entries() {
        return entries;
    }

    /** 未映射需求（incomplete）的元素数（用于覆盖度度量）。 */
    public long incompleteCount() {
        return entries.stream().filter(e -> e.requirementId() == null || e.requirementId().isBlank()).count();
    }
}

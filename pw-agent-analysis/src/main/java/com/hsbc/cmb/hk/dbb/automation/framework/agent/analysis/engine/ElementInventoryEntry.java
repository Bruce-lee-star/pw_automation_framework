package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;

/**
 * 元素清单条目（设计 §4.6 ①）。
 *
 * <p>页面可交互元素 + 业务用途 + 需求映射。{@code ref} 已含角色优先定位候选梯队。</p>
 *
 * @param ref          元素引用（定位候选 + 语义角色）
 * @param purpose      业务用途（如 "转账金额录入"）
 * @param requirementId 关联需求 id（需求映射；空串表示 incomplete）
 */
public record ElementInventoryEntry(ElementRef ref, String purpose, String requirementId) {
}

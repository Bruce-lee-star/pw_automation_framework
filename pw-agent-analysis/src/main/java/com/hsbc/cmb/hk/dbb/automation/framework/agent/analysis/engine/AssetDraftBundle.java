package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;

/**
 * 资产草稿包（设计 §4.6 路径 B 产物聚合）。
 *
 * <p>一次分析产出的三类资产（元素清单 / 行为模型 / 联动图谱）+ 溯源 + 需求上下文，
 * 经 {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.AssetValidator}
 * 校验后，由流水线分别落库为 {@code DRAFT} 资产。AI 产出一律视为未验证建议（R6）。</p>
 *
 * @param requirement   需求上下文（需求映射来源）
 * @param inventory     ① 元素清单
 * @param behaviors     ② 操作行为模型
 * @param linkage       ③ 联动图谱（可能为空规则）
 * @param provenance    溯源（生成器 / 模型版本 / 指纹）
 * @param pageRef       来源页面引用
 */
public record AssetDraftBundle(RequirementContext requirement,
                               ElementInventory inventory,
                               BehaviorModel behaviors,
                               LinkageGraph linkage,
                               Provenance provenance,
                               String pageRef) {
}

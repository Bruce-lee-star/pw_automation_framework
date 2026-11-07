package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 资产类型（设计 §4.6 三类产物 + 扩展）。
 *
 * <p>资产层以通用 {@code contentJson} 存储，避免 pw-core 依赖分析域具体模型（零 LLM 耦合）。</p>
 */
public enum AssetType {
    /** ① 元素清单 Element Inventory（§4.6 ①）。 */
    ELEMENT_INVENTORY,
    /** ② 操作行为模型 Behavior Model（§4.6 ②）。 */
    BEHAVIOR_MODEL,
    /** ③ 联动图谱 Linkage Graph（§4.6 ③）。 */
    LINKAGE_GRAPH,
    /** 步骤计划草案 StepPlan（路径 B 产物）。 */
    STEP_PLAN,
    /** 页面对象草案 Page Object（路径 B 产物）。 */
    PAGE_OBJECT,
    /** 路径 C 产物：StepPlan → Feature/Glue/Steps 绑定资产（agent 生成、DRAFT 入库）。 */
    BINDING
}

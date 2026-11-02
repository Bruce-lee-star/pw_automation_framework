package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 资产草稿（设计 §4.6 路径 B 产物）。
 *
 * <p>由分析引擎（AI / 规则 / 录制）产出，进入资产仓库时状态恒为 {@link AssetState#DRAFT}。
 * 内容以 JSON 序列化存储，使 pw-core 资产层与分析域模型解耦（零 LLM 依赖）。</p>
 *
 * @param type          资产类型
 * @param requirementId 关联需求 id（需求映射，可为空串表示无映射 / incomplete）
 * @param contentJson   资产内容（分析域模型序列化结果）
 * @param provenance    溯源
 */
public record AssetDraft(AssetType type, String requirementId, String contentJson, Provenance provenance) {

    public AssetDraft {
        if (requirementId == null) {
            requirementId = "";
        }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 已落库资产（设计 §4.6 版本与溯源）。
 *
 * <p>{@link AssetRepository} 返回的存储形态，带状态、版本、溯源与内容（JSON）。</p>
 *
 * @param id            资产标识（含版本）
 * @param type          资产类型
 * @param requirementId 关联需求 id（需求映射）
 * @param state         当前状态
 * @param provenance    溯源
 * @param contentJson   资产内容（分析域模型序列化）
 * @param createdAt     创建时间（epoch millis）
 * @param updatedAt     最近更新时间（epoch millis）
 */
public record StoredAsset(AssetId id, AssetType type, String requirementId, AssetState state,
                          Provenance provenance, String contentJson, long createdAt, long updatedAt) {

    /** 校验/审批记录（R9）：操作者 + 时间 + 事件。 */
    public record AuditEntry(String actor, AssetStateMachine.Event event, long atEpoch) {}
}

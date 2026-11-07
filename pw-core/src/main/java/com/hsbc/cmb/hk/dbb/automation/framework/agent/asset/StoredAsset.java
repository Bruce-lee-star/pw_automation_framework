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
 * @param report        最近一次验证报告（§4.6 量化验收：回写置信度 / 稳定性）；未验证时为 {@code null}
 * @param createdAt     创建时间（epoch millis）
 * @param updatedAt     最近更新时间（epoch millis）
 * @param approvedDigest 审批绑定摘要（T8）：{@code STEP_PLAN} 资产在审批通过时记录的
 *                       {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan#planDigest()}
 *                       内容指纹；执行链 {@code AssetStepPlanExecutor} 若见到非 {@code null} 值，必须与当前
 *                       内容摘要逐字一致，否则判定「审批后内容被篡改」并拒绝执行（fail-closed）。
 *                       非 {@code STEP_PLAN} 资产、或尚未审批（DRAFT/PENDING_REVIEW）的资产为 {@code null}，
 *                       此时执行器不做绑定校验（保留开发/测试期可直接跑 DRAFT 的既有行为）。
 *                       注意：本摘要是 StepPlan 执行域指纹，与 pw-codegen 中
 *                       {@code LocatorCandidate.planDigestOf()}（定位器回注域）是两套互不相通的体系。
 */
public record StoredAsset(AssetId id, AssetType type, String requirementId, AssetState state,
                          Provenance provenance, String contentJson, VerificationReport report,
                          long createdAt, long updatedAt, String approvedDigest) {

    /** 校验/审批记录（R9）：操作者 + 时间 + 事件。 */
    public record AuditEntry(String actor, AssetStateMachine.Event event, long atEpoch) {}
}

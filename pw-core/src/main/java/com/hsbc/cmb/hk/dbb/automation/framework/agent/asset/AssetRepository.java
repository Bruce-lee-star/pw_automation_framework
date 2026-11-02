package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import java.util.List;

/**
 * 资产仓库契约（设计 §3.1.3 / §4.6 资产层，归属 pw-core）。
 *
 * <p>资产的一等公民存储：版本化、状态机驱动、需求映射检索、溯源。
 * AI/分析链路仅可 {@link #save}（产出 {@link AssetState#DRAFT}），审批与激活须显式人工动作（R6）。</p>
 */
public interface AssetRepository {

    /**
     * 保存草稿资产。状态恒为 {@link AssetState#DRAFT}（AI 永不自动可信，R6）。
     * @param draft 草稿（内容已序列化为 JSON）
     * @return 新资产的标识
     */
    AssetId save(AssetDraft draft);

    /** 提交评审：DRAFT → PENDING_REVIEW。 */
    StoredAsset submit(AssetId id, String actor);

    /** 审批通过：PENDING_REVIEW → APPROVED（责任归属人工，R9）。 */
    StoredAsset approve(AssetId id, String approver);

    /** 激活：APPROVED → ACTIVE（可被回归链路消费）。 */
    StoredAsset activate(AssetId id);

    /** 废弃：任意 → DEPRECATED（需求变更/定位器漂移重验，不自动沿用旧断言）。 */
    StoredAsset deprecate(AssetId id, String reason);

    /** 按需求 id 检索资产（需求→资产覆盖度统计，§4.6）。 */
    List<StoredAsset> queryByRequirement(String requirementId);

    /** 读取资产（含版本）。 */
    StoredAsset get(AssetId id);
}

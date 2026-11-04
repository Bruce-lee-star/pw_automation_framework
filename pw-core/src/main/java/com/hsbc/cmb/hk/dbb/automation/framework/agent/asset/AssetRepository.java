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

    /**
     * 回写验证报告（§4.6 量化验收）：把 {@link AssetVerifier} 产出的 {@link VerificationReport}
     * 落到资产本体（置信度 / 稳定性 / 逐条结论），供回归链路与需求覆盖度统计消费。
     *
     * <p>不产生状态迁移（验证是观察性动作，不改变 DRAFT/ACTIVE 等可信度状态机）；仅更新资产的
     * 报告字段，使其在 {@link #get} 时携带最近一次验证结论。</p>
     *
     * @param id     资产标识
     * @param report 验证报告（非空）
     * @return 携带报告后的资产
     */
    StoredAsset recordVerification(AssetId id, VerificationReport report);
}

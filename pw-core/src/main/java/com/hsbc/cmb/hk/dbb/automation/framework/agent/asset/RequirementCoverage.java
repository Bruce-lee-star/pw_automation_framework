package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 单条需求的验证覆盖度聚合（设计 §4.6「需求→资产覆盖度统计」量化验收）。
 *
 * <p>消费各资产经 {@link AssetRepository#recordVerification} 回写的 {@link VerificationReport}，
 * 汇总该需求下资产的验证结论：资产总数、已验证数、PASS / FLAKY / FAIL 计数与平均稳定性。
 * 由 {@link VerificationCoverageService} 基于 {@link AssetRepository#queryByRequirement} 计算。</p>
 *
 * @param requirementId 需求 id
 * @param assetCount    该需求关联的资产总数（含未验证）
 * @param verifiedCount 已回写验证报告的资产数
 * @param passedCount   PASS 资产数
 * @param flakyCount    FLAKY 资产数
 * @param failedCount   FAIL 资产数
 * @param avgStability  已验证资产的平均稳定性（无验证资产时为 0.0）
 */
public record RequirementCoverage(String requirementId, int assetCount, int verifiedCount,
                                  int passedCount, int flakyCount, int failedCount, double avgStability) {
}

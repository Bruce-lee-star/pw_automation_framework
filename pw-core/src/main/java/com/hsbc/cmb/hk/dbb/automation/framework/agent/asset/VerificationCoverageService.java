package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import java.util.List;

/**
 * 需求覆盖度聚合服务（设计 §4.6「需求→资产覆盖度统计」）。
 *
 * <p>浏览器无关：仅依赖 {@link AssetRepository} 契约。遍历某需求下的全部资产，消费各自回写的
 * {@link VerificationReport}，聚合出 {@link RequirementCoverage}（资产总数 / 已验证数 / PASS·FLAKY·FAIL
 * 计数 / 平均稳定性）。未验证（{@code report == null}）的资产计入总数但不计入已验证与稳定性统计。</p>
 */
public final class VerificationCoverageService {

    private final AssetRepository repo;

    public VerificationCoverageService(AssetRepository repo) {
        this.repo = repo;
    }

    /** 聚合指定需求的验证覆盖度。 */
    public RequirementCoverage forRequirement(String requirementId) {
        List<StoredAsset> assets = repo.queryByRequirement(requirementId);
        int assetCount = assets.size();
        int verified = 0;
        int passed = 0;
        int flaky = 0;
        int failed = 0;
        double stabilitySum = 0.0;
        for (StoredAsset a : assets) {
            VerificationReport r = a.report();
            if (r == null) {
                continue;
            }
            verified++;
            stabilitySum += r.stability();
            switch (r.status()) {
                case PASS -> passed++;
                case FLAKY -> flaky++;
                case FAIL -> failed++;
            }
        }
        double avgStability = verified == 0 ? 0.0 : stabilitySum / verified;
        return new RequirementCoverage(requirementId, assetCount, verified, passed, flaky, failed, avgStability);
    }
}

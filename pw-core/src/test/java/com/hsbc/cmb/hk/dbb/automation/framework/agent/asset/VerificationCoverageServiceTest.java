package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class VerificationCoverageServiceTest {

    @Test
    public void aggregatesVerifiedReportsByRequirement() throws Exception {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        AssetId a1 = repo.save(new AssetDraft(AssetType.STEP_PLAN, "REQ-1", "{}", Provenance.manual("p")));
        AssetId a2 = repo.save(new AssetDraft(AssetType.STEP_PLAN, "REQ-1", "{}", Provenance.manual("p")));
        AssetId a3 = repo.save(new AssetDraft(AssetType.LINKAGE_GRAPH, "REQ-1", "{}", Provenance.manual("p")));
        // 不相关需求
        repo.save(new AssetDraft(AssetType.STEP_PLAN, "REQ-2", "{}", Provenance.manual("p")));

        // REQ-1：a1 PASS(稳定1.0)、a2 FLAKY(稳定0.5)、a3 未验证
        repo.recordVerification(a1, new VerificationReport(VerificationReport.Status.PASS, 1.0, List.of(), "t1"));
        repo.recordVerification(a2, new VerificationReport(VerificationReport.Status.FLAKY, 0.5, List.of(), "t2"));

        RequirementCoverage c = new VerificationCoverageService(repo).forRequirement("REQ-1");
        assertEquals("REQ-1", c.requirementId());
        assertEquals(3, c.assetCount());
        assertEquals(2, c.verifiedCount());
        assertEquals(1, c.passedCount());
        assertEquals(1, c.flakyCount());
        assertEquals(0, c.failedCount());
        assertEquals(0.75, c.avgStability(), 1e-9);

        // REQ-2：资产存在但从未验证
        RequirementCoverage none = new VerificationCoverageService(repo).forRequirement("REQ-2");
        assertEquals(1, none.assetCount());
        assertEquals(0, none.verifiedCount());
        assertEquals(0.0, none.avgStability(), 1e-9);
    }

    @Test
    public void emptyRequirementYieldsZeroCoverage() {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        RequirementCoverage c = new VerificationCoverageService(repo).forRequirement("REQ-MISSING");
        assertEquals("REQ-MISSING", c.requirementId());
        assertEquals(0, c.assetCount());
        assertEquals(0, c.verifiedCount());
        assertEquals(0.0, c.avgStability(), 1e-9);
    }
}

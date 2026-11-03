package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.RuleBasedUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ManualPageObservationProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline.AssetPipelineOrchestrator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline.PipelineResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline.PipelineValidationException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.StaticRequirementSource;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.DeterministicAssetValidator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetState;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.InMemoryAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 资产生产流水线端到端（设计 §4.6 路径 B / §3.1.2）。零浏览器、零 LLM。 */
public class AssetPipelineOrchestratorTest {

    private static ObservedElement elem(String role, String name) {
        return new ObservedElement(role, name, name, true, true,
                List.of(LocatorSpec.role(role, name)), Map.of());
    }

    private AssetPipelineOrchestrator orchestrator(AssetRepository repo, List<ObservedElement> elements) {
        RequirementEntry req = new RequirementEntry(new RequirementId("REQ-101"), "Transfer amount",
                List.of("user must agree to terms"), List.of(), "transfer.feature");
        RequirementContext ctx = new RequirementContext(List.of(req), "transfer.feature");
        PageObservation obs = new PageObservation("https://x/transfer", "p-transfer", elements, "manual");
        return new AssetPipelineOrchestrator(
                new StaticRequirementSource(ctx),
                new ManualPageObservationProvider(obs),
                new RuleBasedUnderstandingEngine(),
                new DeterministicAssetValidator(),
                repo);
    }

    @Test
    public void pipelineSavesThreeDraftAssets() {
        AssetRepository repo = new InMemoryAssetRepository();
        AssetPipelineOrchestrator o = orchestrator(repo, List.of(
                elem("checkbox", "agree"), elem("textbox", "reason"), elem("button", "Submit")));

        PipelineResult result = o.run("transfer.feature", "p-transfer");

        assertEquals(3, result.savedIds().size());
        assertEquals(3, repo.queryByRequirement("REQ-101@1.0").size());
        // AI/规则仅产 DRAFT，永不自动可信（R6）
        assertTrue(repo.queryByRequirement("REQ-101@1.0").stream()
                .allMatch(a -> a.state() == AssetState.DRAFT));
        assertTrue(result.report().passed());
    }

    @Test
    public void validationFailureFailClosedNoAssetPromoted() {
        AssetRepository repo = new InMemoryAssetRepository();
        // 两个元素共享 role+name → 主定位符重复 → 校验失败 → 失败关闭，不落库
        AssetPipelineOrchestrator o = orchestrator(repo, List.of(elem("textbox", "reason"), elem("textbox", "reason")));

        boolean threw = false;
        try {
            o.run("transfer.feature", "p-transfer");
        } catch (PipelineValidationException e) {
            threw = true;
            assertTrue(e.report().summary().contains("Duplicate") || !e.report().passed());
        }
        assertTrue("expected PipelineValidationException", threw);
        // 失败关闭：仓库为空，无任何资产被提升为可信
        assertEquals(0, repo.queryByRequirement("REQ-101@1.0").size());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.RuleBasedUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ManualPageObservationProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline.AssetPipelineOrchestrator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline.PipelineResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.StaticRequirementSource;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.DeterministicAssetValidator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetState;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.JsonFileAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 证明分析流水线（§4.6 路径 B）可无缝接入 {@link JsonFileAssetRepository}：
 * 三类 DRAFT 资产落盘为「资产本体 JSON + 元数据索引」，进程重启后状态与内容完整重建。
 */
public class JsonBackedPipelineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

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
    public void pipelinePersistsToJsonRepoAndReloadsAcrossRestart() throws Exception {
        Path root = tmp.newFolder().toPath();
        JsonFileAssetRepository repo = new JsonFileAssetRepository(root);
        AssetPipelineOrchestrator o = orchestrator(repo, List.of(
                elem("checkbox", "agree"), elem("textbox", "reason"), elem("button", "Submit")));

        PipelineResult result = o.run("transfer.feature", "p-transfer");

        assertEquals(3, result.savedIds().size());
        assertTrue("index file on disk", Files.exists(root.resolve("_index.json")));
        assertTrue("asset body files on disk",
                Files.list(root.resolve("assets")).anyMatch(p -> p.toString().endsWith(".json")));
        assertEquals(3, repo.queryByRequirement("REQ-101@1.0").size());
        assertTrue(repo.queryByRequirement("REQ-101@1.0").stream()
                .allMatch(a -> a.state() == AssetState.DRAFT));

        // 重启：新实例加载同一目录，资产与内容完整重建
        JsonFileAssetRepository reloaded = new JsonFileAssetRepository(root);
        List<StoredAsset> reloadedAssets = reloaded.queryByRequirement("REQ-101@1.0");
        assertEquals(3, reloadedAssets.size());
        assertTrue(reloadedAssets.stream().allMatch(a -> a.state() == AssetState.DRAFT));

        StoredAsset linkage = reloadedAssets.stream()
                .filter(a -> a.type() == AssetType.LINKAGE_GRAPH).findFirst().orElseThrow();
        // content 嵌套对象落盘后仍为合法 JSON 且携带业务字段
        assertTrue("linkage content survived reload", linkage.contentJson().contains("REQ-101"));
    }
}

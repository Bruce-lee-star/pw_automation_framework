package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetState;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.InMemoryAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 路径 C 闭环流水线契约测试：给定已验证 StepPlan，一条命令产出三件套并落库；退出码 0/1/2 有契约。
 */
public class StepPlanCodegenPipelineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PLAN_JSON = """
            {
              "schemaVersion": 1,
              "planId": "demo-login",
              "steps": [
                {"action":"NAVIGATE","params":{"url":"https://example.com/login"}},
                {"action":"FILL","target":"css=#user","params":{"value":"alice"}},
                {"action":"CLICK","target":"css=#submit"},
                {"action":"ASSERT_VISIBLE","target":"css=#home"}
              ]
            }
            """;

    @Test
    public void generates_three_artifacts_and_persists_draft() throws Exception {
        StepPlan plan = StepPlan.parse(PLAN_JSON);
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        Path featureDir = tmp.newFolder("features").toPath();
        Path glueDir = tmp.newFolder("glue").toPath();

        StepPlanCodegenPipeline.Result r = StepPlanCodegenPipeline.run(plan, featureDir, glueDir, repo);

        assertEquals("退出码应为 0", 0, r.exitCode);
        assertNotNull(r.assetId);

        StoredAsset asset = repo.get(r.assetId);
        assertNotNull("资产应已落库", asset);
        assertEquals(AssetType.BINDING, asset.type());
        assertEquals(AssetState.DRAFT, asset.state());

        assertTrue(Files.exists(featureDir.resolve(r.artifacts.featureFileName)));
        assertTrue(Files.exists(glueDir.resolve(r.artifacts.glueFileName)));
        assertTrue(Files.exists(glueDir.resolve(r.artifacts.stepsFileName)));

        String glue = r.artifacts.glueContent;
        String steps = r.artifacts.stepsContent;
        assertTrue("Glue 委托 navigateTo", glue.contains("steps.navigateTo(url)"));
        assertTrue("Steps 委托 sink.navigateTo", steps.contains("sink.navigateTo(url)"));
        assertTrue("Steps 委托 sink.fillWith", steps.contains("sink.fillWith(target, value)"));
        assertTrue("资产内容标记编译委托 CI", asset.contentJson().contains("PENDING_CI"));
    }

    @Test
    public void conflict_with_handwritten_glue_returns_exit_2() throws Exception {
        StepPlan plan = StepPlan.parse(PLAN_JSON);
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        Path featureDir = tmp.newFolder("features").toPath();
        Path glueDir = tmp.newFolder("glue").toPath();

        Files.writeString(glueDir.resolve("DemoLoginGlue.java"),
                "package x;\npublic class DemoLoginGlue {}\n", StandardCharsets.UTF_8);

        StepPlanCodegenPipeline.Result r = StepPlanCodegenPipeline.run(plan, featureDir, glueDir, repo);

        assertEquals("手写文件冲突应返回 2", 2, r.exitCode);
    }

    @Test
    public void empty_plan_returns_exit_1() {
        StepPlan plan = StepPlan.parse(
                "{\"schemaVersion\":1,\"planId\":\"empty\",\"steps\":[]}");
        InMemoryAssetRepository repo = new InMemoryAssetRepository();

        StepPlanCodegenPipeline.Result r = StepPlanCodegenPipeline.run(
                plan, tmp.getRoot().toPath(), tmp.getRoot().toPath(), repo);

        assertEquals("空计划应返回 1", 1, r.exitCode);
    }
}

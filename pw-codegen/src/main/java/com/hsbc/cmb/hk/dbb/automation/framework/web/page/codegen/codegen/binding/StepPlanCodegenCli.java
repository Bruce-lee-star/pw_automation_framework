package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.InMemoryAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.JsonFileAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径 C 单命令入口：已验证 StepPlan（JSON）→ 三件套产物 + 资产库落库，并以语义化退出码结束。
 *
 * <pre>
 *   java ...StepPlanCodegenCli --plan plan.json --feature-dir out/features --glue-dir out/glue
 *        [--asset-dir repo] [--requirement-id REQ-1]
 * </pre>
 *
 * 退出码：{@code 0} 成功；{@code 1} 计划非法/读取失败；{@code 2} 冲突或一致性失败。
 * 未指定 {@code --asset-dir} 时落内存仓库（dry-run，不持久化）。
 */
public final class StepPlanCodegenCli {

    public static void main(String[] args) {
        String planFile = null;
        String featureDir = null;
        String glueDir = null;
        String assetDir = null;
        String requirementId = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--plan" -> planFile = args[++i];
                case "--feature-dir" -> featureDir = args[++i];
                case "--glue-dir" -> glueDir = args[++i];
                case "--asset-dir" -> assetDir = args[++i];
                case "--requirement-id" -> requirementId = args[++i];
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (planFile == null || featureDir == null || glueDir == null) {
            System.err.println("用法: StepPlanCodegenCli --plan <plan.json> "
                    + "--feature-dir <dir> --glue-dir <dir> [--asset-dir <dir>] [--requirement-id <id>]");
            System.exit(2);
        }
        try {
            String json = Files.readString(Path.of(planFile), StandardCharsets.UTF_8);
            StepPlan plan = StepPlan.parse(json);
            AssetRepository repo = (assetDir == null)
                    ? new InMemoryAssetRepository()
                    : new JsonFileAssetRepository(Path.of(assetDir));
            StepPlanCodegenPipeline.Result r = StepPlanCodegenPipeline.run(
                    plan, Path.of(featureDir), Path.of(glueDir), repo, requirementId);
            System.out.println(r.message);
            if (r.assetId != null) {
                System.out.println("ASSET_ID=" + r.assetId);
            }
            System.exit(r.exitCode);
        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            System.exit(StepPlanCodegenPipeline.EXIT_PLAN_INVALID);
        }
    }

    private StepPlanCodegenCli() {
    }
}

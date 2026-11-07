package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetDraft;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenConflictException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFileWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路径 C 闭环流水线：已验证 {@link StepPlan} → 三件套产物 → 资产库（DRAFT）。
 *
 * <p>阶段：① 生成（复用 {@link FeatureGlueGenerator} + 配套 Steps 类）；② 落盘（带生成标记冲突守卫，
 * 手写文件零覆盖）；③ 本地一致性校验（Glue 委托 ↔ Steps 方法签名，真实门禁）；
 * ④ 落库（{@link AssetRepository#save} 恒 DRAFT，R6 不自动可信）。</p>
 *
 * <p>按 I-02 决策：<b>编译与冒烟不在本流水线伪造</b>，明确标记委托 CI 真实执行；Checkstyle 由构建期负责。
 * 资产内容以结构化 JSON 记录 {@code compile/smoke = PENDING_CI}，便于回归链路与需求覆盖度统计消费。</p>
 *
 * <p>语义化退出码：{@code 0} 成功（生成+落库）；{@code 1} 计划非法（生成/校验失败）；
 * {@code 2} 冲突或一致性失败（手写文件冲突 / Glue-Steps 不一致）。</p>
 */
public final class StepPlanCodegenPipeline {

    public static final int EXIT_OK = 0;
    public static final int EXIT_PLAN_INVALID = 1;
    public static final int EXIT_CONFLICT = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern GLUE_CALL = Pattern.compile("steps\\.(\\w+)\\(([^)]*)\\)");
    private static final Pattern STEPS_METHOD = Pattern.compile("public void (\\w+)\\(([^)]*)\\)");

    private StepPlanCodegenPipeline() {
    }

    public static final class Result {
        public final int exitCode;
        public final String message;
        public final StepPlanCodegenAdapter.StepPlanCodegenResult artifacts;
        public final AssetId assetId;

        private Result(int exitCode, String message,
                      StepPlanCodegenAdapter.StepPlanCodegenResult artifacts, AssetId assetId) {
            this.exitCode = exitCode;
            this.message = message;
            this.artifacts = artifacts;
            this.assetId = assetId;
        }

        public static Result ok(AssetId assetId, StepPlanCodegenAdapter.StepPlanCodegenResult artifacts) {
            return new Result(EXIT_OK, "OK", artifacts, assetId);
        }

        public static Result fail(int exitCode, String message) {
            return new Result(exitCode, message, null, null);
        }
    }

    public static Result run(StepPlan plan, Path featureDir, Path glueDir, AssetRepository repo) {
        return run(plan, featureDir, glueDir, repo, null);
    }

    public static Result run(StepPlan plan, Path featureDir, Path glueDir,
                            AssetRepository repo, String requirementId) {
        Objects.requireNonNull(featureDir, "featureDir");
        Objects.requireNonNull(glueDir, "glueDir");
        Objects.requireNonNull(repo, "repo");

        StepPlanCodegenAdapter.StepPlanCodegenResult art;
        try {
            art = StepPlanCodegenAdapter.toArtifacts(plan, StepPlanCodegenAdapter.DEFAULT_PACKAGE);
        } catch (IllegalArgumentException e) {
            return Result.fail(EXIT_PLAN_INVALID, "计划非法: " + e.getMessage());
        }

        Map<Path, String> outputs = new LinkedHashMap<>();
        outputs.put(featureDir.resolve(art.featureFileName), art.featureContent);
        outputs.put(glueDir.resolve(art.glueFileName), art.glueContent);
        outputs.put(glueDir.resolve(art.stepsFileName), art.stepsContent);
        try {
            CodegenFileWriter.writeAllGenerated(outputs);
        } catch (CodegenConflictException e) {
            return Result.fail(EXIT_CONFLICT, "手写文件冲突，拒绝覆盖: " + e.getMessage());
        } catch (IOException e) {
            return Result.fail(EXIT_CONFLICT, "产物写入失败: " + e.getMessage());
        }

        List<String> inconsistencies = verifyGlueStepsConsistency(art);
        if (!inconsistencies.isEmpty()) {
            return Result.fail(EXIT_CONFLICT, "Glue/Steps 不一致: " + String.join("; ", inconsistencies));
        }

        AssetDraft draft = buildDraft(plan, art, requirementId);
        AssetId id = repo.save(draft);
        return Result.ok(id, art);
    }

    /** 真实本地门禁：Glue 里每个 {@code steps.xxx(...)} 调用必须在 Steps 类有同签名方法。 */
    private static List<String> verifyGlueStepsConsistency(StepPlanCodegenAdapter.StepPlanCodegenResult art) {
        Set<String> called = new java.util.HashSet<>();
        Matcher mc = GLUE_CALL.matcher(art.glueContent);
        while (mc.find()) {
            called.add(mc.group(1) + arity(mc.group(2)));
        }
        Set<String> defined = new java.util.HashSet<>();
        Matcher ms = STEPS_METHOD.matcher(art.stepsContent);
        while (ms.find()) {
            defined.add(ms.group(1) + arity(ms.group(2)));
        }
        List<String> bad = new ArrayList<>();
        for (String c : called) {
            if (!defined.contains(c)) {
                bad.add(c);
            }
        }
        return bad;
    }

    private static String arity(String args) {
        String t = args.trim();
        if (t.isEmpty()) {
            return "()";
        }
        return "(" + t.split(",").length + ")";
    }

    private static AssetDraft buildDraft(StepPlan plan, StepPlanCodegenAdapter.StepPlanCodegenResult art,
                                        String requirementId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("featureName", art.featureName);
        content.put("planDigest", art.planDigest);
        content.put("feature", art.featureContent);
        content.put("glue", art.glueContent);
        content.put("steps", art.stepsContent);
        content.put("compile", "PENDING_CI");
        content.put("smoke", "PENDING_CI");
        content.put("checkstyle", "DELEGATED_TO_BUILD");
        String json;
        try {
            json = MAPPER.writeValueAsString(content);
        } catch (IOException e) {
            throw new IllegalStateException("序列化资产内容失败", e);
        }
        Provenance prov = Provenance.manual("stepplan:" + art.planDigest);
        String req = (requirementId == null) ? "" : requirementId;
        return new AssetDraft(AssetType.BINDING, req, json, prov);
    }
}

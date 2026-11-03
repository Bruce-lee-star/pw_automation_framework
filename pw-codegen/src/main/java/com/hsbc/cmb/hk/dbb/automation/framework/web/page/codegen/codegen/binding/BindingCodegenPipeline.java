package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFileWriter;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.FeatureGluePlan;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.FeatureGlueResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * P2 流水线入口：<b>计划 JSON → 生成 → 冲突守卫 → 落盘</b>。
 *
 * <h2>与录制流水线的关系</h2>
 * <p>录制（{@code MultiPageCodegen}）与绑定（本类）是两条独立入口，产物在代码仓里汇合：
 * 录制的 Steps 类是本类生成 Glue 的委托目标。两者都不互相调用，避免"要生成 Glue 就得先开浏览器"
 * 这种荒谬依赖。
 *
 * <h2>验证边界（不伪造结论）</h2>
 * <p>本类只保证：输入计划合法（{@link FeatureGlueGenerator} 三重校验通过）、产物按生成标记安全落盘。
 * <b>编译与冒烟不在这里做</b>——生成物落在目标仓后由该仓的 {@code mvn test} / CI 执行，
 * 本模块不会用"假编译"给出通过结论。
 *
 * <p>典型用法（需求评审通过后由构建脚本或 CLI 调用）：
 * <pre>{@code
 * BindingCodegenResult r = BindingCodegenPipeline.run(
 *         Path.of("requirements/bindings/role_element_login.json"),
 *         Path.of("src/test/resources/features"),
 *         Path.of("src/test/java/com/example/glue"));
 * }</pre>
 */
public final class BindingCodegenPipeline {

    private BindingCodegenPipeline() {}

    /** 读计划文件 → 生成 → 落盘。 */
    public static BindingCodegenResult run(Path planFile, Path featureDir, Path glueDir) throws IOException {
        Objects.requireNonNull(planFile, "planFile");
        return run(BindingPlanJson.load(planFile), featureDir, glueDir);
    }

    /** JSON 文本 → 生成 → 落盘（供已经持有计划内容的调用方 / 测试使用）。 */
    public static BindingCodegenResult runJson(String planJson, Path featureDir, Path glueDir) throws IOException {
        return run(BindingPlanJson.parse(planJson), featureDir, glueDir);
    }

    /**
     * 已构建的计划 → 生成 → 落盘。
     *
     * @throws IllegalArgumentException 计划不合法（占位符/类型/字面值对不上，或缺 Steps 类）
     * @throws com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenConflictException
     *         目标文件是手写代码（无生成标记）；此时两份产物都不写入
     */
    public static BindingCodegenResult run(FeatureGluePlan plan, Path featureDir, Path glueDir) throws IOException {
        Objects.requireNonNull(featureDir, "featureDir");
        Objects.requireNonNull(glueDir, "glueDir");
        FeatureGlueResult generated = FeatureGlueGenerator.generate(plan);
        Map<Path, String> outputs = generated.outputs(featureDir, glueDir);
        // 先全量校验生成标记、再落盘：Feature 可覆盖而 Glue 是手写时，不会出现半截写入。
        CodegenFileWriter.writeAllGenerated(outputs);
        return new BindingCodegenResult(
                featureDir.resolve(generated.featureFileName),
                glueDir.resolve(generated.glueFileName));
    }
}

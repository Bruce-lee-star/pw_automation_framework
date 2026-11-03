package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.BindingStep;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.FeatureGluePlan;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.ParamType;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.Scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 绑定计划的 JSON 序列化：让「需求 → 代码绑定」的输入可以像资产一样<b>落库、评审、版本化</b>。
 *
 * <p>为什么计划不直接写在 Java 代码里：Feature/Glue 由业务需求驱动，需求会改、要评审、要 diff。
 * 一份可提交进仓的 JSON 计划，比"改一行 Java 再重新生成"更容易走评审流程。
 *
 * <p>JSON 形态（字段顺序无关，未列出的字段可省）：
 * <pre>{@code
 * {
 *   "version": 1,
 *   "featureName": "Role element login",
 *   "featureFileName": "role_element_login.feature",   // 可选，默认由 featureName 派生
 *   "gluePackage": "com.example.glue",
 *   "glueClass": "RoleElementLoginGlue",               // 可选，默认由 featureName 派生
 *   "stepsPackage": "com.example.steps",
 *   "stepsClass": "LoginSteps",
 *   "stepsVar": "steps",                               // 可选，默认 steps
 *   "tags": ["regression", "login"],
 *   "scenarios": [{
 *     "name": "Maker logs on",
 *     "steps": [
 *       { "keyword": "Given", "phrase": "I open the login page", "delegate": "step1" },
 *       { "keyword": "When", "phrase": "I log on as {string} with role {string}",
 *         "params": [{"name": "username", "type": "string"}, {"name": "role", "type": "string"}],
 *         "args": ["alice", "maker"],
 *         "delegate": "logon", "forward": true }
 *     ]
 *   }]
 * }
 * }</pre>
 *
 * <p>解析只做<b>结构映射</b>，业务校验（占位符/类型/字面值是否对得上）仍由
 * {@link FeatureGlueGenerator#generate} 统一负责，避免两处规则漂移。
 */
public final class BindingPlanJson {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private BindingPlanJson() {}

    /**
     * 解析 JSON 文本为计划。
     *
     * @throws IllegalArgumentException JSON 非法或缺少必填字段（{@code featureName} / scenario 名 / 步骤关键字）
     */
    public static FeatureGluePlan parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("绑定计划 JSON 不可为空");
        }
        PlanDto dto;
        try {
            dto = GSON.fromJson(json, PlanDto.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("绑定计划 JSON 解析失败: " + e.getMessage(), e);
        }
        return toPlan(dto);
    }

    /** 读取计划文件（UTF-8）。 */
    public static FeatureGluePlan load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 计划 → JSON 文本（便于把需求侧绑定关系提交进仓 / 做评审 diff）。 */
    public static String toJson(FeatureGluePlan plan) {
        PlanDto dto = toDto(plan);
        return GSON.toJson(dto);
    }

    /** 计划 → JSON 文件（UTF-8，父目录自动创建）。 */
    public static void write(FeatureGluePlan plan, Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toJson(plan), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // JSON ↔ 模型
    // ------------------------------------------------------------------

    private static FeatureGluePlan toPlan(PlanDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("绑定计划 JSON 为空对象");
        }
        if (isBlank(dto.featureName)) {
            throw new IllegalArgumentException("绑定计划缺少 featureName");
        }
        FeatureGluePlan plan = FeatureGluePlan.named(dto.featureName);
        if (!isBlank(dto.featureFileName)) {
            plan.featureFileName(dto.featureFileName);
        }
        if (!isBlank(dto.gluePackage)) {
            plan.gluePackage(dto.gluePackage);
        }
        if (!isBlank(dto.glueClass)) {
            plan.glueClass(dto.glueClass);
        }
        if (!isBlank(dto.stepsClass)) {
            plan.steps(dto.stepsPackage, dto.stepsClass);
        }
        if (!isBlank(dto.stepsVar)) {
            plan.stepsVar(dto.stepsVar);
        }
        if (dto.tags != null && !dto.tags.isEmpty()) {
            plan.tags(dto.tags.toArray(new String[0]));
        }
        if (dto.scenarios != null) {
            for (ScenarioDto sd : dto.scenarios) {
                if (sd == null || isBlank(sd.name)) {
                    throw new IllegalArgumentException("绑定计划存在缺少 name 的 scenario");
                }
                Scenario scenario = Scenario.named(sd.name);
                if (sd.steps != null) {
                    for (StepDto st : sd.steps) {
                        scenario.step(toStep(st));
                    }
                }
                plan.scenario(scenario);
            }
        }
        return plan;
    }

    private static BindingStep toStep(StepDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("绑定计划存在空步骤");
        }
        if (isBlank(dto.keyword)) {
            throw new IllegalArgumentException("步骤 [" + dto.phrase + "] 缺少 keyword（Given/When/Then/And/But）");
        }
        BindingStep step = BindingStep.of(dto.keyword, dto.phrase);
        if (dto.params != null) {
            for (ParamDto p : dto.params) {
                if (p == null || isBlank(p.name)) {
                    throw new IllegalArgumentException("步骤 [" + dto.phrase + "] 存在缺少 name 的 param");
                }
                step.param(p.name, ParamType.parse(p.type));
            }
        }
        if (dto.args != null && !dto.args.isEmpty()) {
            step.args(dto.args.toArray(new String[0]));
        }
        if (!isBlank(dto.delegate)) {
            if (Boolean.TRUE.equals(dto.forward)) {
                step.delegateForwarding(dto.delegate);
            } else {
                step.delegate(dto.delegate);
            }
        }
        return step;
    }

    private static PlanDto toDto(FeatureGluePlan plan) {
        PlanDto dto = new PlanDto();
        dto.version = 1;
        dto.featureName = plan.featureName();
        dto.featureFileName = plan.featureFileName();
        dto.gluePackage = plan.gluePackageName();
        dto.glueClass = plan.glueClassName();
        dto.stepsPackage = plan.stepsPackageName();
        dto.stepsClass = plan.stepsClassName();
        dto.stepsVar = plan.stepsVar();
        dto.tags = new ArrayList<>(plan.tagNames());
        dto.scenarios = new ArrayList<>();
        for (Scenario s : plan.scenarioList()) {
            ScenarioDto sd = new ScenarioDto();
            sd.name = s.name();
            sd.steps = new ArrayList<>();
            for (BindingStep st : s.stepList()) {
                StepDto stDto = new StepDto();
                stDto.keyword = st.keyword();
                stDto.phrase = st.phrase();
                stDto.delegate = st.delegateMethod();
                stDto.forward = st.forwardsParams();
                stDto.params = new ArrayList<>();
                st.paramList().forEach(p -> {
                    ParamDto pd = new ParamDto();
                    pd.name = p.name();
                    pd.type = p.type().cucumberName();
                    stDto.params.add(pd);
                });
                stDto.args = new ArrayList<>(st.argList());
                sd.steps.add(stDto);
            }
            dto.scenarios.add(sd);
        }
        return dto;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // --- Gson DTO（字段名即 JSON 键；未出现的键保持 null，便于区分"未指定"与"显式空"） ---

    private static final class PlanDto {
        int version;
        String featureName;
        String featureFileName;
        String gluePackage;
        String glueClass;
        String stepsPackage;
        String stepsClass;
        String stepsVar;
        List<String> tags;
        List<ScenarioDto> scenarios;
    }

    private static final class ScenarioDto {
        String name;
        List<StepDto> steps;
    }

    private static final class StepDto {
        String keyword;
        String phrase;
        List<ParamDto> params;
        List<String> args;
        String delegate;
        Boolean forward;
    }

    private static final class ParamDto {
        String name;
        String type;
    }
}

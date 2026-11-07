package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.BindingStep;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.FeatureGluePlan;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.FeatureGlueResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.ParamType;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding.FeatureGlueGenerator.Scenario;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 路径 C 适配器：把已验证的 {@link StepPlan} 转成「{@code .feature} + 极薄 Glue + 配套 Steps 类」三件套，
 * 守 {@code Glue 只委托} 契约——Steps 类不含定位器/业务分支，仅声明与受控原语一一对应的方法，
 * 经 {@code StepPlanActionSink} 把执行推迟到测试运行时注入（编译/冒烟委托 CI 真实执行，I-02 红线）。
 *
 * <p>映射以每个动作的<b>规范参数名顺序</b>为单一事实源，同时驱动 Glue 的 {@code delegateForwarding}
 * 透传与 Steps 类的方法签名，确保两者始终一致（流水线另做 Glue/Steps 一致性校验）。</p>
 */
public final class StepPlanCodegenAdapter {

    public static final String DEFAULT_PACKAGE = "com.hsbc.cmb.hk.dbb.automation.generated.agent";
    public static final String SINK_PACKAGE =
            "com.hsbc.cmb.hk.dbb.automation.framework.agent.execution";

    private StepPlanCodegenAdapter() {
    }

    public static StepPlanCodegenResult toArtifacts(StepPlan plan) {
        return toArtifacts(plan, DEFAULT_PACKAGE);
    }

    public static StepPlanCodegenResult toArtifacts(StepPlan plan, String packageName) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(packageName, "packageName");
        List<Command> cmds = plan.steps();
        if (cmds.isEmpty()) {
            throw new IllegalArgumentException("StepPlan 不含任何步骤，无法生成 BDD 资产");
        }
        String featureName = plan.planId();
        String glueClassName = pascal(featureName) + "Glue";
        String stepsClassName = pascal(featureName) + "Steps";

        FeatureGluePlan fplan = FeatureGluePlan.named(featureName)
                .gluePackage(packageName)
                .glueClass(glueClassName)
                .steps(packageName, stepsClassName);
        Scenario scenario = Scenario.named(featureName + " scenario");
        for (Command cmd : cmds) {
            scenario.step(toBindingStep(cmd));
        }
        fplan.scenario(scenario);

        FeatureGlueResult gen = FeatureGlueGenerator.generate(fplan);
        String stepsSource = generateStepsClass(packageName, stepsClassName, cmds);
        return new StepPlanCodegenResult(
                featureName,
                gen.featureFileName, gen.featureContent,
                gen.glueFileName, gen.glueContent,
                stepsClassName + ".java", stepsSource,
                plan.planDigest());
    }

    private static BindingStep toBindingStep(Command cmd) {
        StepMapping m = mappingOf(cmd);
        BindingStep bs = switch (m.keyword) {
            case "Given" -> BindingStep.given(m.phrase);
            case "When" -> BindingStep.when(m.phrase);
            default -> BindingStep.then(m.phrase);
        };
        for (String p : m.paramNames) {
            bs.param(p, ParamType.STRING);
        }
        for (String a : m.literalArgs) {
            bs.args(a);
        }
        if (m.paramNames.isEmpty()) {
            bs.delegate(m.delegate);
        } else {
            bs.delegateForwarding(m.delegate);
        }
        return bs;
    }

    private record StepMapping(String keyword, String phrase, List<String> paramNames,
                               List<String> literalArgs, String delegate) {
    }

    private static StepMapping mappingOf(Command cmd) {
        Command.ActionType a = cmd.action();
        LocatorSpec target = cmd.target();
        var params = cmd.params();
        return switch (a) {
            case NAVIGATE -> new StepMapping("Given", "I navigate to {string}",
                    List.of("url"), List.of(str(params.get("url"))), "navigateTo");
            case CLICK -> new StepMapping("When", "I click {string}",
                    List.of("target"), List.of(locatorArg(target)), "clickOn");
            case FILL -> new StepMapping("When", "I fill {string} with {string}",
                    List.of("target", "value"),
                    List.of(locatorArg(target), str(params.get("value"))), "fillWith");
            case SELECT -> new StepMapping("When", "I select {string} with option {string}",
                    List.of("target", "option"),
                    List.of(locatorArg(target), str(params.get("option"))), "selectIn");
            case CHECK -> new StepMapping("When", "I check {string}",
                    List.of("target"), List.of(locatorArg(target)), "check");
            case UNCHECK -> new StepMapping("When", "I uncheck {string}",
                    List.of("target"), List.of(locatorArg(target)), "uncheck");
            case WAIT -> new StepMapping("When", "I wait for {string}",
                    List.of("target"), List.of(locatorArg(target)), "waitFor");
            case ASSERT_VISIBLE -> new StepMapping("Then", "I expect {string} to be visible",
                    List.of("target"), List.of(locatorArg(target)), "expectVisible");
            case ASSERT_TEXT -> new StepMapping("Then", "I expect {string} to have text",
                    List.of("target"), List.of(locatorArg(target)), "expectText");
            case SWITCH_TO_POPUP -> new StepMapping("When", "I switch to the popup window",
                    List.of(), List.of(), "switchToPopup");
        };
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String locatorArg(LocatorSpec t) {
        return t == null ? "page" : t.canonical();
    }

    private static String generateStepsClass(String pkg, String className, List<Command> cmds) {
        Set<String> emitted = new java.util.HashSet<>();
        StringBuilder methods = new StringBuilder();
        for (Command cmd : cmds) {
            StepMapping m = mappingOf(cmd);
            if (!emitted.add(m.delegate)) {
                continue;
            }
            String params = joinTyped(m.paramNames);
            methods.append("    public void ").append(m.delegate).append("(").append(params).append(") {\n");
            methods.append("        sink.").append(m.delegate)
                    .append("(").append(String.join(", ", m.paramNames)).append(");\n");
            methods.append("    }\n\n");
        }
        return new StringBuilder()
                .append("// GENERATED BY pw-agent StepPlanCodegenAdapter (路径 C 闭环)\n")
                .append("package ").append(pkg).append(";\n\n")
                .append("import ").append(SINK_PACKAGE).append(".StepPlanActionSink;\n")
                .append("import ").append(SINK_PACKAGE).append(".StepPlanActionSinks;\n\n")
                .append("/**\n")
                .append(" * 路径 C 生成的 Steps 类：由 agent StepPlan 转换，委托 StepPlanActionSink 执行。\n")
                .append(" * 不含定位器与业务分支；执行由测试运行时经 StepPlanActionSinks 注入的 sink 提供（CI/agent 装配）。\n")
                .append(" */\n")
                .append("public class ").append(className).append(" {\n\n")
                .append("    private final StepPlanActionSink sink = StepPlanActionSinks.requireCurrent();\n\n")
                .append(methods)
                .append("}\n")
                .toString();
    }

    private static String joinTyped(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("String ").append(names.get(i));
        }
        return sb.toString();
    }

    private static String pascal(String s) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        String r = sb.toString();
        return r.isEmpty() ? "Generated" : r;
    }

    /** 三件套产物 + 溯源指纹（供落库与一致性校验）。 */
    public static final class StepPlanCodegenResult {
        public final String featureName;
        public final String featureFileName;
        public final String featureContent;
        public final String glueFileName;
        public final String glueContent;
        public final String stepsFileName;
        public final String stepsContent;
        public final String planDigest;

        StepPlanCodegenResult(String featureName, String featureFileName, String featureContent,
                              String glueFileName, String glueContent, String stepsFileName,
                              String stepsContent, String planDigest) {
            this.featureName = featureName;
            this.featureFileName = featureFileName;
            this.featureContent = featureContent;
            this.glueFileName = glueFileName;
            this.glueContent = glueContent;
            this.stepsFileName = stepsFileName;
            this.stepsContent = stepsContent;
            this.planDigest = planDigest;
        }
    }
}

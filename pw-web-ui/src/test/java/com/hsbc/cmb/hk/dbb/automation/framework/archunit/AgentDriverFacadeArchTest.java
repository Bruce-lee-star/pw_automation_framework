package com.hsbc.cmb.hk.dbb.automation.framework.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * T10 结构门禁：固化「单一执行门面」。
 *
 * <p>光靠单测无法阻止后人再写一条 acquire → 引擎 → release 的第二编排路径，故用 ArchUnit
 * 把两条硬约束钉死：① pw-web-ui 的 agent 包里，只有 {@code AgentDriver} 可以直接驱动
 * {@code StepDriver} / {@code StepVerifier}；② 三个既有兼容入口必须依赖门面（而不是各自实现）。</p>
 *
 * <p>与 {@link AgentRuntimeDependencyTest}（T5 依赖方向）、{@link AgentWebDependencyTest}
 * （R6 零浏览器依赖）互补，三者共同保证「执行链只走一个入口、一个组合根」。</p>
 */
public class AgentDriverFacadeArchTest {

    private static final String WEB_AGENT_PKG = "..framework.web.agent..";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(
                    "com.hsbc.cmb.hk.dbb.automation.framework.agent",
                    "com.hsbc.cmb.hk.dbb.automation.framework.web");

    /** 引擎 StepDriver 只允许门面调用；兼容入口不得再自行编排执行。 */
    @Test
    public void stepDriverIsOnlyOrchestratedByFacade() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(WEB_AGENT_PKG)
                .and().haveSimpleNameNotStartingWith("AgentDriver")
                .should().dependOnClassesThat().haveSimpleName("StepDriver");
        rule.check(CLASSES);
    }

    /** 验证器 StepVerifier 同样只允许门面调用（幂等 seam 不得在多处被绕过）。 */
    @Test
    public void stepVerifierIsOnlyOrchestratedByFacade() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(WEB_AGENT_PKG)
                .and().haveSimpleNameNotStartingWith("AgentDriver")
                .should().dependOnClassesThat().haveSimpleName("StepVerifier");
        rule.check(CLASSES);
    }

    /** 兼容 runner 必须委托门面，而不是保留另一套执行实现。 */
    @Test
    public void legacyRunnerDependsOnFacade() {
        ArchRule rule = classes()
                .that().haveSimpleName("StepPlanRunner")
                .should().dependOnClassesThat().haveSimpleName("AgentDriver");
        rule.check(CLASSES);
    }

    /** 生产组合根必须经门面装配，保证真实仓库/靶场/运行时只被创建一次。 */
    @Test
    public void wiringDependsOnFacade() {
        ArchRule rule = classes()
                .that().haveSimpleName("AssetVerificationWiring")
                .should().dependOnClassesThat().haveSimpleName("AgentDriver");
        rule.check(CLASSES);
    }
}

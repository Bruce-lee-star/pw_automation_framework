package com.hsbc.cmb.hk.dbb.automation.framework.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * T5 门禁：Agent 运行时组合根的<b>依赖方向</b>与孤岛消除的结构保证。
 *
 * <p>本测试在 classpath 同时含 agent 与 web 两树的 pw-web-ui 侧执行，与既有
 * {@link AgentWebDependencyTest}（R6 红线）互补：后者约束整个 {@code framework.agent..}
 * 零 web 依赖，本测试进一步固化 T5 引入的 {@code agent.runtime} 抽象层，以及
 * 「执行链只能经组合根访问业务链组件」这一结构约束。</p>
 */
public class AgentRuntimeDependencyTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(
                    "com.hsbc.cmb.hk.dbb.automation.framework.agent",
                    "com.hsbc.cmb.hk.dbb.automation.framework.web",
                    "com.hsbc.cmb.hk.dbb.automation.framework.common.route");

    /** 组合根抽象层不得反向依赖任何业务链实现包（含 Playwright）。 */
    @Test
    public void runtimeAbstractionMustNotDependOnBusinessChain() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..framework.agent.runtime..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..framework.web..",
                        "..framework.route..",
                        "..framework.web.agent..",
                        "com.microsoft.playwright..");
        rule.check(CLASSES);
    }

    /** 组合根抽象层不得触碰具体浏览器 / 路由 / 会话 / 闸门 / 监控实现类。 */
    @Test
    public void runtimeAbstractionMustNotReferenceConcreteComponents() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..framework.agent.runtime..")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("Playwright")
                .orShould().dependOnClassesThat().haveSimpleName("ConcurrencyGate")
                .orShould().dependOnClassesThat().haveSimpleName("SessionManager")
                .orShould().dependOnClassesThat().haveSimpleName("SessionStore")
                .orShould().dependOnClassesThat().haveSimpleName("RouteEngine")
                .orShould().dependOnClassesThat().haveSimpleName("MonitorSink");
        rule.check(CLASSES);
    }

    /**
     * 执行链编排类不得绕过组合根直接调用业务链静态门面——孤岛必须有且仅有一个接线点。
     * 适配器 {@code WebAgentRuntimeContextProvider}（含其嵌套适配器类）是唯一例外。
     * 注意：按 <b>全限定名正则</b> 排除而非 simple name —— ArchUnit 中嵌套类的 simple name
     * 不含外部类前缀，用 haveSimpleNameNotStartingWith 会误伤适配器内部类；
     * haveNameNotMatching 作用于 {@code JavaClass#getName()}（FQN，嵌套类带 {@code $}），
     * 故一条正则即可同时排除外部类与其全部嵌套类。
     */
    @Test
    public void executionChainMustNotBypassRuntimeContext() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..framework.web.agent..")
                .and().haveNameNotMatching(
                        ".*\\.WebAgentRuntimeContextProvider(\\$.*)?")
                .should().dependOnClassesThat().haveSimpleName("ConcurrencyGate")
                .orShould().dependOnClassesThat().haveSimpleName("SessionManager")
                .orShould().dependOnClassesThat().haveSimpleName("SessionStore")
                .orShould().dependOnClassesThat().haveSimpleName("RouteLifecycleRegistry");
        rule.check(CLASSES);
    }

    /** 业务链适配器不得反向被 agent 抽象层引用（依赖方向恒为 web → core）。 */
    @Test
    public void adapterMustNotBeReferencedByCoreRuntime() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..framework.agent..")
                .should().dependOnClassesThat().haveSimpleName("WebAgentRuntimeContextProvider");
        rule.check(CLASSES);
    }
}

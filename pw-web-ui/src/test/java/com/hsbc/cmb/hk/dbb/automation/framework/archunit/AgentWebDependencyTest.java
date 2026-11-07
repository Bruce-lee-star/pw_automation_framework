package com.hsbc.cmb.hk.dbb.automation.framework.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * R6 红线门禁：agent 执行底座（framework.agent..）零 web 实现依赖。
 *
 * <p>依赖方向恒为 web → reports → core；agent 通道只定义类型化契约（snapshot/execute），
 * 真正的 Playwright 驱动在 pw-web-ui 实现，agent 不得反向依赖 web（否则 agent 与具体浏览器实现耦合，
 * 失去可测试性与运行期零 LLM 保证）。本测试在 classpath 含 agent + web 两树的 pw-web-ui 侧执行。</p>
 */
public class AgentWebDependencyTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(
                    "com.hsbc.cmb.hk.dbb.automation.framework.agent",
                    "com.hsbc.cmb.hk.dbb.automation.framework.web");

    @Test
    public void agentMustNotDependOnWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..framework.agent..")
                .should().dependOnClassesThat().resideInAPackage("..framework.web..");
        rule.check(CLASSES);
    }
}

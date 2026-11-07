package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorRoleVocabulary;
import com.hsbc.cmb.hk.dbb.automation.framework.web.agent.LocatorResolver;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T11 结构门禁：定位表示与角色映射的依赖方向必须保持单向。
 *
 * <p>行为一致性由 {@link LocatorSpecGetByRoleContractTest} 逐项对账；这里锁的是
 * 「谁可以依赖谁」，防止有人把第二套词表/翻译重新塞回某一侧而绕过契约。</p>
 */
public class LocatorRoleMappingArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(
                    "com.hsbc.cmb.hk.dbb.automation.framework.agent.channel",
                    "com.hsbc.cmb.hk.dbb.automation.framework.web.agent",
                    "com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator");

    @Test
    public void coreLocatorRepresentationStaysBrowserFree() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..framework.agent.channel..")
                .should().dependOnClassesThat().resideInAnyPackage("com.microsoft.playwright..")
                .check(CLASSES);
    }

    @Test
    public void bothSidesDependOnTheSingleCoreVocabulary() {
        assertTrue("codegen 侧 AriaRoles 必须委托 core 唯一规范",
                dependsOn(AriaRoles.class, LocatorRoleVocabulary.class));
        assertTrue("执行侧 LocatorResolver 必须委托 core 唯一规范",
                dependsOn(LocatorResolver.class, LocatorRoleVocabulary.class));
    }

    @Test
    public void executionSideDoesNotDependOnCodegen() {
        JavaClass resolver = CLASSES.get(LocatorResolver.class.getName());
        assertFalse("pw-web-ui 不得反向依赖 codegen（依赖方向必须单向）",
                resolver.getDirectDependenciesFromSelf().stream()
                        .map(d -> d.getTargetClass().getName())
                        .anyMatch(name -> name.contains(".web.page.codegen")));
    }

    @Test
    public void codegenAdapterDoesNotOwnPlaywrightEnumReflection() {
        JavaClass adapter = CLASSES.get(AriaRoles.class.getName());
        assertFalse("AriaRoles 不得再反射 AriaRole.values() 自建第二份白名单",
                adapter.getDirectDependenciesFromSelf().stream()
                        .map(d -> d.getTargetClass().getName())
                        .anyMatch(name -> name.startsWith("com.microsoft.playwright")));
    }

    private static boolean dependsOn(Class<?> from, Class<?> target) {
        JavaClass source = CLASSES.get(from.getName());
        for (Dependency d : source.getDirectDependenciesFromSelf()) {
            if (d.getTargetClass().getName().equals(target.getName())) {
                return true;
            }
        }
        return false;
    }
}

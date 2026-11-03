package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构门禁（B）：靶场浏览器通道 {@link PlaywrightChannel} 只能被 {@code web.agent} 包内
 * 构造/引用，杜绝用生产 {@code Page} 裸包通道绕过隔离靶场（R4/R8 运行期落点）。
 *
 * <p>配合 {@link PlaywrightChannel} 构造器收窄为包内可见，把"分析/重放只在靶场"从约定
 * 升级为结构保证；任何回潮（其他包直接引用 PlaywrightChannel）都会使本测试失败。</p>
 *
 * <p>采用纯 JUnit 4 + {@link ClassFileImporter} 程序化执行，仅依赖父 POM 已提供的
 * {@code archunit} 与 {@code junit}（见 {@code CodegenDecouplingArchTest}）。</p>
 */
public class RouteTestBedArchTest {

    private static final String WEB_PKG = "com.hsbc.cmb.hk.dbb.automation.framework.web";
    private static final String WEB_AGENT_PKG = "..framework.web.agent..";

    @Test
    public void playwrightChannelOnlyAccessibleWithinWebAgent() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(WEB_PKG);
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(WEB_AGENT_PKG)
                .should().accessClassesThat().haveSimpleName("PlaywrightChannel");
        rule.check(classes);
    }
}

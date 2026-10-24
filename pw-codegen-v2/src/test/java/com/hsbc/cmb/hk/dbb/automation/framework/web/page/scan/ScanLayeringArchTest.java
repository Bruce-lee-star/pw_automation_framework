package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构分层门禁：固化 scan 包的企业级分层（model / engine / spi）依赖方向，防回归。
 *
 * <p><b>分层契约</b>：
 * <ul>
 *   <li>{@code model} 是叶子层，仅依赖外部 {@code web.page.element.RoleElement}；
 *       禁止反向依赖 {@code engine} / {@code spi}（避免出现引擎→model 的循环或封装破坏）。</li>
 *   <li>{@code engine} 可依赖 {@code model}，禁止依赖 {@code spi}
 *       （{@code engine ↔ generate} 本就是真实循环依赖，generate 已并入 engine 同包，
 *        若 engine 再依赖 spi 会制造包级循环）。</li>
 *   <li>{@code spi} 仅可依赖 {@code engine} / {@code model} 的 public API
 *       （{@code RoleElementPickerBridge} 经 ServiceLoader 注入，只引用 engine 公开面）。</li>
 * </ul>
 * 依赖方向固定为 {@code model ← engine ← spi}，无环。本测试把"三条非法反向依赖边"锁死，
 * 任何破坏该方向的改动都会让测试失败，把回归挡在架构期（与 WEB-P1-1 的 ArchUnit L7 守护同源）。</p>
 *
 * <p><b>实现说明</b>：采用仓库已验证稳定的 {@code noClasses().that().resideInAPackage().should()
 * .dependOnClassesThat()} 否定规则写法（同 {@code RouteArchitectureTest}），避免 {@code LayeredArchitecture}
 * 流式 API 在本 archunit 版本下的符号解析差异。</p>
 *
 * <p>@apiNote 仅测试期执行；不影响运行时依赖。</p>
 */
public class ScanLayeringArchTest {

    private static final JavaClasses SCAN_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan");

    /** model 叶子层禁止依赖 engine（方向必须 model ← engine，而非反向）。 */
    @Test
    public void modelMustNotDependOnEngine() {
        noClasses().that().resideInAPackage("..web.page.scan.model..")
                .should().dependOnClassesThat().resideInAPackage("..web.page.scan.engine..")
                .check(SCAN_CLASSES);
    }

    /** model 叶子层禁止依赖 spi。 */
    @Test
    public void modelMustNotDependOnSpi() {
        noClasses().that().resideInAPackage("..web.page.scan.model..")
                .should().dependOnClassesThat().resideInAPackage("..web.page.scan.spi..")
                .check(SCAN_CLASSES);
    }

    /** engine 禁止依赖 spi（否则制造包级循环）。 */
    @Test
    public void engineMustNotDependOnSpi() {
        noClasses().that().resideInAPackage("..web.page.scan.engine..")
                .should().dependOnClassesThat().resideInAPackage("..web.page.scan.spi..")
                .check(SCAN_CLASSES);
    }
}

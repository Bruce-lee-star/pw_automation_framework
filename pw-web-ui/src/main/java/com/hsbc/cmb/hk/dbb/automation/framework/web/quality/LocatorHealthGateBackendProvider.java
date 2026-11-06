package com.hsbc.cmb.hk.dbb.automation.framework.web.quality;

import io.cucumber.core.backend.Backend;
import io.cucumber.core.backend.BackendProviderService;
import io.cucumber.core.backend.Container;
import io.cucumber.core.backend.Lookup;

import java.util.function.Supplier;

/**
 * 定位健康度门禁的 Cucumber {@code BackendProviderService} 实现（SPI 零配置入口）。
 *
 * <p><b>如何被发现</b>：本类由随 {@code pw-web-ui} 发布的
 * {@code META-INF/services/io.cucumber.core.backend.BackendProviderService} 注册；
 * Cucumber 运行时会 {@code ServiceLoader.load(BackendProviderService.class)} 并<b>逐个</b>
 * {@code create(...)}（已核对 7.34.4 字节码：{@code BackendServiceLoader.loadBackends} 收集为列表），
 * 因此本适配器与 {@code cucumber-java} 的 Java backend <b>并存</b>，互不替换。
 *
 * <p><b>为什么不走 Plugin SPI</b>：Cucumber 的 JUnit4 runner
 * （{@code @RunWith(Cucumber.class)}）<b>不做</b> plugin 的 SPI 发现，业务必须把插件写进
 * {@code @CucumberOptions}——那是"要改代码"的配置，违背门禁零配置的初衷。Backend 的 SPI
 * 由 runtime 自己加载，与 runner 形态无关。
 *
 * <p><b>失败影响面</b>：{@code create} 只构建对象、不做任何 IO；钩子装配的异常在
 * {@link LocatorHealthGateBackend#loadGlue} 内 fail-open 吞掉并记 ERROR，
 * 故本 SPI 的存在<b>不会</b>给未使用该门禁的工程带来运行风险。
 */
public final class LocatorHealthGateBackendProvider implements BackendProviderService {

    @Override
    public Backend create(Lookup lookup, Container container, Supplier<ClassLoader> classLoaderSupplier) {
        return new LocatorHealthGateBackend();
    }
}

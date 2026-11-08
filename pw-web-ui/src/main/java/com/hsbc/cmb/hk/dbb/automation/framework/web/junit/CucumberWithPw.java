package com.hsbc.cmb.hk.dbb.automation.framework.web.junit;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber.PwReportPlugin;
import com.hsbc.cmb.hk.dbb.automation.framework.report.step.PwReportObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkCucumberEventListener;
import io.cucumber.junit.Cucumber;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 框架自带的 Cucumber JUnit4 Runner——业务把 {@code @RunWith(Cucumber.class)} 换成
 * {@code @RunWith(CucumberWithPw.class)}，即可<b>零配置</b>获得框架的插件与 ObjectFactory。
 *
 * <p><b>为什么用委托而不是继承</b>：{@code io.cucumber.junit.Cucumber} 是 {@code final}
 * （字节码已核实），无法继承；而 Serenity 的做法（{@code CucumberWithSerenity}）是<b>整份复制</b>
 * cucumber-junit 的 runner 成 {@code CucumberSerenityBaseRunner} 放进 {@code io.cucumber.junit}
 * 包以访问包级机制——那样与 Cucumber 版本强耦合，升级即需重新同步。本类只做<b>委托</b>：
 * 先把框架默认值写进 Cucumber 的 options 来源，再构造真实 runner，不复制任何内部实现。</p>
 *
 * <p><b>为什么不走 {@code /cucumber.properties}</b>：该配置是 classpath 上的<b>单例资源</b>，
 * 任意一个第三方 jar 也带一份就会把我们的屏蔽掉，且是静默失效（框架插件全丢、无任何报错）。
 * 改用本 Runner 在构造时把 {@code cucumber.plugin} / {@code cucumber.object-factory}
 * 两个<b>系统属性</b>补齐（Cucumber 7 的 options 装配会读取系统属性，见
 * {@code io.cucumber.junit.Cucumber} 构造流程），不存在被屏蔽的问题，也不依赖资源查找顺序。</p>
 *
 * <p><b>幂等与尊重用户配置</b>：插件是追加（缺哪个补哪个，重复调用不会重复注册）；
 * ObjectFactory 仅在业务<b>未指定</b>时才填默认值——业务显式指定了自己的实现时以业务为准。</p>
 */
public final class CucumberWithPw extends Runner implements Filterable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CucumberWithPw.class);

    /** Cucumber 插件系统属性（逗号分隔）。 */
    public static final String PLUGIN_PROPERTY = "cucumber.plugin";

    /** Cucumber ObjectFactory 系统属性（单值）。 */
    public static final String OBJECT_FACTORY_PROPERTY = "cucumber.object-factory";

    /** 框架必需插件：生命周期事件适配器 + 报告插件。 */
    public static final List<String> FRAMEWORK_PLUGINS = List.of(
            FrameworkCucumberEventListener.class.getName(),
            PwReportPlugin.class.getName());

    /** 框架默认 ObjectFactory（{@code @Step} 拦截 / 步骤树采集）。 */
    public static final String FRAMEWORK_OBJECT_FACTORY = PwReportObjectFactory.class.getName();

    private final Cucumber delegate;

    public CucumberWithPw(Class<?> testClass) throws InitializationError {
        ensureFrameworkDefaults();
        this.delegate = new Cucumber(testClass);
    }

    /**
     * 把框架默认插件 / ObjectFactory 补进 Cucumber 的系统属性（幂等）。
     *
     * <p>必须在委托 runner 构造<b>之前</b>调用：{@code io.cucumber.junit.Cucumber} 在构造函数里
     * 就完成 RuntimeOptions 装配，之后再补就晚了。synchronized 是必要的——并行执行下多个
     * runner 类可能被同时实例化，而 System 属性是 JVM 全局共享状态。</p>
     */
    public static synchronized void ensureFrameworkDefaults() {
        Set<String> plugins = new LinkedHashSet<>(split(ConfigReader.getProperty(PLUGIN_PROPERTY)));
        if (plugins.addAll(FRAMEWORK_PLUGINS)) {
            System.setProperty(PLUGIN_PROPERTY, String.join(",", plugins));
        }
        String factory = ConfigReader.getProperty(OBJECT_FACTORY_PROPERTY);
        if (factory == null || factory.isBlank()) {
            System.setProperty(OBJECT_FACTORY_PROPERTY, FRAMEWORK_OBJECT_FACTORY);
        }
        //  features 根目录：framework.features.root 决定 Cucumber 去哪里找 .feature 文件。
        //  通过 cucumber.options 注入 --features，业务无需在 @CucumberOptions 里硬编码路径；
        //  仅当业务未显式用 cucumber.options 指定 --features 时才注入，尊重其显式配置。
        String featuresRoot = ConfigReader.resolve(ConfigRegistry.WEB_FRAMEWORK_FEATURES_ROOT.key(), null);
        if (featuresRoot != null && !featuresRoot.isBlank()) {
            String cucumberOpts = ConfigReader.getProperty("cucumber.options");
            if (cucumberOpts == null || !cucumberOpts.contains("--features")) {
                String injected = ((cucumberOpts == null || cucumberOpts.isBlank()) ? "" : cucumberOpts + " ")
                        + "--features " + featuresRoot.trim();
                System.setProperty("cucumber.options", injected);
                LOGGER.debug("framework.features.root -> cucumber.options --features {}", featuresRoot.trim());
            }
        }
    }

    @Override
    public Description getDescription() {
        return delegate.getDescription();
    }

    @Override
    public void run(RunNotifier notifier) {
        delegate.run(notifier);
    }

    @Override
    public void filter(Filter filter) throws NoTestsRemainException {
        ((Filterable) delegate).filter(filter);
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : Arrays.asList(raw.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.junit;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionVerdict;
import com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber.PwReportPlugin;
import com.hsbc.cmb.hk.dbb.automation.framework.report.step.PwReportObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkCucumberEventListener;
import io.cucumber.junit.Cucumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p><b>MONITOR 断言失败的 case 判红投影（不加任何 hook）</b>：{@link #run(RunNotifier)}
 * 用 {@link MonitorVerdictNotifier} 包装 notifier。当 MONITOR 断言失败在 glue 步骤方法
 * 执行期间未能抛出（例如最后一步内完全没有框架业务操作），Cucumber 会先把
 * {@code TestCaseFinished} 的 {@link io.cucumber.plugin.event.Result} 按 PASSED 构造完毕；
 * 该对象是 {@code final} 且字段全 {@code final}，{@code TestCaseState} 接口也只有只读方法，
 * 事件层无法改写。框架改在 <b>JUnit 结果投影层</b>补一刀：
 * {@code FeatureRunner.runChild} 以 {@code try/finally} 包裹 {@code child.run(notifier)}，
 * 因此 {@code fireTestFinished} 一定在 Cucumber 派发完 {@code TestCaseFinished} 之后、
 * 同一线程被调用；此处消费 {@link RouteAssertionVerdict} 并补发
 * {@code fireTestFailure}，case 即判红。</p>
 *
 * <p>该路径不新增 Cucumber hook、不打断 {@code TestCaseFinished} 派发链，
 * afterHooks / 报告插件 / {@code JUnitReporter} / 框架场景清理全部照常执行；
 * 失败仍按 {@code BrowserContext} 归属消费、判决按线程隔离，不终止 JVM、
 * 不中断整个 run、不牵连并行 case。</p>
 *
 * <p><b>使用约束</b>：只有使用本 Runner（{@code @RunWith(CucumberWithPw.class)}）的
 * 运行器具备该投影能力；仍用 {@code @RunWith(Cucumber.class)} 的自定义 runner 需迁移一行注解。</p>
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
        delegate.run(new MonitorVerdictNotifier(Objects.requireNonNull(notifier, "notifier")));
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

    /**
     * 把「Cucumber 已按 PASSED 定案、但框架登记了 MONITOR 断言失败」的 case 在 JUnit 层判红。
     *
     * <p><b>必须整体委托</b>：{@code RunNotifier} 的监听器列表是实例私有字段，若只覆写少数
     * {@code fire*} 方法，未覆写的通知会打到本包装对象自己的空列表上，导致 run / suite 级
     * 事件与 surefire 的收集全部丢失。故所有 public 方法逐一转发。</p>
     *
     * <p><b>时机</b>：{@code FeatureRunner.runChild} 用 {@code try/finally} 包裹
     * {@code child.run(notifier)}，{@code fireTestFinished} 必在 Cucumber 派发完
     * {@code TestCaseFinished} 之后同线程执行——此时 {@code RouteAssertionVerdict} 已登记，
     * 而 {@code JUnitReporter} 已结束本 case 的 failure 收集。补发的
     * {@code fireTestFailure} 位于 {@code fireTestFinished} 之前，符合 JUnit 契约。</p>
     *
     * <p><b>并行</b>：{@code failed} 以 {@code Description}（case 唯一标识）为键，
     * 判决本身按线程隔离；{@code RunNotifier} 包装对象无可变 case 维度状态泄漏。</p>
     */
    static final class MonitorVerdictNotifier extends RunNotifier {

        private final RunNotifier delegate;
        /** 本 case 已由 Cucumber / 其它插件报过 failure，用于防重复补发。 */
        private final Set<Description> failed = ConcurrentHashMap.newKeySet();

        MonitorVerdictNotifier(RunNotifier delegate) {
            this.delegate = delegate;
        }

        @Override
        public void addListener(RunListener listener) {
            delegate.addListener(listener);
        }

        @Override
        public void removeListener(RunListener listener) {
            delegate.removeListener(listener);
        }

        @Override
        public void addFirstListener(RunListener listener) {
            delegate.addFirstListener(listener);
        }

        @Override
        public void fireTestRunStarted(Description description) {
            delegate.fireTestRunStarted(description);
        }

        @Override
        public void fireTestRunFinished(Result result) {
            delegate.fireTestRunFinished(result);
        }

        @Override
        public void fireTestSuiteStarted(Description description) {
            delegate.fireTestSuiteStarted(description);
        }

        @Override
        public void fireTestSuiteFinished(Description description) {
            delegate.fireTestSuiteFinished(description);
        }

        @Override
        public void fireTestStarted(Description description) {
            // 双保险：即便 FrameworkCucumberEventListener 未被装载，也不把上一条 case 的判决带过来。
            RouteAssertionVerdict.clear();
            delegate.fireTestStarted(description);
        }

        @Override
        public void fireTestFailure(Failure failure) {
            if (failure != null && failure.getDescription() != null) {
                failed.add(failure.getDescription());
            }
            delegate.fireTestFailure(failure);
        }

        @Override
        public void fireTestAssumptionFailed(Failure failure) {
            delegate.fireTestAssumptionFailed(failure);
        }

        @Override
        public void fireTestIgnored(Description description) {
            delegate.fireTestIgnored(description);
        }

        @Override
        public void fireTestFinished(Description description) {
            boolean alreadyFailed = description != null && failed.remove(description);
            AssertionError verdict = RouteAssertionVerdict.takeAndClear();
            if (verdict != null && !alreadyFailed && description != null) {
                LOGGER.error("MONITOR assertion failure settled after Cucumber result was fixed"
                        + " - failing case via JUnit notifier: {}", description.getDisplayName());
                delegate.fireTestFailure(new Failure(description, verdict));
            }
            delegate.fireTestFinished(description);
        }

        @Override
        public void pleaseStop() {
            delegate.pleaseStop();
        }
    }
}

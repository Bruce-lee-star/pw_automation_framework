package com.hsbc.cmb.hk.dbb.automation.framework.report.step;

import io.cucumber.core.backend.ObjectFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Cucumber ObjectFactory SPI 实现——平移 {@code SerenityObjectFactory} 的最小充分子集
 * （方案 v3 §16.4.4）：实例化 glue + {@code @Steps} 字段注入。
 *
 * <p>与 Serenity 的差异（有意）：<b>不复制</b> @Managed/webdriver 注入部分——本库的页面
 * 对象走 {@code PageObjectFactory} + {@code @AutoBrowser}，不经 Serenity DI。
 *
 * <p>注册：{@code META-INF/services/io.cucumber.core.backend.ObjectFactory}（随 jar）。
 * 与 serenity-cucumber 双 SPI 并存时，由消费方以
 * {@code cucumber.object-factory=…PwReportObjectFactory} 属性显式选中
 * （coexistence 窗口，见迁移方案 §16.5）。
 */
public final class PwReportObjectFactory implements ObjectFactory {

    private final Map<Class<?>, Object> instances = new ConcurrentHashMap<>();
    private final Map<Class<?>, Supplier<?>> suppliers = new ConcurrentHashMap<>();

    @Override
    public void start() {
        // 无需启动熔断：@Step/@Steps 为框架自有、唯一提供者，无第三方同 FQN 副本。
    }

    @Override
    public void stop() {
        // 仅清理实例。<b>不得在此 finishRun()</b>：Cucumber JUnit4 下 ObjectFactory.start()/stop()
        // 是<b>每场景（per-pickle）</b>调用（实测 buildBackendWorlds 在 runPickle 内触发，split-brain
        // 亦每场景报错佐证）。若在此 finishRun()，幂等收尾会在第 1 个场景后即触发 → 后续场景只落
        // real-time per-case JSON，不再进 finishAll 聚合（HTML/summary/index 缺后续场景）。
        // finishRun 统一由 PwReportPlugin.onRunFinished（TestRunFinished，每运行一次）驱动。
        instances.clear();
    }

    @Override
    public boolean addClass(Class<?> glueClass) {
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getInstance(Class<T> glueClass) {
        Object instance = instances.computeIfAbsent(glueClass, this::newGlueInstance);
        return (T) instance;
    }

    /** 注册自定义实例化 Supplier（框架/测试装配用；无参构造不可用时）。 */
    public <T> void registerSupplier(Class<T> type, Supplier<T> supplier) {
        suppliers.put(type, supplier);
    }

    private Object newGlueInstance(Class<?> glueClass) {
        Object instance = newInstanceOf(glueClass);
        PwStepInjector.injectInto(instance);
        return instance;
    }

    private Object newInstanceOf(Class<?> type) {
        Supplier<?> supplier = suppliers.get(type);
        if (supplier != null) {
            return supplier.get();
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalStateException(
                    "PwReportObjectFactory cannot instantiate glue class " + type.getName()
                            + " (no accessible no-arg constructor?)", e);
        }
    }
}

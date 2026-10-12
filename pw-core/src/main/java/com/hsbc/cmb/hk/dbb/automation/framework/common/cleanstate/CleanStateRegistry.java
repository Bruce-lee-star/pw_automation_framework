package com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * B-6：用例级隔离状态注册表——框架统一复位与**断言式清理**的单一收口点。
 *
 * <p>替代「每个 glue 手写 {@code @Before} 重置」：业务把复位与残留判定注册为 {@link StateResolver}，
 * 框架钩子统一在 {@code @Before} 调 {@link #resetAll()}、{@code @After} 调 {@link #dirtyNames()} 断言。
 *
 * <p><b>SPI 自动发现</b>：除显式 {@link #register(StateResolver)} 外，本注册表在首次访问时通过
 * {@link ServiceLoader} 扫描 classpath 上的 {@link StateResolver} 实现（各模块在
 * {@code META-INF/services/...StateResolver} 中声明），实现「模块自注册、框架零装配」。
 * 这样可选模块（如 API 测试支撑）只需提供实现与 services 声明，无需消费方手动接线，
 * 也根除了「依赖已加载但 resolver 未注册」的加载时序问题。
 *
 * <p>线程安全：注册表用 {@link ConcurrentHashMap}；{@link #resetAll()}/{@link #dirtyNames()} 对已注册
 * 解析器逐一调用，实例本身须线程安全。所有方法幂等；空注册表时为空操作。
 */
public final class CleanStateRegistry {

    private static final ConcurrentMap<String, StateResolver> RESOLVERS = new ConcurrentHashMap<>();

    //  SPI 发现只跑一次；clearResolvers()（测试隔离）会重置以便下次访问重新触发
    private static volatile boolean serviceResolversLoaded = false;

    private CleanStateRegistry() {
    }

    /** 注册解析器（同名覆盖）。null 忽略。 */
    public static void register(StateResolver resolver) {
        if (resolver == null) {
            return;
        }
        Objects.requireNonNull(resolver.name(), "StateResolver.name() must not be null");
        RESOLVERS.put(resolver.name(), resolver);
    }

    /** 注销指定状态（测试隔离 / 动态卸载）。 */
    public static void unregister(String name) {
        if (name != null) {
            RESOLVERS.remove(name);
        }
    }

    /** 清空注册表（仅供测试隔离）；同时使 SPI 发现可被下次访问重新触发。 */
    public static void clearResolvers() {
        RESOLVERS.clear();
        serviceResolversLoaded = false;
    }

    /** 已注册状态名快照。 */
    public static Set<String> registeredNames() {
        ensureServiceResolversLoaded();
        return Set.copyOf(RESOLVERS.keySet());
    }

    /** 复位所有已注册状态（幂等；空注册表为空操作）。 */
    public static void resetAll() {
        ensureServiceResolversLoaded();
        for (StateResolver resolver : RESOLVERS.values()) {
            resolver.reset();
        }
    }

    /** 复位指定状态（未注册的忽略）。 */
    public static void reset(String name) {
        ensureServiceResolversLoaded();
        StateResolver resolver = name == null ? null : RESOLVERS.get(name);
        if (resolver != null) {
            resolver.reset();
        }
    }

    /**
     * 返回仍处于「脏」状态的状态名（按注册顺序无关的稳定排序）；空表示无残留。
     * 供框架 {@code @After} 断言式清理使用。
     */
    public static List<String> dirtyNames() {
        ensureServiceResolversLoaded();
        List<String> dirty = new ArrayList<>();
        RESOLVERS.forEach((name, resolver) -> {
            if (resolver.isDirty()) {
                dirty.add(name);
            }
        });
        dirty.sort(String::compareTo);
        return dirty;
    }

    /**
     * 首次访问时通过 SPI 发现 classpath 上声明的 {@link StateResolver} 实现并注册。
     * 双检锁保证一次性；发现失败（缺失声明 / 类加载异常）仅记录、不阻断框架启动与用例收尾。
     */
    private static void ensureServiceResolversLoaded() {
        if (serviceResolversLoaded) {
            return;
        }
        synchronized (CleanStateRegistry.class) {
            if (serviceResolversLoaded) {
                return;
            }
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = StateResolver.class.getClassLoader();
            }
            try {
                ServiceLoader<StateResolver> loader = ServiceLoader.load(StateResolver.class, cl);
                for (StateResolver resolver : loader) {
                    register(resolver);
                }
            } catch (ServiceConfigurationError e) {
                //  SPI 发现失败（如模块未提供实现）属可降级场景，不应阻断用例收尾
                System.err.println("[CleanStateRegistry] StateResolver SPI discovery skipped: " + e);
            }
            serviceResolversLoaded = true;
        }
    }
}

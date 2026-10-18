package com.hsbc.cmb.hk.dbb.automation.framework.report.step;

import com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.NoStepInterception;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.TypeCache;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isVirtual;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * ByteBuddy 子类代理工厂——平移 Serenity {@code StepFactory.createProxyStepLibrary}。
 *
 * <p><b>免 javaagent</b>：Serenity 的 JUnit 路径才需要 agent 级 instrumentation；
 * 我们只代理工厂创建的实例，运行时子类即可。
 *
 * <p>缓存策略：<b>代理类</b>按步骤库类型缓存（{@link TypeCache} SOFT 引用，防 classloader
 * 泄漏）；实例每次调用新构造（{@code shared=false} 语义）。{@code shared=true} 或字段标
 * {@code @Shared} 的实例另有一份<b> per-thread</b> 共享缓存（{@link ThreadLocal}
 * {@code Map}）——对齐 Serenity 的 per-Scenario StepFactory（同类型步骤库在<b>当前线程</b>
 * 内全 glue 共享一个代理实例；跨线程隔离，杜绝跨场景/跨线程状态泄漏）。
 *
 * <p>生命周期：per-thread 共享缓存在 {@code ReportBus.finishCase}（场景结束）时由
 * {@link #clearSharedCacheForThread()} 回收，确保线程池复用时下一场景拿到全新共享实例。
 *
 * <p>边界（§16.4.5）：final 类 / 接口 / 抽象类 / {@link NoStepInterception} 标注类 /
 * 已是代理的类 → 不代理（warn 或静默直通）；final / static / private 的 @Step 方法
 * 无法覆盖 → 创建时 warn 清单。
 */
public final class PwStepFactory {

    private static final Logger log = LoggerFactory.getLogger(PwStepFactory.class);

    private static final ByteBuddy BYTE_BUDDY = new ByteBuddy();
    private static final TypeCache<TypeCache.SimpleKey> PROXY_CLASS_CACHE =
            new TypeCache<>(TypeCache.Sort.SOFT);
    /**
     * per-thread 共享实例缓存：键为步骤库类型，值为该线程内共享的代理实例。
     * JVM-global 会变成跨场景/跨线程共享同一可变步骤库实例（状态泄漏 + 竞态），
     * 故必须为 ThreadLocal（对齐 Serenity per-Scenario StepFactory 语义）。
     */
    private static final ThreadLocal<Map<Class<?>, Object>> SHARED_INSTANCES =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    private PwStepFactory() {
    }

    /** 每注入点独立实例（{@code shared=false} 语义）。 */
    public static <T> T proxyFor(Class<T> stepsClass) {
        if (stepsClass == null) {
            return null;
        }
        if (skipProxy(stepsClass, "proxyFor")) {
            // 降级策略：@NoStepInterception / final / 已是代理 → 原始实例直通
            //（不拦截但字段可用，避免「注入了 null」的 NPE 陷阱）；
            // 接口 / 抽象类无法实例化 → null（注入方 warn 跳过）。
            if (stepsClass.isInterface() || Modifier.isAbstract(stepsClass.getModifiers())) {
                return null;
            }
            log.debug("proxyFor: {} degrading to raw instance (no interception)", stepsClass.getName());
            return newInstanceOf(stepsClass);
        }
        Class<?> proxyClass;
        try {
            proxyClass = PROXY_CLASS_CACHE.findOrInsert(
                    stepsClass.getClassLoader(),
                    new TypeCache.SimpleKey(stepsClass),
                    () -> createProxyClass(stepsClass));
        } catch (Throwable t) {
            // 代理创建失败 → 降级原始实例（可用但无拦截）。
            // 关键：让异常穿透 findOrInsert 而不是在 createProxyClass 内返回原始类——
            // TypeCache 缓存 Callable 的返回值，返回原始类会把「不可代理」持久化进缓存。
            log.warn("proxyFor: {} proxy creation failed (degrading to no interception): {}",
                    stepsClass.getName(), t.toString());
            return newInstanceOf(stepsClass);
        }
        return newInstanceOf(proxyClass);
    }

    /** 同类型在<b>当前线程</b>内共享一个实例（{@code shared=true} / {@code @Shared} 语义）。 */
    @SuppressWarnings("unchecked")
    public static <T> T sharedProxyFor(Class<T> stepsClass) {
        if (stepsClass == null) {
            return null;
        }
        Map<Class<?>, Object> cache = SHARED_INSTANCES.get();
        Object existing = cache.get(stepsClass);
        if (existing != null) {
            return (T) existing;
        }
        T created = proxyFor(stepsClass);
        if (created == null) {
            return null;
        }
        Object raced = cache.putIfAbsent(stepsClass, created);
        return raced == null ? created : (T) raced;
    }

    // ---------------------------------------------------------------- internals

    private static boolean skipProxy(Class<?> stepsClass, String op) {
        if (stepsClass.isAnnotationPresent(NoStepInterception.class)) {
            log.debug("{}: {} marked @NoStepInterception, raw instance", op, stepsClass.getName());
            return true;
        }
        if (PwStepProxy.class.isAssignableFrom(stepsClass)) {
            log.debug("{}: {} is already a pw-report proxy", op, stepsClass.getName());
            return true;
        }
        int modifiers = stepsClass.getModifiers();
        if (stepsClass.isInterface() || Modifier.isAbstract(modifiers)) {
            log.warn("{}: {} is interface/abstract; subclass proxy unavailable", op, stepsClass.getName());
            return true;
        }
        if (Modifier.isFinal(modifiers)) {
            log.warn("{}: {} is final; subclass proxy unavailable (degrading to no interception)",
                    op, stepsClass.getName());
            return true;
        }
        return false;
    }

    /**
     * 生成代理类。<b>不吞异常</b>——失败由 {@link #proxyFor} 捕获并降级，
     * 保证 TypeCache 不会把「创建失败的原始类」当成代理类缓存（持久降级陷阱）。
     */
    private static Class<?> createProxyClass(Class<?> stepsClass) {
        List<TypeDescription> stepAnnotations =
                PwStepAnnotations.stepAnnotationTypes(stepsClass.getClassLoader());
        if (stepAnnotations.isEmpty()) {
            log.debug("no @Step annotation types resolvable for {}; no interception needed",
                    stepsClass.getName());
            return stepsClass; // 无注解可用 → 无需代理，零开销
        }
        warnUnproxyableStepMethods(stepsClass);
        // ⚠ ByteBuddy 陷阱：anyOf(Iterable<?>) 是「值相等」匹配器（逐元素 is(value)），
        // 不是「匹配器的或」！传 List<Junction> 进去会得到 is(匹配器对象)，永远不匹配。
        // 必须用 or() 链（或 anyOf(ElementMatcher...) 变参重载）。
        ElementMatcher.Junction<MethodDescription> stepMethods = null;
        for (TypeDescription annotation : stepAnnotations) {
            ElementMatcher.Junction<MethodDescription> one =
                    ElementMatchers.isAnnotatedWith(annotation);
            stepMethods = stepMethods == null ? one : stepMethods.or(one);
        }
        return BYTE_BUDDY
                .subclass(stepsClass)
                .method(stepMethods
                        .and(isVirtual())
                        .and(not(ElementMatchers.isAbstract()))
                        .and(not(ElementMatchers.isFinalizer())))
                .intercept(MethodDelegation.to(PwStepDispatcher.class))
                .make()
                .load(stepsClass.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
    }

    /**
     * final / static / private 的 @Step 方法无法被子类覆盖——提前 warn 清单，
     * 避免「以为拦了其实没拦」的静默缺口。
     */
    private static void warnUnproxyableStepMethods(Class<?> stepsClass) {
        for (String warning : unproxyableStepWarnings(stepsClass)) {
            log.warn("{}", warning);
        }
    }

    /**
     * 列出无法拦截的 {@code @Step} 方法告警文案（包可见，供测试固化）。
     *
     * <p>抽成纯函数而非只在 {@link #warnUnproxyableStepMethods} 里拼字符串：这份清单是
     * 「静默缺口」的唯一信号——final/static/private 方法不会报错、只是<b>没被拦</b>，
     * 若哪天重构不小心把这个循环删了，没有测试会发现报告里凭空少步骤。</p>
     *
     * @return 告警文案（可为空列表）；每条形如
     *         {@code "@Step method a.b.C.m is final; NOT intercepted"}
     */
    static List<String> unproxyableStepWarnings(Class<?> stepsClass) {
        List<String> warnings = new java.util.ArrayList<>();
        Class<?> c = stepsClass;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!PwStepAnnotations.hasStepAnnotation(m)) {
                    continue;
                }
                int mod = m.getModifiers();
                if (Modifier.isFinal(mod)) {
                    warnings.add("@Step method " + stepsClass.getName() + "." + m.getName()
                            + " is final; NOT intercepted");
                } else if (Modifier.isStatic(mod) || Modifier.isPrivate(mod)) {
                    warnings.add("@Step method " + stepsClass.getName() + "." + m.getName()
                            + " is static/private; NOT intercepted");
                }
            }
            c = c.getSuperclass();
        }
        return warnings;
    }

    private static <T> T newInstanceOf(Class<?> type) {
        try {
            return (T) type.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            log.warn("cannot instantiate {} (no accessible no-arg constructor?): {}",
                    type.getName(), t.toString());
            return null;
        }
    }

    /** 场景结束回收：移除当前线程的 per-thread 共享实例缓存（线程池复用安全）。 */
    public static void clearSharedCacheForThread() {
        SHARED_INSTANCES.remove();
    }

    /** 测试钩子：清理当前线程共享实例缓存（委托 {@link #clearSharedCacheForThread}）。 */
    static void clearSharedCacheForTest() {
        clearSharedCacheForThread();
    }

    /** 测试钩子：代理类是否已缓存。 */
    static boolean isProxyClassCachedForTest(Class<?> stepsClass) {
        return PROXY_CLASS_CACHE.find(stepsClass.getClassLoader(), new TypeCache.SimpleKey(stepsClass)) != null;
    }

    static TypePool typePoolForTest(ClassLoader cl) {
        return TypePool.Default.of(cl);
    }
}

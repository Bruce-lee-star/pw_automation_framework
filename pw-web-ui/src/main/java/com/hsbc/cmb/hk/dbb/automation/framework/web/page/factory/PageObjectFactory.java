package com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConfigurationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.ManagedPageAware;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording.RecordingPageProxy;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ScenarioContext;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContext;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * 企业级PageObject工厂类
 * 
 * 功能：
 * 1. 支持多种实例生命周期策略（单例、原型、线程隔离等）
 * 2. 支持依赖注入和条件创建
 * 3. 支持创建拦截器和回调
 * 4. 提供统计和监控功能
 * 5. 线程安全，支持并发场景
 * 
 * 使用方式：
 * 在Steps类中：
 * - private BaiduPage baiduPage = PageObjectFactory.getPage(BaiduPage.class);
 * 
 * 或者使用生命周期策略：
 * - private BaiduPage baiduPage = PageObjectFactory.getPage(BaiduPage.class, LifecycleStrategy.PROTOTYPE);
 * 
 * 或者使用Builder模式：
 * - private BaiduPage baiduPage = PageObjectFactory.builder()
 *       .lifecycle(LifecycleStrategy.PROTOTYPE)
 *       .lazy(true)
 *       .build()
 *       .getPage(BaiduPage.class);
 */
public class PageObjectFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(PageObjectFactory.class);
    
    /**
     * 实例生命周期策略。
     * <p><b>默认 {@link LifecycleStrategy#THREAD_ISOLATED}</b>：每线程独立实例，与并行执行
     * （每线程独立 Browser/Context/Page）一致，避免共享实例被 {@code ownerThread} 守卫拒绝。
     */
    public enum LifecycleStrategy {
        /**
         * 单例模式：整个应用运行期间只创建一个实例（跨线程共享）。
         * <p>注意：跨线程共享与 {@code BasePage} 的 per-instance {@code ownerThread} 守卫冲突
         * （并行下他线程访问必抛 {@code cross-thread access denied}），故<b>不再是默认</b>；
         * 仅明确单线程复用时显式指定。
         */
        SINGLETON,
        
        /**
         * 原型模式：每次调用getPage()都创建新实例
         */
        PROTOTYPE,
        
        /**
         * 线程隔离模式：每个线程有独立的实例
         */
        THREAD_ISOLATED,
        
        /**
         * 请求作用域：每次测试请求创建新实例（模拟）
         */
        REQUEST_SCOPED
    }
    
    /**
     * 创建配置
     */
    public static class CreationConfig {
        private final LifecycleStrategy lifecycleStrategy;
        private final boolean lazy;
        private final Map<Class<?>, Supplier<Object>> customSuppliers;
        private final List<Consumer<Object>> postCreateHooks;
        private final Map<String, Object> properties;
        
        private CreationConfig(LifecycleStrategy lifecycleStrategy, boolean lazy,
                          Map<Class<?>, Supplier<Object>> customSuppliers,
                          List<Consumer<Object>> postCreateHooks,
                          Map<String, Object> properties) {
            this.lifecycleStrategy = lifecycleStrategy;
            this.lazy = lazy;
            this.customSuppliers = customSuppliers;
            this.postCreateHooks = postCreateHooks;
            this.properties = properties;
        }
        
        public LifecycleStrategy getLifecycleStrategy() {
            return lifecycleStrategy;
        }
        
        public boolean isLazy() {
            return lazy;
        }
        
        public Map<Class<?>, Supplier<Object>> getCustomSuppliers() {
            return customSuppliers;
        }
        
        public List<Consumer<Object>> getPostCreateHooks() {
            return postCreateHooks;
        }
        
        public Map<String, Object> getProperties() {
            return properties;
        }
    }
    
    /**
     * Builder模式 - 灵活配置PageObjectFactory
     */
    public static class Builder {
        private LifecycleStrategy lifecycleStrategy = LifecycleStrategy.THREAD_ISOLATED;
        private boolean lazy = false;
        private final Map<Class<?>, Supplier<Object>> customSuppliers = new HashMap<>();
        private final List<Consumer<Object>> postCreateHooks = new CopyOnWriteArrayList<>();
        private final Map<String, Object> properties = new HashMap<>();
        
        /**
         * 设置生命周期策略
         */
        public Builder lifecycle(LifecycleStrategy strategy) {
            this.lifecycleStrategy = strategy;
            return this;
        }
        
        /**
         * 设置是否延迟初始化
         */
        public Builder lazy(boolean lazy) {
            this.lazy = lazy;
            return this;
        }
        
        /**
         * 注册自定义供应商
         */
        public <T> Builder customSupplier(Class<T> pageClass, Supplier<T> supplier) {
            @SuppressWarnings("unchecked")
            Supplier<Object> objectSupplier = (Supplier<Object>) (Supplier<?>) supplier;
            customSuppliers.put(pageClass, objectSupplier);
            return this;
        }
        
        /**
         * 添加创建后钩子
         */
        public Builder postCreateHook(Consumer<Object> hook) {
            postCreateHooks.add(hook);
            return this;
        }
        
        /**
         * 设置属性
         */
        public Builder property(String key, Object value) {
            properties.put(key, value);
            return this;
        }
        
        /**
         * 构建配置
         */
        public CreationConfig build() {
            return new CreationConfig(lifecycleStrategy, lazy, customSuppliers, postCreateHooks, properties);
        }
    }
    
    // 默认配置
    private static final CreationConfig DEFAULT_CONFIG = new Builder().build();
    
    // 存储所有PageObject实例，使用类名作为key
    private static final ConcurrentMap<Class<?>, Object> singleInstances = new ConcurrentHashMap<>();
    
    // 线程隔离实例存储（ T3-1 收拢：由 static ThreadLocal 迁入 TestContext，per-thread 等价）
    private static final ContextKey<Map> THREAD_INSTANCES_KEY = ContextKey.of("pof.threadInstances", Map.class);

    /** 取当前线程的隔离实例 Map（惰性创建，等价原 withInitial(ConcurrentHashMap::new)）。 */
    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Object> threadInstances() {
        return (Map<Class<?>, Object>) TestContextHolder.get()
                .computeIfAbsent(THREAD_INSTANCES_KEY, ConcurrentHashMap::new);
    }

    /**
     * 不写状态：取当前线程 / 用例<b>已存在</b>的隔离实例 Map（无则空 Map），<b>绝不创建</b> ThreadContext
     * （P1-11）。纯查询（hasInstance / getInstanceCount / getStatistics / getRegisteredPageClasses）
     * 必须走本方法而非 {@link #threadInstances()}，否则会为当前线程凭空创建上下文并登记进
     * {@link TestContextHolder}，引入意料之外的 side-effect。
     */
    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Object> threadInstancesView() {
        TestContext ctx = TestContextHolder.peek();
        if (ctx == null) {
            return Collections.emptyMap();
        }
        Object v = ctx.get(THREAD_INSTANCES_KEY);
        if (v instanceof Map) {
            return (Map<Class<?>, Object>) v;
        }
        return Collections.emptyMap();
    }

    /**
     * 不写状态：遍历<b>所有</b>已登记上下文（线程键 + 用例级）合并线程隔离实例 Map（P1-11）。
     * 与 {@link #clearAll()} 跨上下文口径一致，供统计 / 注册类查询使用。
     */
    private static Map<Class<?>, Object> allThreadInstancesView() {
        Map<Class<?>, Object> acc = new HashMap<>();
        TestContextHolder.forEachAllContext(ctx -> mergeThreadInstances(acc, ctx));
        return acc;
    }

    private static void mergeThreadInstances(Map<Class<?>, Object> acc, TestContext ctx) {
        Object v = ctx.get(THREAD_INSTANCES_KEY);
        if (v instanceof Map) {
            acc.putAll((Map<Class<?>, Object>) v);
        }
    }

    /**
     * 不写状态：统计<b>所有</b>已登记上下文（线程键 + 用例级）下的线程隔离实例总数（P1-11）。
     * 此前 {@link #getInstanceCount()} 只数当前线程，而 {@link #clearAll()} 跨线程统计 →
     * "实例数"泄漏观测指标口径不一致、不可信；现统一为跨上下文口径。
     */
    private static long threadIsolatedInstanceCount() {
        AtomicLong total = new AtomicLong(0);
        TestContextHolder.forEachAllContext(ctx -> addThreadInstanceCount(total, ctx));
        return total.get();
    }

    private static void addThreadInstanceCount(AtomicLong total, TestContext ctx) {
        Object v = ctx.get(THREAD_INSTANCES_KEY);
        if (v instanceof Map) {
            total.addAndGet(((Map<?, ?>) v).size());
        }
    }
    
    // 请求作用域实例存储
    private static final ConcurrentMap<String, Map<Class<?>, Object>> requestScopedInstances =
            new ConcurrentHashMap<>();

    /**
     * 记录「本次请求作用域实际写入所用的键」（P1-12 修复）。
     *
     * <p>背景：{@link #getCurrentRequestId()} 优先场景身份、回落线程身份；请求作用域实例在
     * {@link #resolveInstance} 创建时按该函数取键写入。若收尾时刻（{@link #endRequestScope}）场景身份
     * 已解绑，键口径会翻转为 {@code thread:<tid>}，写入时的 {@code scenario:<id>} 条目就此永久滞留
     * （静态 Map 随用例数单调增长，且持有整张 PageObject 图）。故在<b>写入同线程</b>用本 ThreadLocal
     * 记录真实键，收尾时优先取之 —— 即便场景已解绑，删除键仍与写入键一致。</p>
     */
    private static final ThreadLocal<String> CURRENT_REQUEST_SCOPE_KEY = new ThreadLocal<>();

    /** 记录请求作用域键（P1-12）：存于线程级，跨「场景解绑」仍可被收尾读取。 */
    private static void recordRequestScopeKey(String reqId) {
        CURRENT_REQUEST_SCOPE_KEY.set(reqId);
    }
    
    // 当前配置
    private static volatile CreationConfig currentConfig = DEFAULT_CONFIG;

    // G-3：全局显式构造器注册表（编译期安全创建路径，优先于反射回退）
    private static final ConcurrentMap<Class<?>, Supplier<Object>> GLOBAL_SUPPLIERS = new ConcurrentHashMap<>();
    
    // 统计信息
    private static final ConcurrentMap<Class<?>, Long> creationCount = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Long> accessCount = new ConcurrentHashMap<>();
    private static final AtomicLong totalCreations = new AtomicLong(0);
    private static final AtomicLong totalAccess = new AtomicLong(0);
    
    // 私有构造函数，防止实例化
    private PageObjectFactory() {
    }
    
    /**
     * 使用Builder模式构建配置
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * 设置全局配置
     */
    public static void setConfig(CreationConfig config) {
        if (config != null) {
            currentConfig = config;
            VerboseLogging.logInfoIfVerbose(logger, "PageObjectFactory configuration updated: {}", config.getLifecycleStrategy());
        }
    }
    
    /**
     * 重置为默认配置
     */
    public static void resetConfig() {
        currentConfig = DEFAULT_CONFIG;
        VerboseLogging.logInfoIfVerbose(logger, "PageObjectFactory configuration reset to default");
    }

    /**
     * G-3 修复：显式登记 PageObject 的构造 Supplier，提供<b>编译期安全</b>的创建路径，
     * 优先于反射 {@code newInstance()}。适合无公共无参构造、或构造需注入依赖的 Page。
     *
     * @param pageClass 页类型
     * @param supplier  构造器（可捕获所需依赖）
     */
    public static <T> void register(Class<T> pageClass, Supplier<? extends T> supplier) {
        @SuppressWarnings("unchecked")
        Supplier<Object> objectSupplier = (Supplier<Object>) (Supplier<?>) supplier;
        GLOBAL_SUPPLIERS.put(pageClass, objectSupplier);
    }

    /** 撤销 {@link #register} 登记的构造器。 */
    public static void unregister(Class<?> pageClass) {
        GLOBAL_SUPPLIERS.remove(pageClass);
    }
    
    /**
     * 获取PageObject实例（使用默认配置）
     * 
     * @param pageClass PageObject的Class对象
     * @return PageObject实例
     */
    public static <T> T getPage(Class<T> pageClass) {
        return getPage(pageClass, currentConfig);
    }
    
    /**
     * 获取PageObject实例（使用指定生命周期策略）
     * 
     * @param pageClass PageObject的Class对象
     * @param strategy 生命周期策略
     * @return PageObject实例
     */
    public static <T> T getPage(Class<T> pageClass, LifecycleStrategy strategy) {
        CreationConfig config = currentConfig;
        if (config.getLifecycleStrategy() != strategy) {
            // 临时使用指定策略：必须连同 customSuppliers / postCreateHooks / properties 一并迁移，
            // 否则经 Builder.customSupplier()/postCreateHook()/property() 配置的注入与钩子，在显式传
            // strategy 时被静默丢弃（典型表现：AbstractManagedPage.getPage() NPE 或钩子不执行）—— P1-10。
            CreationConfig base = config;
            config = new Builder()
                    .lifecycle(strategy)
                    .lazy(base.isLazy())
                    .build();
            config.getCustomSuppliers().putAll(base.getCustomSuppliers());
            config.getPostCreateHooks().addAll(base.getPostCreateHooks());
            config.getProperties().putAll(base.getProperties());
        }
        return getPage(pageClass, config);
    }
    
    /**
     * 获取PageObject实例（使用指定配置）
     * 
     * @param pageClass PageObject的Class对象
     * @param config 创建配置
     * @return PageObject实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T getPage(Class<T> pageClass, CreationConfig config) {
        try {
            // 统计访问次数
            accessCount.merge(pageClass, 1L, Long::sum);
            totalAccess.incrementAndGet();

            //  评审修复（2026-09-17）：按策略<b>原子</b>「取或建」。
            //  原实现是「读取 → 为 null 则创建 → 存回」的检查-创建序列：并发首调用会各自创建并
            //  顺序覆盖 —— 同一时刻不同调用方拿到<b>不同实例</b>（身份不一致；对持有 Page/Context
            //  引用的对象尤其危险），且后来者会静默覆盖前者。现改为「读 → 创建 → putIfAbsent 竞争发布
            //  → 落败者丢弃自己的实例并返回已发布者」，使「同一 (策略, key) 的全部调用方看到同一实例」
            //  成为保证。
            return (T) resolveInstance(pageClass, config);
        } catch (Exception e) {
            logger.error("Failed to create PageObject instance for: {}", pageClass.getSimpleName(), e);
            throw new ConfigurationException("Failed to create PageObject: " + pageClass.getSimpleName(), e);
        }
    }

    /**
     * 按生命周期策略原子获取（或创建）实例。
     *
     * @param pageClass PageObject 类型
     * @param config    创建配置
     * @return 该策略作用域下的实例
     */
    private static Object resolveInstance(Class<?> pageClass, CreationConfig config) {
        switch (config.getLifecycleStrategy()) {
            case PROTOTYPE:
                // 原型：每次都新建，不缓存、不参与发布竞争
                return newInstance(pageClass, config);
            case THREAD_ISOLATED:
                return getOrCreate(threadInstances(), pageClass, config);
            case REQUEST_SCOPED:
                String reqId = getCurrentRequestId();
                recordRequestScopeKey(reqId); // P1-12：记下写入所用键，供 endRequestScope 精确删除
                Map<Class<?>, Object> requestMap = requestScopedInstances
                        .computeIfAbsent(reqId, k -> new ConcurrentHashMap<>());
                return getOrCreate(requestMap, pageClass, config);
            case SINGLETON:
            default:
                return getOrCreate(singleInstances, pageClass, config);
        }
    }

    /**
     * 原子「取或建」：命中直接返回；未命中则<b>在 map 操作之外</b>创建，再以 {@link Map#putIfAbsent}
     * 竞争发布。
     *
     * <p><b>为何不用 {@code computeIfAbsent}</b>：创建过程包含「执行创建后钩子」与「注入受管 Page
     * 供应器」，属用户可扩展代码；放进 {@code ConcurrentHashMap} 的映射函数会在持桶锁时回调外部代码，
     * 一旦钩子再次调用本工厂（同 key）即触发递归更新（CHM 抛 {@code IllegalStateException}／死锁）。
     * {@code putIfAbsent} 同样保证「只有一个发布者」，且并发落败者丢弃自己的实例并返回已发布者 ——
     * 语义满足需要而更安全。
     *
     * @param store     目标存储（单例 / 线程隔离 / 请求作用域各自的 map）
     * @param pageClass PageObject 类型
     * @param config    创建配置
     * @return 已发布的实例（并发竞争时可能不是本次创建的那个）
     */
    private static Object getOrCreate(Map<Class<?>, Object> store, Class<?> pageClass, CreationConfig config) {
        Object existing = store.get(pageClass);
        if (existing != null) {
            logger.debug("Reusing cached PageObject instance for: {} (strategy: {})",
                    pageClass.getSimpleName(), config.getLifecycleStrategy());
            return existing;
        }
        Object created = newInstance(pageClass, config);
        Object winner = store.putIfAbsent(pageClass, created);
        if (winner != null) {
            logger.debug("Concurrent creation detected for: {} (strategy: {}) — discarding local instance, "
                    + "reusing the published one", pageClass.getSimpleName(), config.getLifecycleStrategy());
            return winner;
        }
        logger.debug("Created and published PageObject instance for: {} (strategy: {})",
                pageClass.getSimpleName(), config.getLifecycleStrategy());
        return created;
    }
    
    /**
     * 创建新实例（<b>不写入任何缓存</b>）：显式 Supplier 优先 → 反射回退；执行创建后钩子；统计创建次数。
     *
     * <p>缓存的写入由调用方 {@link #getOrCreate} 以 {@code putIfAbsent} 竞争发布完成 —— 创建与发布分离，
     * 使创建过程（含用户钩子）不持有 map 的桶锁。
     */
    private static Object newInstance(Class<?> pageClass, CreationConfig config) {
        // 1) 全局显式注册（编译期安全，推荐路径，G-3）
        Supplier<Object> supplier = GLOBAL_SUPPLIERS.get(pageClass);
        // 2) 本次配置传入的自定义供应商
        if (supplier == null) {
            supplier = config.getCustomSuppliers().get(pageClass);
        }
        Object instance;
        if (supplier != null) {
            instance = supplier.get();
        } else {
            // 3) 反射回退：仅适用于有无参构造的 Page；缺构造器时给出可操作的清晰报错（G-3）
            try {
                instance = pageClass.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException e) {
                throw new ConfigurationException(
                        "无法创建 PageObject: " + pageClass.getName() + " —— 缺少可访问的无参构造器。"
                        + "请为其添加 public 无参构造，或通过 PageObjectFactory.register("
                        + pageClass.getSimpleName() + ".class, () -> new " + pageClass.getSimpleName()
                        + "(...)) 显式登记 Supplier（编译期安全）。", e);
            } catch (InstantiationException | IllegalAccessException e) {
                throw new ConfigurationException(
                        "无法创建 PageObject: " + pageClass.getName() + " —— 构造器不可访问。"
                        + "请通过 PageObjectFactory.register(...) 显式登记 Supplier。", e);
            } catch (java.lang.reflect.InvocationTargetException e) {
                //  评审：原实现的 throws Exception 会把「构造器内部抛错」原样上抛，最终只看到
                //  "Failed to create PageObject" 而看不到真实根因 —— 此处显式带上 cause（含类名+消息）。
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new ConfigurationException(
                        "无法创建 PageObject: " + pageClass.getName() + " —— 构造器执行抛异常："
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
            }

            // 组合式 Page Object（新模型，G1 零继承）：注入受管 Page 惰性供应器，
            // 使其取得录制装饰（enabled 时）的受管 Page，原生操作自动录制（Layer A）
        }

        // 组合式 Page Object（新模型，G1 零继承）：注入受管 Page 惰性供应器，使其取得录制装饰
        // （enabled 时）的受管 Page，原生操作自动录制（Layer A）。
        //  修复（评审 F-02）：注入必须对「两条创建路径」统一执行 —— 原实现误置于上方反射 else 分支内，
        //  导致文档推荐的 register()/customSupplier() 路径反而漏注入（AbstractManagedPage.getPage() 直接 NPE）。
        if (instance instanceof ManagedPageAware) {
            ((ManagedPageAware) instance).setManagedPage(
                    () -> RecordingPageProxy.wrap(PlaywrightManager.getPage()));
        }

        // 执行创建后钩子（仅创建路径执行一次）
        executePostCreateHooks(instance, config);

        // 统计创建次数（评审：原供应商路径漏计 —— 两条创建路径统一在此计数，统计才可信）
        creationCount.merge(pageClass, 1L, Long::sum);
        totalCreations.incrementAndGet();

        return instance;
    }
    
    /**
     * 执行创建后钩子
     */
    private static void executePostCreateHooks(Object instance, CreationConfig config) {
        for (Consumer<Object> hook : config.getPostCreateHooks()) {
            try {
                hook.accept(instance);
            } catch (Exception e) {
                logger.warn("Post-create hook execution failed", e);
            }
        }
    }
    
    /**
     * 取当前「请求作用域」标识（评审修复 2026-09-17）。
     *
     * <p><b>原实现</b>返回 {@code Thread.currentThread().getName()}：线程池复用下「请求作用域」退化为
     * 「线程作用域」（同一线程上的多个用例共用同一批实例 → 跨用例串扰），且线程改名即等于换作用域。
     *
     * <p><b>现实现</b>优先取用例级身份（{@link ScenarioContext#currentScenarioId()}，由框架在用例开始时绑定），
     * 无绑定（如纯逻辑单测）时才回退到线程身份。作用域因此与<b>用例</b>对齐，并由
     * {@link #endRequestScope()}（用例收尾调用，见 {@code PlaywrightLifecycleBridge.cleanupForScenario}）
     * 回收，避免实例随用例数累积。
     *
     * @return 请求作用域键（{@code scenario:<id>} 或 {@code thread:<tid>}）
     */
    private static String getCurrentRequestId() {
        String scenarioId = ScenarioContext.currentScenarioId();
        if (scenarioId != null && !scenarioId.trim().isEmpty()) {
            return "scenario:" + scenarioId;
        }
        return "thread:" + Thread.currentThread().threadId();
    }
    
    /**
     * 预热PageObject实例（测试开始前创建）
     * 
     * @param pageClasses 需要预热的PageObject类列表
     */
    @SafeVarargs
    public static void warmUp(Class<?>... pageClasses) {
        if (pageClasses == null || pageClasses.length == 0) {
            logger.debug("No PageObject classes to warm up");
            return;
        }
        
        VerboseLogging.logInfoIfVerbose(logger, "Warming up {} PageObject instances", pageClasses.length);
        
        for (Class<?> pageClass : pageClasses) {
            try {
                getPage(pageClass);
                logger.debug("Warmed up: {}", pageClass.getSimpleName());
            } catch (Exception e) {
                logger.warn("Failed to warm up: {}", pageClass.getSimpleName(), e);
            }
        }
        
        VerboseLogging.logInfoIfVerbose(logger, "PageObject warm-up completed. Total instances: {}", getInstanceCount());
    }
    
    /**
     * 开始新的请求作用域
     */
    public static void beginRequestScope() {
        String requestId = getCurrentRequestId();
        recordRequestScopeKey(requestId); // P1-12：记录本次作用域键
        if (!requestScopedInstances.containsKey(requestId)) {
            requestScopedInstances.put(requestId, new ConcurrentHashMap<>());
            logger.debug("Started request scope for: {}", requestId);
        }
    }
    
    /**
     * 结束当前请求作用域
     */
    public static void endRequestScope() {
        //  P1-12：优先用写入时记录的键（即便收尾时场景身份已解绑，删除键仍与写入键一致）；
        //  未记录时回退到 getCurrentRequestId()（兼容未走 REQUEST_SCOPED 创建却调用本方法的边界）。
        String requestId = CURRENT_REQUEST_SCOPE_KEY.get();
        if (requestId == null) {
            requestId = getCurrentRequestId();
        }
        CURRENT_REQUEST_SCOPE_KEY.remove();
        Map<Class<?>, Object> instances = requestScopedInstances.remove(requestId);
        if (instances != null) {
            logger.debug("Ended request scope for: {}, cleaned up {} instances", 
                    requestId, instances.size());
        }
    }
    
    /**
     * 清除所有 PageObject 实例（通常在测试套件结束时调用）。
     *
     * <p><b>CT2-08</b>：三种作用域都必须<b>全局</b>清理，不能只清当前线程：
     * <ul>
     *   <li>单例 / 请求作用域：本就是全局 Map，直接 clear；</li>
     *   <li>线程隔离：实例按线程存放在各线程的 {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContext}
     *       （{@code THREAD_INSTANCES_KEY}）。原实现只移除<b>当前线程</b>的键 —— 其它 worker 线程
     *       （线程池复用 / 并行 scenario）的实例 Map 及其持有的 Page/Context 引用会跨用例、跨套件滞留；
     *       且打印的 threadCount 只统计当前线程，<b>语义名实不符、会掩盖泄漏</b>。
     *       现改为遍历<b>所有已登记线程</b>的上下文清理，并聚合真实总数。</li>
     * </ul>
     */
    public static void clearAll() {
        int singletonCount = singleInstances.size();
        int requestCount = requestScopedInstances.size();

        singleInstances.clear();
        requestScopedInstances.clear();

        // CT2-08：跨线程清理线程隔离实例 + 聚合真实总数（原实现只清/只数当前线程）。
        //  P1-11：扩展为「所有已登记上下文」（线程键 + 用例级）—— 即便未走 CT2-08 修复也可能
        //  残留于用例级上下文下的线程隔离实例，现一并清理，使计数与清理口径对齐。
        //  注意：遍历过程中「读值统计」必须早于「remove 键」，否则统计恒为 0。
        AtomicLong threadCount = new AtomicLong();
        TestContextHolder.forEachAllContext(ctx -> {
            Object value = ctx.get(THREAD_INSTANCES_KEY);
            if (value instanceof Map) {
                threadCount.addAndGet(((Map<?, ?>) value).size());
            }
            ctx.remove(THREAD_INSTANCES_KEY);
        });
        CURRENT_REQUEST_SCOPE_KEY.remove(); // 清理本线程的请求作用域键残留（P1-12）

        VerboseLogging.logInfoIfVerbose(logger,
                "Cleared all PageObject instances: {} singletons, {} thread-isolated (across {} thread context(s)), {} request-scoped",
                singletonCount, threadCount.get(), TestContextHolder.activeContextCount(), requestCount);
    }
    
    /**
     * 清除指定类型的PageObject实例
     * 
     * @param pageClass PageObject的Class对象
     */
    public static void clear(Class<?> pageClass) {
        int removed = 0;
        if (singleInstances.remove(pageClass) != null)  {removed++;} 
        if (threadInstances().remove(pageClass) != null)  {removed++;} 
        
        for (Map<Class<?>, Object> requestMap : requestScopedInstances.values()) {
            if (requestMap.remove(pageClass) != null)  {removed++;} 
        }
        
        logger.debug("Cleared PageObject instance for: {} (removed {} instances)", 
                pageClass.getSimpleName(), removed);
    }
    
    /**
     * 检查指定类型的PageObject实例是否存在
     * 
     * @param pageClass PageObject的Class对象
     * @return 如果实例存在返回true，否则返回false
     */
    public static boolean hasInstance(Class<?> pageClass) {
        if (singleInstances.containsKey(pageClass))  {return true;} 
        if (threadInstancesView().containsKey(pageClass))  {return true;} 
        
        for (Map<Class<?>, Object> requestMap : requestScopedInstances.values()) {
            if (requestMap.containsKey(pageClass))  {return true;} 
        }
        
        return false;
    }
    
    /**
     * 获取当前缓存的PageObject实例数量
     * 
     * @return 实例数量
     */
    public static int getInstanceCount() {
        long count = (long) singleInstances.size() + threadIsolatedInstanceCount();
        for (Map<Class<?>, Object> requestMap : requestScopedInstances.values()) {
            count += requestMap.size();
        }
        return (int) Math.min(count, Integer.MAX_VALUE);
    }
    
    /**
     * 获取统计信息
     * 
     * @return 统计信息字符串
     */
    public static String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== PageObjectFactory Statistics ===\n");
        sb.append(String.format("Total Creations: %d%n", totalCreations.get()));
        sb.append(String.format("Total Access: %d%n", totalAccess.get()));
        sb.append(String.format("Singleton Instances: %d%n", singleInstances.size()));
        sb.append(String.format("Thread-Isolated Instances: %d%n", allThreadInstancesView().size()));
        sb.append(String.format("Request-Scoped Instances: %d%n", requestScopedInstances.size()));
        sb.append("\nCreation Count by Class:\n");
        
        creationCount.entrySet().stream()
                .sorted(Map.Entry.<Class<?>, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    sb.append(String.format("  %s: %d creations, %d accesses%n",
                            entry.getKey().getSimpleName(),
                            entry.getValue(),
                            accessCount.getOrDefault(entry.getKey(), 0L)));
                });
        
        return sb.toString();
    }
    
    /**
     * 获取所有已注册的PageObject类
     * 
     * @return PageObject类集合
     */
    public static Set<Class<?>> getRegisteredPageClasses() {
        Set<Class<?>> classes = new HashSet<>();
        classes.addAll(singleInstances.keySet());
        classes.addAll(allThreadInstancesView().keySet());
        
        for (Map<Class<?>, Object> requestMap : requestScopedInstances.values()) {
            classes.addAll(requestMap.keySet());
        }
        
        return classes;
    }
    
    /**
     * 重置所有统计信息
     */
    public static void resetStatistics() {
        creationCount.clear();
        accessCount.clear();
        totalCreations.set(0);
        totalAccess.set(0);
        VerboseLogging.logInfoIfVerbose(logger, "PageObjectFactory statistics reset");
    }
}


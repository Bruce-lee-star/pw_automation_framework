package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge.PlaywrightLifecycleBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.provider.DefaultRuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightInitializer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * 企业级 Playwright Manager —— 运行时对象的<b>稳定公开门面（Facade）</b>，
 * 统一管理 Playwright / Browser / Context / Page 的生命周期。
 *
 * <h2>设计立场（满足 Java 三大特性 + 企业级）</h2>
 * <ul>
 *   <li><b>封装</b>：可变状态收敛到 {@link PlaywrightRuntimeState}（唯一受管容器）；
 *       本类自身无可变静态字段。配置读取经 {@code config()} 暴露为<b>只读</b>视图；
 *       自定义选项经 {@code customOptions()} 暴露为<b>受生命周期治理的 per-thread 可变</b>自定义 Context 选项
 *       （意图内的变更 API：setter 落 per-thread {@code TestContextHolder} 并触发延迟 Context 重建），二者均不暴露框架可变状态根。</li>
 *   <li><b>组合优于继承</b>：本门面与所有协作者均为 {@code final}，**刻意不使用 class 继承**
 *       （避免脆弱基类、保护封装）；扩展通过「接口契约 + 组合」实现（如 {@code BrowserStrategy}）。</li>
 *   <li><b>多态</b>：发生在 seam 层——{@link PlaywrightRuntime#setInstance} 整体换实现、
 *       {@link #setProvider} 换运行时对象源；每个角色接口 ≥2 实现（生产 {@code XImpl} + 测试替身）。</li>
 * </ul>
 *
 * <h2>API 边界</h2>
 * <ul>
 *   <li><b>稳定公开契约</b>：{@link #getPlaywright}/{@link #getBrowser}/{@link #getContext}/{@link #getPage}、
 *       生命周期入口、{@link #config()}/{@link #customOptions()} —— 业务与框架代码均可依赖。</li>
 *   <li><b>框架内部 seam</b>（包级私有）：{@code getPageThreadLocal()}/{@code getFrameworkState()} 等仅供同包协作者。</li>
 *   <li>运行时对象获取一律经 {@code provider → PlaywrightRuntime 组合根}，组合根可整体替换（真多态）。</li>
 * </ul>
 *
 * 特性：支持 框架 BDD 集成、线程安全（per-thread {@code ContextKey}）、灵活的浏览器生命周期管理、
 * 多级浏览器启动策略、避免静态初始化问题。
 */
public class PlaywrightManager {

    // ==================== 静态常量 ====================

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    // ==================== 非 ThreadLocal 静态变量 ====================
    // 【状态根已收口】原 STATE（PlaywrightRuntimeState 引用）已于 doc16 Phase 2 从本门面移除：
    // 全部可变状态由 PlaywrightRuntimeState 持有（WEB-P1-1 Step 1），并经 LifecycleState 角色接口
    // 对外提供受控操作；生命周期协作者统一经 PlaywrightRuntime.instance().state 访问，
    // 本门面不再持有任何状态根引用（公开面进一步收敛，状态根亦可随组合根整体替换）。
    //
    // 线程安全的实例回收容器（T3-2 企业级隔离）位于 PlaywrightRuntimeState：
    // ⚠️ 默认不变式：Map 的 VALUE（Browser/Playwright 实例）绝不跨线程共享——
    // 存储键为 "threadId:configId"（见 BrowserRegistry.keyFor），保证每个 worker 线程拥有独立实例。
    // 共享 ConcurrentHashMap 仅作为跨线程安全的回收/清理容器（供 cleanupAll 统一关闭），
    // 不再像旧实现那样按 configId 跨线程复用同一个 Browser（评审 P0：单点故障 + 全局串行化）。
    //


    // 【锁已收口】doc16 Phase 3：三把锁（per-thread Browser 锁 / CONTEXT_LOCK / PAGE_LOCK）
    // 全部迁入 LifecycleLockMediator 并改为 private，本门面不再暴露任何锁对象。
    // 协作者一律经 LifecycleLockMediator.withXxxLock(...) 进入临界区，
    // 从而消除「外部按任意顺序 synchronized 同一锁 / 长期劫持锁对象」的风险（锁顺序由中介集中保证）。



    // 框架状态引用
    static final FrameworkState frameworkState = FrameworkState.getInstance();

    // ==================== per-thread 变量（ T3-1 收拢：原 3 个 static ThreadLocal 迁入 TestContext，
    //  均为默认 null 语义，迁移后等价；包级可见性保持不变，供同包 PlaywrightLifecycleBridge 等访问） ====================

    // ---- 核心 Page/Context ----
    public static final ContextKey<BrowserContext> CONTEXT_KEY = ContextKey.of("playwrightManager.context", BrowserContext.class);
    public static final ContextKey<Page> PAGE_KEY = ContextKey.of("playwrightManager.page", Page.class);

    // ---- 配置标识 ----
    public static final ContextKey<String> CURRENT_CONFIG_ID_KEY = ContextKey.of("playwrightManager.currentConfigId", String.class);

    // ==================== 静态初始化块 ====================

    static {
        // 委托给 PlaywrightInitializer 处理初始化逻辑
        PlaywrightInitializer.initializePlaywrightPaths();
        PlaywrightInitializer.cleanupPlaywrightTempDirs();
        // 浏览器下载延迟到实际需要时，不在静态初始化阶段下载
    }

    // ==================== 初始化相关方法（委托给 PlaywrightInitializer） ====================

    // ==================== 生命周期管理方法 ====================

    public static synchronized void initialize() {
        PlaywrightRuntime.instance().browserStartup.initialize();
    }

    // ==================== 实例访问方法 ====================

    /**
     * 获取当前配置ID
     */
    public static String getCurrentConfigId() {
        if (TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY) == null) {
            TestContextHolder.get().set(CURRENT_CONFIG_ID_KEY,PlaywrightRuntime.instance().browserStartup.generateConfigId());
            // 修复 4.1：懒初始化 configId 时同步标记 frameworkState 为已初始化，
            // 避免 getContext()/getPage() 因 frameworkState 未初始化而抛 IllegalStateException。
            frameworkState.markInitialized();
        }
        return TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY);
    }

    public static String ensureConfigId() {
        return PlaywrightRuntime.instance().browserRegistry.ensureConfigId();
    }

    public static void setConfigId(String configId) {
        PlaywrightRuntime.instance().browserRegistry.setConfigId(configId);
    }

    /**
     * 并发 worker 使用的规范 configId（仅含浏览器类型 / headed 维度，如 {@code "chromium_headless_"}）。
     *
     * <p>各 worker 线程设置同一规范 configId，但 {@code BrowserRegistry.keyFor} 仍含 {@code threadId} 维度，
     * 故每个线程拿到<b>各自独立</b>的 Browser 实例（每线程独立 Browser + 每用例独立 Context）。
     * 此值与「共享 Browser」无关——它只统一浏览器形态，不共享 Browser 对象。</p>
     */
    public static String workerConfigId() {
        return PlaywrightRuntime.instance().browserRegistry.workerConfigId();
    }

    /**
     * 获取 Playwright 实例
     */
    // ===================== WEB-P0-2 可测试性 DI seam =====================
    // 公开 getter 退化为纯委托门面，经 provider seam（DefaultRuntimeProvider → PlaywrightRuntime 组合根）获取运行时对象。
    // DefaultRuntimeProvider 默认实现委托 PlaywrightRuntime.instance() 的 6 角色协作者（WEB-P1-6 Phase 3），
    // 故组合根可整体替换（PlaywrightRuntime.setInstance）以达成真多态。
    // provider 字段 volatile + 原子换引用，满足可见性；默认 INSTANCE 不可变，生产零额外开销。
    private static volatile RuntimeProvider provider = DefaultRuntimeProvider.INSTANCE;

    public static void setProvider(RuntimeProvider p) {
        if (p == null) {
            throw new IllegalArgumentException("RuntimeProvider must not be null");
        }
        provider = p;
    }

    public static RuntimeProvider getProvider() {
        return provider;
    }

    public static void resetProvider() {
        provider = DefaultRuntimeProvider.INSTANCE;
    }

    public static Playwright getPlaywright() {
        return provider.getPlaywright();
    }

    public static Browser getBrowser() {
        return provider.getBrowser();
    }

    /**
     * 创建并获取 BrowserContext（线程安全）
     * <p>
     * 支持延迟重建机制：当检测到自定义配置时,自动重建Context
     */
    public static BrowserContext getContext() {
        return provider.getContext();
    }

    public static void discardCurrentContext() {
        PlaywrightRuntime.instance().contextRegistry.discardCurrentContext();
    }

    /**
     * 本线程当前 Context（<b>线程级</b>记录，不受用例边界影响）。
     * <p>用于收尾可靠关闭与孤儿回收保护。
     */
    public static BrowserContext currentContextForThread() {
        return PlaywrightRuntime.instance().contextRegistry.currentContextForThread();
    }

    /** 本线程当前 Page（<b>线程级</b>记录，不受用例边界影响）。 */
    public static Page currentPageForThread() {
        return PlaywrightRuntime.instance().pageRegistry.currentPageForThread();
    }

    /**
     * 清理<b>本线程</b>的 Route / 采集状态（路由规则、防重门控、MonitorSession、per-context 引擎、
     * 采集会话），<b>不关闭</b> Context/Page，也<b>绝不触碰</b>其它线程。
     *
     * <p>用途：替代原异常/错误路径的全局 {@code resetAll()} / {@code clearDispatchedRoutes()} /
     * {@code stopCapture()} —— 后三者在并行下会清掉其它 worker 线程正在使用的规则与采集记录
     * （G4「误清理其他线程」）。
     */
    public static void clearCurrentThreadRouteState() {
        for (BrowserContext ctx : PlaywrightRuntime.instance().browserCleanup.contextsForCurrentThread()) {
            //  2026-09-30（档 B）：用例收尾【只清规则、保留引擎】—— V2 的 clearContext 已改为纯内存
            //  解绑（零协议调用）。原先这里还会额外调 stopContextEngine（= 拆 runtime + 逐条 unroute），
            //  那正是每用例 N 次 setNetworkInterceptionPatterns 的来源，也是 30s 卡死 / 信道污染 /
            //  下一用例 bind 挂死的引信（FIX_PLAN §4.1）。引擎的真正关闭交给"Context 确实要关"的
            //  路径：ContextRegistryImpl.cleanupContextScoped → stopContextEngine(ctx, true)
            //  （随 context.close() 原生释放，同样不 unroute）。
            PlaywrightRuntime.instance().browserCleanup.safeClean(
                    "RouteRegistry.clearContext", () -> RouteLifecycleRegistry.get().clearContext(ctx));
            PlaywrightRuntime.instance().browserCleanup.safeClean(
                    "ApiCaptureContext.stop", () -> RouteLifecycleRegistry.get().stopCaptureFor(ctx));
        }
    }

    /**
     * 停止<b>本线程</b>各 context 的采集（不触碰其它线程）。
     * <p>用于 scenario 正常收尾，替代全局 {@code stopCapture()}（G4）。
     */
    public static void stopCaptureForCurrentThread() {
        for (BrowserContext ctx : PlaywrightRuntime.instance().browserCleanup.contextsForCurrentThread()) {
            PlaywrightRuntime.instance().browserCleanup.safeClean(
                    "ApiCaptureContext.stop", () -> RouteLifecycleRegistry.get().stopCaptureFor(ctx));
        }
    }

    /**
     * 清理<b>本线程</b>的全部资源：Route/采集状态 + 本线程 Context（含孤儿）+ Page。
     *
     * <p>「清理当前线程的所有资源，不清理其它线程」：全部入口都以 {@code threadId} 前缀限定，
     * 并行安全。用于异常终止（skipped / step error）等需要彻底复位本线程的场景。
     */
    public static void clearCurrentThreadResources() {
        clearCurrentThreadRouteState();
        PlaywrightRuntime.instance().contextRegistry.closeContext();
        PlaywrightRuntime.instance().browserCleanup.closeOrphanContextsForCurrentThread();
        PlaywrightRuntime.instance().pageRegistry.closePage();
    }

    /**
     * 用例<b>异常收尾</b>（失败/跳过/忽略）的线程级清理。
     *
     * <p><b>2026-09-30 政策：不复用活 Context</b> —— 无论是否承载登录态，异常终止都<b>销毁本线程
     * Context 与 Page</b>，与正常 scenario 收尾（{@link #cleanupForScenario}）行为一致。
     * 登录复用改走官方一等机制 {@code storageState} 快照：{@code SessionManager.saveSession()} 成功路径落盘、
     * 下一用例经 {@code SessionManager.restoreSession()} 把文件级 storageState 重新注入<b>新建</b>的
     * Context（凭证是快照而非活体）；故「保留活 Context 延续登录态」已被废除。</p>
     *
     * <p>本方法始终执行：路由/采集状态清理 + 本线程孤儿 Context 回收 + 销毁本线程 Context/Page。
     * 并行安全：全部入口以线程级限定，不触碰其它线程。</p>
     */
    public static void clearThreadResourcesOnCaseAbort() {
        clearCurrentThreadRouteState();
        PlaywrightRuntime.instance().browserCleanup.closeOrphanContextsForCurrentThread();
        PlaywrightRuntime.instance().contextRegistry.closeContext();
        PlaywrightRuntime.instance().pageRegistry.closePage();
    }

    public static void setPage(Page page) {
        PlaywrightRuntime.instance().pageRegistry.setPage(page);
    }

    /**
     * 获取 Page（线程安全）
     * <p>
     * 支持延迟重建机制：在获取 Page 时检查是否需要重建 Context
     * 先调用 getContext() 确保重建检查被执行
     */
    public static Page getPage() {
        return provider.getPage();
    }

    /**
     * 本线程当前 Page 的「变更世代」信号（P2-18 热路径优化）。
     *
     * <p>仅当本线程当前页发生过切换 / 重建时自增；{@code PageContextState.ensurePageValid}
     * 在 page 已有效时据此跳过昂贵的 {@link #getPage()} 全链路比对，避免每个 element op 的静态调用链开销。
     * 读取开销仅为一次线程级 {@code ThreadLocal.get()}，远小于 {@link #getPage()}（provider seam + 注册表查表 + 可能建页）。</p>
     */
    public static long currentPageEpoch() {
        return PlaywrightRuntime.instance().pageRegistry.currentPageEpoch();
    }







    // ==================== Context 和 Page 创建方法 ====================

    /**
     * 非阻塞语义的退避等待工具：基于 LockSupport.parkNanos 实现，不调用 Thread.sleep，
     * 也不依赖 ForkJoinPool。仅用于初始化路径中"等待浏览器启动重试"这类本身即阻塞 I/O 的场景。
     *
     * @param millis 等待毫秒数
     */
    // 包级私有：供同包 Browser*Impl 协作访问（WEB-P1-1 Step 2）。
    public static void parkMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        // parkNanos 接受纳秒；直接阻塞当前线程（此处没有 Playwright 事件循环需要保护）
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    public static void createNewContextAndPage() {
        PlaywrightRuntime.instance().contextRegistry.createNewContextAndPage();
    }

    // ==================== 关闭和清理方法 ====================

    public static void closePage() {
        PlaywrightRuntime.instance().pageRegistry.closePage();
    }

    public static void closeContext() {
        PlaywrightRuntime.instance().contextRegistry.closeContext();
    }

    public static boolean hasContext() {
        return PlaywrightRuntime.instance().contextRegistry.hasContext();
    }

    // ==================== 就地换会话（1.59+ BrowserContext.setStorageState，免重建） ====================

    /**
     * 将 session storageState 应用到当前 Context（1.59+ <b>就地换会话，免重建</b>）。
     * <p>
     * 若当前线程已有活跃 {@link BrowserContext}：直接在其上调用 {@code BrowserContext.setStorageState}
     * 切换会话，并关闭当前 Page（避免其携带旧会话），免去「改会话即重建 Context」的绕路
     * （关闭 Page/Context 再重建、重注册监听、重跑 route 等），契合评估报告 §4 的轻量化诉求。
     * 若无活跃 Context：退化为设置 customOptions，待下次 {@link #getContext()} 创建时应用（与既有行为一致）。
     * <p>
     * 无论哪种路径都会同步 customOptions 的 storageState（<b>不置重建 flag</b>），保证后续若因其它自定义配置
     * 触发重建时仍带上本次会话；同时避免「仅会话恢复」误导 {@code getContext()} 误判需重建。
     *
     * @param storageStateJson storageState JSON 字符串（null/空安全：直接忽略）
     * @apiNote 稳定公开契约；业务/框架经 {@code SessionManager} 间接触达，不建议直接调用。
     */
    public static void applyStorageState(String storageStateJson) {
        if (storageStateJson == null || storageStateJson.isEmpty()) {
            return;
        }
        BrowserContext ctx = PlaywrightRuntime.instance().contextRegistry.getCurrentContext();
        boolean ctxAlive = ctx != null && ctx.browser() != null && ctx.browser().isConnected();
        if (ctxAlive) {
            closeCurrentPageIfAny();
            // 1.59+ 的 BrowserContext.setStorageState 仅接受 Path：将内存 JSON 落临时文件后应用，用完即删，
            // 避免为「就地换会话」重新引入磁盘存储（仍远轻于关闭 Page/Context 再重建整条生命周期）。
            Path tmp = null;
            try {
                tmp = Files.createTempFile("pw-storage-", ".json");
                Files.writeString(tmp, storageStateJson, StandardCharsets.UTF_8);
                ctx.setStorageState(tmp);
                VerboseLogging.logInfoIfVerbose(logger,
                        "Applied storageState in-place to live context (no context rebuild)");
            } catch (IOException e) {
                throw new RuntimeException("[Session] Failed to apply storageState to live context", e);
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignore) {
                        VerboseLogging.logDebugIfVerbose(logger, "Temp storageState file not deleted: {}", tmp);
                    }
                }
            }
            // 仅就地换会话：不置 flag，避免后续 getContext() 误判需重建（抵消轻量化收益）
            customOptions().setStorageStateWithoutRebuild(storageStateJson);
        } else {
            // Context 尚未创建或已失效：必须置位 customOptions flag（setStorageState 而非 WithoutRebuild），
            // 否则后续 getContext() 新建 Context 时不会注入 storageState，导致「本地有有效缓存却仍走完整登录」
            // （master 分支「首次登录不加载 session 文件」的等价缺陷）。此时本就无存活 Context，谈不上「重建」。
            customOptions().setStorageState(storageStateJson);
            VerboseLogging.logInfoIfVerbose(logger,
                    "StorageState set (context not alive) — will apply on next getContext() creation");
        }
    }

    /**
     * 同上，但接受 storageState 文件路径（回退路径：内存 JSON 缓存未命中时）。
     */
    public static void applyStorageStatePath(Path storageStatePath) {
        if (storageStatePath == null) {
            return;
        }
        BrowserContext ctx = PlaywrightRuntime.instance().contextRegistry.getCurrentContext();
        boolean ctxAlive = ctx != null && ctx.browser() != null && ctx.browser().isConnected();
        if (ctxAlive) {
            closeCurrentPageIfAny();
            ctx.setStorageState(storageStatePath);
            VerboseLogging.logInfoIfVerbose(logger,
                    "Applied storageState (path) in-place to live context (no context rebuild): {}", storageStatePath);
            // 仅就地换会话：不置 flag，避免后续 getContext() 误判需重建（抵消轻量化收益）
            customOptions().setStorageStatePathWithoutRebuild(storageStatePath);
        } else {
            // Context 尚未创建或已失效：必须置位 customOptions flag（setStorageStatePath 而非 WithoutRebuild），
            // 否则后续 getContext() 新建 Context 时不会注入 storageState，导致「本地有有效缓存却仍走完整登录」
            // （master 分支「首次登录不加载 session 文件」的等价缺陷）。此时本就无存活 Context，谈不上「重建」。
            customOptions().setStorageStatePath(storageStatePath);
            VerboseLogging.logInfoIfVerbose(logger,
                    "StorageState path set (context not alive) — will apply on next getContext() creation: {}",
                    storageStatePath);
        }
    }

    /**
     * 关闭当前线程的 Page（若有且未关闭），并从 TestContext 移除引用。
     * 供「就地换会话」在 {@code BrowserContext.setStorageState} 前释放旧会话的页面，避免其携带过期 Cookie/Storage。
     */
    private static void closeCurrentPageIfAny() {
        Page page = TestContextHolder.get().get(PAGE_KEY);
        if (page != null && !page.isClosed()) {
            try {
                VerboseLogging.logInfoIfVerbose(logger, "Closing current page for in-place session switch");
                page.close();
            } catch (Exception e) {
                VerboseLogging.logWarnIfVerbose(logger, "Failed to close page on session switch: {}", e.getMessage());
            }
        }
        TestContextHolder.get().remove(PAGE_KEY);
    }

    public static void restartBrowser() {
        PlaywrightRuntime.instance().browserRestart.restartBrowser();
    }

    /**
     * 清理所有 Playwright 资源（全局）。
     *
     * @apiNote <b>framework-internal</b>：仅限套件结束 / 非并发场景使用。并发执行期间调用会关停
     * 所有线程的 Browser/Playwright 实例，导致并行整轮集体失败；并发场景请使用
     * {@link #cleanupForScenario()}（按线程隔离）。现已加运行时断言：并发模式下调用直接抛
     * {@link IllegalStateException}（致命缺陷3 修复，原仅靠注释约束）。
     */
    public static void cleanupAll() {
        if (ConcurrentContextExecutor.isConcurrentModeActive()) {
            throw new IllegalStateException(
                    "cleanupAll() must not be called while concurrent execution is active; "
                            + "use cleanupForScenario() per-thread instead, or call cleanupAll() only at suite teardown.");
        }
        PlaywrightRuntime.instance().browserCleanup.cleanupAll();
    }

    /**
     * 兜底回收本线程 Browser 上仍打开的 BrowserContext（<b>窗口堆积修复</b>）。
     *
     * <p>收尾链路关闭 Context 依赖 {@code CONTEXT_KEY}；该键因用例级上下文已解绑而取不到时，
     * {@link #closeContext()} 会被静默跳过，导致 headed 模式下窗口跨用例堆积。
     * 本方法从 Browser 层确定性回收，作为兜底；正常路径下无残留时为零开销（返回 0）。
     *
     * @return 实际关闭的 Context 数
     */
    public static int reapOrphanContexts() {
        return PlaywrightRuntime.instance().browserCleanup.closeOrphanContextsForCurrentThread();
    }

    /**
     * 关闭<b>本线程</b>当前持有的 Browser（{@code <threadId>:<configId>} 键）并从状态根移除。
     *
     * <p><b>方案 A（2026-10-10）</b>：正常与并发模式默认都不触发本方法 —— Browser 为
     * worker 级重对象跨场景/跨任务复用（对齐 Playwright 官方模型），仅在<b>并发执行器模式且显式开启
     * {@code playwright.browser.close.after.concurrent.task=true}</b> 或显式关闭路径调用；
     * 浏览器进程最终由套件收尾（JVM shutdown hook → {@code cleanupAll()}）统一回收。
     * 当前线程无存活 Browser 时为 no-op。</p>
     *
     * @return 实际关闭的 Browser 数
     */
    public static int closeBrowserForCurrentThread() {
        return PlaywrightRuntime.instance().browserCleanup.closeBrowserForCurrentThread();
    }

    // ==================== 框架 BDD 集成方法（委托给 PlaywrightLifecycleBridge） ====================

    public static void initializeForScenario() {
        PlaywrightLifecycleBridge.initializeForScenario();
    }

    /**
     * 断开的 Browser 重建（由 {@code BrowserCrashGuard} 调用）。
     * 委托 {@link BrowserRestart}；每线程独立 Browser 模型下重建本线程 Browser（详见 {@code BrowserRestart}）。
     *
     * @return true 表示可继续重跑（已重建或无需重建）
     */
    public static boolean rebuildBrowserIfDisconnected() {
        return PlaywrightRuntime.instance().browserRestart.rebuildBrowserIfDisconnected();
    }

    /**
     * 无条件重建本线程 Browser（句柄损坏场景的强制恢复动作，由 {@code BrowserCrashGuard#recoverForced()} 调用）。
     * 委托 {@link BrowserRestart}；每线程独立 Browser 模型下重建本线程 Browser。
     *
     * @return 始终返回 true（表示已执行重建）
     */
    public static boolean rebuildBrowser() {
        return PlaywrightRuntime.instance().browserRestart.rebuildBrowser();
    }

    public static void cleanupForScenario() {
        ScenarioLifecycle.cleanupForScenario();
    }

    /**
     * 带用例结果的场景收尾（A5，2026-09-26；**失败语义已于 2026-09-28 修订**）。
     *
     * <p>{@code scenarioFailed} 仅作为<b>结果语义</b>传入（2026-09-30 收口后：<b>无论成败、是否承载
     * 登录态，都销毁本 case 的 Context 与 Page</b>，scenario-scoped）；失败不再保留 Context ——
     * 登录复用统一走 {@code storageState} 快照注入（凭证是快照而非活体），
     * "保留活 Context 延续登录态"已废除。
     * 详见仓库根 {@code FIX_PLAN.md} §1.1（I-1）与 §2.0（时效声明）。</p>
     *
     * @apiNote <b>framework-internal</b>：由 {@code PlaywrightListener} 在 框架 {@code testFinished} 时
     *          传入本用例真实结果；业务代码不得调用。
     * @param scenarioFailed 本用例是否失败（FAILURE / ERROR 视为失败）
     */
    public static void cleanupForScenario(boolean scenarioFailed) {
        ScenarioLifecycle.cleanupForScenario(scenarioFailed);
    }

    /**
     * Feature 级别的清理（委托给 ScenarioLifecycle → PlaywrightLifecycleBridge）
     */
    public static void cleanupForFeature() {
        ScenarioLifecycle.cleanupForFeature();
    }

    // ==================== 截图方法（委托给 PlaywrightScreenshotManager） ====================

    public static String takeScreenshot(String title) {
        return PlaywrightScreenshotManager.takeScreenshot(title);
    }

    // ==================== 配置访问（通过 config() 代理到 PlaywrightConfigManager） ====================
    // 使用 PlaywrightManager.config().getXXX() 或 PlaywrightConfigManager.config().getXXX() 访问配置

    // ==================== 公共访问方法 ====================

    /**
     * 获取自定义选项管理器（提供 {@code PlaywrightManager.customOptions().getXXX()} 风格的 API）。
     *
     * <p><b>这是受支持的、稳定的公开门面方法</b>（业务与框架代码均可使用），用于设置/读取自定义浏览器选项
     * （storage state、proxy、locale 等）。它<b>不是封装缺口</b>，而是门面刻意提供的配置访问入口；
     * 同包内部协作者为减少一次门面跳转，可直接调用 {@link CustomOptionsManager#getInstance()}。</p>
     *
     * @apiNote <b>稳定的公开变更 API（per-thread）</b>：返回的 {@link CustomOptions} 抽象（实现为 {@link CustomOptionsManager}）的 setter 写入的是
     *          本线程 {@code TestContextHolder} 中的自定义 Context 选项，并触发延迟 Context 重建（下次 {@code getContext()} 生效），
     *          不会污染其它线程；典型用法为会话恢复（{@code SessionManager} 经此 {@code setStorageState/Path}）与场景级设备仿真
     *          （{@code setLocale/setViewportSize}）。请在导航前设置；业务应经此门面（返回抽象）而非直连具体类 {@link CustomOptionsManager}。
     *          内部生命周期控制 {@code removeAllThreadLocals()} 已为包级私有，不对业务暴露。
     */
    public static CustomOptions customOptions() {
        return CustomOptionsManager.getInstance();
    }

    /**
     * 获取配置管理器（提供 {@code PlaywrightManager.config().getXXX()} 风格的 API）。
     *
     * <p><b>这是受支持的、稳定的公开门面方法</b>（业务与框架代码均可使用）。它<b>不是封装缺口</b>，
     * 而是门面刻意收敛的「唯一配置读取入口」——所有 {@code framework.playwright.*} 配置都经此暴露，
     * 业务侧应统一走 {@code PlaywrightManager.config().getXxx()}，不要直连 {@link PlaywrightConfigManager}。</p>
     *
     * @apiNote 稳定的公开契约；其返回对象是只读配置视图，不暴露可变状态。
     */
    public static PlaywrightConfigManager config() {
        return PlaywrightConfigManager.config();
    }

    // ==================== 包内访问器（框架内部 seam，非对外契约） ====================

    /**
     * 读取本线程绑定的 Page（per-thread 原始状态访问）。
     *
     * @apiNote <b>框架内部 seam</b>（仅同包协作者使用，如 {@code PlaywrightScreenshotManager}）；
     *          业务代码应使用 {@link #getPage()} 经 provider seam 获取，勿依赖此原始访问器。
     */
    public static Page getPageThreadLocal() {
        return TestContextHolder.get().get(PAGE_KEY);
    }

    /**
     * 读取框架状态引用（{@link FrameworkState}）。
     *
     * @apiNote <b>框架内部 seam</b>（仅同包协作者使用，如 {@code PlaywrightLifecycleBridge}）；
     *          不属公开契约，业务不应依赖。
     */
    public static FrameworkState getFrameworkState() {
        return frameworkState;
    }

    /**
     * 解析本线程的下载落盘目录：{@code <playwright.browser.downloadsPath>/thread-<线程序号>}。
     *
     * <p>框架不自动保存下载文件；业务在 {@code Download.saveAs(...)} 时可使用本目录，以获得
     * 「按线程隔离 + 用例结束自动清理」的语义：每个用例（scenario）收尾时框架只删除本线程的
     * 子目录，并发执行的其它线程不受影响。</p>
     *
     * <p><b>为何带线程子目录</b>：{@code downloadsPath} 是进程级共享根目录，并行执行时若所有用例
     * 直接落到同一目录，一次用例清理会误删其它线程正在写入的文件。</p>
     *
     * @return 本线程的下载目录（不存在则尝试创建；创建失败也返回该路径，由落盘方报错）
     */
    public static Path downloadDirectoryForCurrentThread() {
        Path dir = Paths.get(config().getBrowserDownloadsPath())
                .resolve("thread-" + Thread.currentThread().getId());
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            // 目录创建失败不阻断业务：返回路径即可，真正落盘时由 saveAs 暴露错误
            LoggerFactory.getLogger(PlaywrightManager.class)
                    .warn("[download] failed to create download dir {}: {}", dir, e.getMessage());
        }
        return dir;
    }

}
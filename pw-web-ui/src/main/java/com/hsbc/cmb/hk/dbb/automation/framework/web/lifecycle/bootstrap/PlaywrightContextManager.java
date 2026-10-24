package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextOutcomeBinding;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.CloseGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageInteractionMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.ProxyConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace.ScenarioTraceRecorder;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Context 和 Page 管理器 - 负责 Context 和 Page 的创建、配置和关闭
 * <p>
 * 职责：
 * - Context 创建和配置
 * - Page 创建和稳定化
 * - Context 和 Page 的关闭
 */
public class PlaywrightContextManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightContextManager.class);

    /**
     * 本线程「框架受管页创建中」标记（见 {@link #createPage}）。
     *
     * <p><b>用途</b>：Playwright 的 {@code BrowserContext.onPage} 对 Context 内<b>任何</b>新页触发 ——
     * 既含框架自身 {@code createPage()} 的 {@code context.newPage()}（此刻尚未导航，url 恒为
     * {@code about:blank}），也含业务 {@code window.open}/{@code target=_blank} 弹窗或泄漏页。
     * 仅凭该回调无法区分二者，故在框架唯一建页入口 {@link #createPage} 内置位本标记，
     * 使回调能精确判定「受管页 vs 额外页」，避免把框架自己的 about:blank 受管页误报成弹窗。</p>
     *
     * <p><b>为何用 {@code ThreadLocal}</b>：Playwright Java 在<b>等待命令返回的线程</b>上派发事件，
     * 故 {@code context.newPage()} 触发的 onPage 回调与本次 {@code createPage} 调用同线程
     * （实测 onPage 与 createPage 同 worker 线程、时间窗重合），标记对该回调可见。</p>
     */
    private static final ThreadLocal<Boolean> MANAGED_PAGE_CREATION_IN_FLIGHT = new ThreadLocal<>();

    /** 录屏诊断告警去重（每 JVM 一条）：避免"开了却没录到"在每个用例上刷屏。 */
    private static final java.util.concurrent.atomic.AtomicBoolean VIDEO_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 每个 Context 的录屏句柄登记表 —— 在<b>建页时</b>登记，而不是等关闭时再去
     * {@code context.pages()} 里捞。
     *
     * <p>why：实测 dbb-2/dbb-3 在 close 阶段 {@code context.pages()} 已拿不到可用录像句柄
     * （页面此前已被 {@code page-close} 关掉 / 通道已失效），于是"明明录了却报没有录像"。
     * 关闭时只做兜底补充，主来源是这张表。</p>
     *
     * <p>BrowserContext 未覆写 equals/hashCode → 按身份匹配；条目在 closeContext 末尾移除，不会泄漏。</p>
     */
    private static final java.util.Map<BrowserContext, java.util.List<com.microsoft.playwright.Video>>
            CONTEXT_VIDEOS = new java.util.concurrent.ConcurrentHashMap<>();

    //  trace 的启停 / 分段 / 导出 / 报告挂载已收口到
    //  {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace.ScenarioTraceRecorder}
    //  （方案 A：按 scenario 分段录制）；其专属写盘线程池与 ShutdownCoordinator 登记随之内聚到该类。


    /**
     * 创建新的 BrowserContext
     */
    public static BrowserContext createContext() {
        VerboseLogging.logInfoIfVerbose(logger, "Creating new BrowserContext...");

        Browser currentBrowser = PlaywrightManager.getBrowser();
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();

        // 配置框架默认项
        configureDefaultContextOptions(contextOptions);

        // 条件注入自定义配置
        Boolean customFlag = CustomOptionsManager.getInstance().isCustomContextOptionsFlag();
        if (customFlag != null && customFlag) {
            VerboseLogging.logInfoIfVerbose(logger, "Applying custom context options...");
            configureCustomContextOptions(contextOptions);
        }

        //  修复问题3：用 try-finally 确保 customContextOptionsFlag 在 newContext() 抛异常（浏览器断开/
        // 参数非法）时也能重置，避免该线程后续重试持续携带已失效的自定义配置而反复失败。
        //  CT2-05：newContext() 之后任一步骤（onPage/register/configureTimeouts/enableTracing）抛异常，
        //  已创建的 context 既未返回也未关闭 → 确定性泄漏。用 initialized 标志区分成功/失败，
        //  失败路径在 finally 中安全关闭半初始化 context（close 自身再包 try-catch）。
        BrowserContext context = null;
        boolean initialized = false;
        try {
            // 初始化 Context
            context = currentBrowser.newContext(contextOptions);
            // lambda 需捕获有效 final 引用（context 在 try 内被赋值，非 effectively final）
            final BrowserContext ctx = context;

            //  页面创建监听（判定「框架受管页」vs「额外页」）：
            //   context.onPage 对「任何」新页触发，不能笼统记成 "detected via window.open()" ——
            //   框架自身 createPage() 的 context.newPage() 也会触发（此刻尚未导航，url 为 about:blank），
            //   在并行逐 scenario 重建下会被误读为"同一窗口多开了一个 about:blank 页"。
            //   故经 MANAGED_PAGE_CREATION_IN_FLIGHT 精确判定归属，并打出 Context 内页数供判定。
            //  受配置开关控制（playwright.page.events.*）：onPage 为扇出总闸（关掉则本 Context 下
            //  所有页面级订阅一并失效），内嵌 onLoad 为独立开关。二者默认开启。
            if (WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_PAGE_ENABLED.getBooleanValue()) {
                ctx.onPage(newPage -> {
                    logNewPageEvent(ctx, newPage,
                            classifyNewPageEvent(ctx, newPage,
                                    Boolean.TRUE.equals(MANAGED_PAGE_CREATION_IN_FLIGHT.get())));
                    if (WebFrameworkConfig.PLAYWRIGHT_PAGE_EVENTS_LOAD_ENABLED.getBooleanValue()) {
                        newPage.onLoad(pageLoad -> {
                            VerboseLogging.logDebugIfVerbose(logger,
                                    "New page loaded: url={}, title={}", newPage.url(), newPage.title());
                        });
                    }
                });
            }

            // 注册页面级可观测性诊断监听（未捕获异常/控制台错误/网络失败/崩溃）
            // 经 context.onPage 覆盖所有新建页面（含 window.open 弹窗），与上方 onPage 日志互不冲突
            PageEventMonitor.register(context);

            // 注册页面级交互事件监听（导航轨迹 / 未受管弹窗 / 对话框自动处置 / 文件选择器自动上传）
            // 与 PageEventMonitor 同族、同接缝；各项均默认零行为回归，受独立配置开关控制
            PageInteractionMonitor.register(context);

            // 设置超时
            configureTimeouts(context);

            // 启用 tracing（如果配置了）
            enableTracing(context);

            initialized = true;
            //  per-context → outcomeId 绑定：供 CloseGuard 收尾线程精确解析 per-case 落点（并行下不取
            //    "最近开始的用例"）。报告旁路失败绝不影响 context 创建。
            try {
                String oid = ReportingBridgeRegistry.get().currentOutcomeId();
                if (oid != null) {
                    ContextOutcomeBinding.bind(context, oid);
                }
            } catch (RuntimeException ignore) {
                //  报告未装配 / 桥缺失 → 不绑定，回落既有最佳努力逻辑
            }
            VerboseLogging.logInfoIfVerbose(logger, "BrowserContext created successfully");
            return context;
        } finally {
            // 重置标志（成功或失败都执行）
            if (customFlag != null && customFlag) {
                CustomOptionsManager.getInstance().disableCustomOptions();
                VerboseLogging.logInfoIfVerbose(logger, "Custom context options applied, flag reset to false");
            }
            // CT2-05：初始化未成功（任一步骤抛异常）且已创建 context → 安全回收，杜绝泄漏
            if (!initialized && context != null) {
                safeCloseContextOnFailure(context);
            }
        }
    }

    /** 登记一页的录屏句柄（未开启录屏时 {@code page.video()} 为 null，直接跳过）。尽力而为。 */
    private static void rememberVideo(BrowserContext context, Page page) {
        if (context == null || page == null) {
            return;
        }
        try {
            com.microsoft.playwright.Video video = page.video();
            if (video == null) {
                return;
            }
            CONTEXT_VIDEOS.computeIfAbsent(context,
                            k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                    .add(video);
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "[video] handle unavailable: {}", e.getMessage());
        }
    }

    /**
     * 采集本 context 下各页面的录屏句柄 —— <b>必须在 {@code context.close()} 之前调用</b>。
     *
     * <p><b>API 事实（务必照此实现，勿改）</b>：Playwright 的录屏句柄在 <b>Page</b> 上
     * （{@code Page.video()}），<b>不在</b> {@code BrowserContext}——context 根本没有
     * {@code video()} 方法。而 {@code Video.path()} 只在 context 关闭后才返回可用路径、文件也才完整。</p>
     *
     * <p><b>为什么必须先取句柄</b>：{@code context.pages()} 在 close 之后返回空列表（页面通道随
     * context 一起销毁）。此前"close 之后再遍历 {@code context.pages()} 取 video"的写法永远拿不到
     * 句柄 → 报告里 Playwright records 一直显示"没有录像"（2026-10-21 用户反馈）。</p>
     *
     * <p>完全尽力而为：未开启录屏（video 为 null）/ 句柄不可用，一律静默跳过，绝不影响 context 收尾。</p>
     */
    private static List<com.microsoft.playwright.Video> collectVideoHandles(BrowserContext context) {
        List<com.microsoft.playwright.Video> videos = new java.util.ArrayList<>();
        if (context == null) {
            return videos;
        }
        try {
            for (Page page : context.pages()) {
                if (page == null) {
                    continue;
                }
                try {
                    com.microsoft.playwright.Video video = page.video();
                    if (video != null) {
                        videos.add(video);
                    }
                } catch (Exception e) {
                    VerboseLogging.logDebugIfVerbose(logger,
                            "[video] handle unavailable for a page: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "[video] collect skipped: {}", e.getMessage());
        }
        return videos;
    }

    /**
     * 把已采集的录屏句柄登记进报告（{@code ReportingBridge#attachVideo}）——在 close 之后调用。
     *
     * <p>一个 context 可能有多个页面（各自一份录像），报告只展示一份，故取<b>最大</b>的那个
     * 已落盘文件（主页面通常最长；空录像只有几 KB，被自动跳过），避免把弹窗的短录像当主录像。</p>
     *
     * @param videos    各页面录屏句柄（close 前采集的快照；可为空 → 安全 no-op）
     * @param outcomeId context 绑定的用例 id（可为 null——回落最佳努力归属）
     */
    private static void attachVideosToReport(List<com.microsoft.playwright.Video> videos, String outcomeId) {
        boolean enabled = PlaywrightManager.config().isRecordVideoEnabled();
        if (videos == null || videos.isEmpty()) {
            //  开了录屏却连句柄都没有 = 录制根本没配置上（context 创建时 recordVideoDir 未生效）。
            //  这条必须可定位：否则报告只会静默显示"该用例没有录像"（2026-10-22 用户仍在踩）。
            if (enabled) {
                warnVideoOnce("[video] recordVideo is enabled but no Video handle was found on any page — "
                        + "context was likely created without setRecordVideoDir (check "
                        + "playwright.context.recordVideo.enabled / dir)");
            }
            return;
        }
        Path best = null;
        long bestSize = -1L;
        StringBuilder detail = new StringBuilder();
        for (com.microsoft.playwright.Video video : videos) {
            try {
                Path recorded = video.path();
                if (recorded == null) {
                    detail.append(" | null path");
                    continue;
                }
                boolean exists = Files.exists(recorded);
                long size = exists ? Files.size(recorded) : -1L;
                detail.append(" | ").append(recorded.getFileName()).append(" exists=").append(exists)
                        .append(" bytes=").append(size);
                if (!exists) {
                    continue;
                }
                if (size > bestSize) {
                    best = recorded;
                    bestSize = size;
                }
            } catch (Throwable t) {
                //  Video.path() 在"无视频帧/未完成"时会抛异常：跳过该句柄，不拖累其它页面
                detail.append(" | path error: ").append(t.getMessage());
                VerboseLogging.logDebugIfVerbose(logger, "[video] path unavailable: {}", t.toString());
            }
        }
        if (best == null) {
            if (enabled) {
                warnVideoOnce("[video] recordVideo is enabled but no playable file was produced" + detail);
            }
            return;
        }
        try {
            //  优先按 context 绑定的 outcomeId 精确挂载（并行下不归错用例）；id 缺失时桥回落最佳努力。
            ReportingBridgeRegistry.get().attachVideo(outcomeId, best);
            VerboseLogging.logDebugIfVerbose(logger, "[video] recorded {} ({} bytes) to report (outcomeId={})",
                    best.getFileName(), bestSize, outcomeId);
        } catch (Throwable t) {
            warnVideoOnce("[video] attach to report failed for " + best.getFileName() + ": " + t);
        }
    }

    /**
     * 录屏诊断告警<b>每 JVM 只打一条</b>：录屏属旁路能力，不能因为每个用例都失败而刷屏；
     * 但"开了却没有任何录像"必须可见，否则报告里只会静默显示"该用例没有录像"，
     * 无法区分"没开"与"开了但没接上"（2026-10-22 排障口径）。
     */
    private static void warnVideoOnce(String message) {
        if (VIDEO_WARNED.compareAndSet(false, true)) {
            logger.warn(message);
        }
    }

    /** CT2-05：半初始化 context 失败时的安全回收（close 自身再包 try-catch，避免二次异常掩盖原错误）。 */
    private static void safeCloseContextOnFailure(BrowserContext ctx) {
        try {
            if (ctx != null && ctx.browser() != null && ctx.browser().isConnected()) {
                ctx.close();
            }
        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(logger,
                    "Failed to close half-initialized BrowserContext on failure: {}", e.getMessage());
        }
    }

    /**
     * 创建新的 Page
     */
    public static Page createPage(BrowserContext context) {
        VerboseLogging.logInfoIfVerbose(logger, "Creating new Page...");
        //  置位「框架受管页创建中」标记：createPage 是框架唯一调用 context.newPage() 的入口，
        //  其执行期间触发的 onPage 回调必属受管页；标记之外触发的 onPage 即业务弹窗 / 泄漏页。
        //  必须在 newPage() 之前置位、之后清除（含异常路径），否则归属判定失真。
        MANAGED_PAGE_CREATION_IN_FLIGHT.set(Boolean.TRUE);
        Page page;
        try {
            page = context.newPage();
        } finally {
            MANAGED_PAGE_CREATION_IN_FLIGHT.remove();
        }
        //  录像句柄<b>建页即登记</b>：Video 对象此刻一定可用；等到 close 时页面可能已被
        //  page-close 关掉，context.pages() 捞不出来（2026-10-22 实测）。
        rememberVideo(context, page);

        // 下载保存监听已迁移至 createContext 经 context.onDownload 一次性注册（见报告 §3.2），
        // 此处不再逐页注册，避免 window.open 弹窗内下载漏捕获。
        stabilizePage(page);
        VerboseLogging.logInfoIfVerbose(logger, "Page created successfully");
        return page;
    }



    /**
     * 关闭 Context
     */
    public static void closeContext(BrowserContext context) {
        if (context != null) {
            //  耗时探针（2026-10-10）：TRACE 级，默认不输出；按需开
            //    logger=com.hsbc...bootstrap.PlaywrightContextManager level=TRACE 观察
            //    SIT 真实收尾分布，为 CloseGuard.CONTEXT_CLOSE_LIMIT_MS 定标提供数据（正常毫秒级，
            //    挂死时此处不返回、由 CloseGuard 超时放弃 + dirty 隔离兜底，与阈值大小无关）。
            long t0 = System.nanoTime();
            //  录屏句柄：主来源是建页时登记的 CONTEXT_VIDEOS（close 阶段 context.pages() 常常已不可用），
            //   再把 close 前能枚举到的补上（去重），最后在收尾处摘除登记。
            List<com.microsoft.playwright.Video> recordedVideos = new java.util.ArrayList<>();
            java.util.List<com.microsoft.playwright.Video> remembered = CONTEXT_VIDEOS.get(context);
            if (remembered != null) {
                synchronized (remembered) {
                    recordedVideos.addAll(remembered);
                }
            }
            for (com.microsoft.playwright.Video v : collectVideoHandles(context)) {
                if (!recordedVideos.contains(v)) {
                    recordedVideos.add(v);
                }
            }
            try {
                //  先释放路由层资源：停止 MonitorSession 定时器、unroute、清理注册表与防重门控，
                //    避免调度器线程池持有已销毁 context 引用导致内存泄漏 / 对已关闭 context 无效调度。
                //    统一走 RouteLifecycle 生命周期钩子（含关闭前泄漏诊断）。
                try {
                    RouteLifecycleRegistry.get().clearContext(context);
                } catch (Exception re) {
                    VerboseLogging.logWarnIfVerbose(logger, "Failed to clear Route resources on context close: {}", re.getMessage());
                }
                //  trace 收尾（方案 A，2026-09-17）：交给 ScenarioTraceRecorder。
                //  正常路径下每个用例已在 scenario 收尾时导出了自己的 chunk 文件；若仍有未导出的活动 chunk
                //  （该用例未走到收尾，如异常/强制清理路径），在此兜底导出为 ONCLOSE 文件，随后结束 tracing。
                //  导出带超时、走专属线程池，任何失败只降级为日志，绝不阻塞 context.close()。
                ScenarioTraceRecorder.onContextClosing(context);
                //  独立的 close try：即使上面任何步骤抛异常，也要保证 context.close() 被执行，
                //    否则已关闭失败会导致 context 资源泄漏。
                //    注意：BrowserContext.isClosed() 在 Playwright Java 中可用（与 Page.isClosed() 同族）；
                //      本收口只负责"执行 close"，是否仍活跃由调用方复用判定（ContextRegistryImpl.getContext）
                //      统一用 isClosed() 把关，此处不重复。
                //  窗口堆积修复：原实现仅在 browser() 可用且已连接时才 close —— 浏览器已断开/对象失效时
                //    静默跳过，Context 及其窗口残留（直到套件级 cleanupAll 才释放）。改为无条件尝试 close，
                //    已关闭/失效（TargetClosedError 等）按预期降级为 debug 日志。
                //  收尾期不挂 onDialog 守卫：context.close() 默认不跑 beforeunload（强制关闭），且 Playwright
                //    未注册 handler 时对所有对话框 auto-dismiss，close 不会因此挂死；改挂 accept 反而会覆盖
                //    框架默认语义。真正的"无限挂死"兜底由 CloseGuard 有界关闭 + 超时强收本线程 Playwright 提供。
                long tClose = System.nanoTime();
                try {
                    context.close(new BrowserContext.CloseOptions().setReason("teardown"));
                    VerboseLogging.logInfoIfVerbose(logger, "BrowserContext closed");
                } catch (Exception closeEx) {
                    VerboseLogging.logDebugIfVerbose(logger,
                            "BrowserContext close skipped (already closed or browser gone): {}", closeEx.getMessage());
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("[CT] context.close() segment took {} ms (reason=teardown)",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tClose));
                }
                //  录屏收尾：Video.path() 只在 context 关闭后才可用且文件才完整，
                //   故解析路径放在 close() 之后；句柄则来自 close 之前采集的快照。
                //   交给报告落入 per-case（<outcomeId>/records/）；未开启录屏时集合为空 → 安全 no-op。
                //   归属优先用 context 绑定的 outcomeId（并行下精确），缺失时回落最佳努力。
                attachVideosToReport(recordedVideos, ContextOutcomeBinding.outcomeId(context));
            } catch (Exception e) {
                logger.error("Failed to close BrowserContext: {}", e.getMessage(), e);
                throw new BrowserException("Failed to close BrowserContext", e);
            } finally {
                CONTEXT_VIDEOS.remove(context); // 防泄漏：条目随 context 一起消亡
                ContextOutcomeBinding.unbind(context); // 同步解绑 outcomeId，防 key 驻留
            }
            if (logger.isTraceEnabled()) {
                logger.trace("[CT] closeContext total took {} ms (routeClear+downloadClear+trace+close)",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
            }
        }
    }

    // ==================== Trace（已迁移）====================
    //  文件名 sanitize 与「trace 挂进 框架 报告」两件事已随方案 A 迁至
    //  {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace.ScenarioTraceRecorder}：
    //  文件名改为 {@code trace-<scenarioId>-<start>-<end>-<PASS|FAIL|ONCLOSE>.zip}（带起止时间），
    //  报告条目同时给出覆盖时间窗，便于按区间定位。

    /**
     * 关闭 Page
     */
    public static void closePage(Page page) {
        if (page != null) {
            try {
                if (!page.isClosed()) {
                    //  先释放该 Page 上的路由层资源（停止 MonitorSession 定时器、清理注册表），
                    //    再关闭 Page，避免调度器线程池持有已销毁 page 引用导致内存泄漏。
                    //    统一走 RouteRegistry.clearContext 释放。
                    try {
                        RouteLifecycleRegistry.get().clearContext(page);
                    } catch (Exception re) {
                        VerboseLogging.logWarnIfVerbose(logger, "Failed to clear Route resources on page close: {}", re.getMessage());
                    }
                    VerboseLogging.logInfoIfVerbose(logger, "Closing Page...");
                    //  有界关闭：page.close() 亦可能挂（页面卸载 pending 导航/对话框）；超限即放弃等待，
                    //  该 page 会随后续 context/browser 关闭被回收，绝不让收尾线程被卡。
                    CloseGuard.Result pr = CloseGuard.runBoundedCapture("page-close",
                            () -> page.close(), CloseGuard.PAGE_CLOSE_LIMIT_MS);
                    if (!pr.completed()) {
                        VerboseLogging.logWarnIfVerbose(logger,
                                "page-close exceeded {}ms; abandoned (reclaimed with context/browser close)",
                                CloseGuard.PAGE_CLOSE_LIMIT_MS);
                    } else if (pr.hasError()) {
                        throw new BrowserException("Failed to close page: " + pr.errorMessage());
                    }
                    VerboseLogging.logInfoIfVerbose(logger, "Page closed");
                }
            } catch (Exception e) {
                logger.error("Failed to close page: {}", e.getMessage(), e);
                throw new BrowserException("Failed to close page", e);
            }
        }
    }

    /**
     * 配置框架默认 Context 选项
     */
    private static void configureDefaultContextOptions(Browser.NewContextOptions contextOptions) {
        // 启用文件下载（Playwright 安全策略默认禁止下载，需显式开启）
        contextOptions.setAcceptDownloads(true);

        contextOptions.setLocale(PlaywrightManager.config().getContextLocale());
        
        String timezoneId = PlaywrightManager.config().getContextTimezone();
        if (timezoneId != null && !timezoneId.isEmpty()) {
            contextOptions.setTimezoneId(timezoneId);
        }

        String userAgent = PlaywrightManager.config().getContextUserAgent();
        if (userAgent != null && !userAgent.isEmpty()) {
            contextOptions.setUserAgent(userAgent);
        }

        String permissionsConfig = PlaywrightManager.config().getContextPermissions();
        if (permissionsConfig != null && !permissionsConfig.isEmpty()) {
            contextOptions.setPermissions(List.of(permissionsConfig.split(",")));
        }

        // 配置代理服务器（直接从统一代理配置读取）
        // 优先级：customProxyEnabled ThreadLocal > playwright.context.proxy.enabled
        Boolean customProxyOverride = PlaywrightManager.customOptions().getProxyEnabled();
        boolean proxyEnabled;
        if (customProxyOverride != null) {
            proxyEnabled = customProxyOverride;
            VerboseLogging.logInfoIfVerbose(logger, "Using custom proxyEnabled override: {} (from business code)", proxyEnabled);
        } else {
            proxyEnabled = PlaywrightConfigManager.config().getContextProxyEnabled();
        }

        if (proxyEnabled) {
            String proxyUrl = ProxyConfigResolver.getHttpProxyUrl();
            if (proxyUrl != null) {
                contextOptions.setProxy(proxyUrl);
                VerboseLogging.logInfoIfVerbose(logger, "Setting context proxy from unified config");
            } else {
                VerboseLogging.logWarnIfVerbose(logger, "Proxy enabled but no HTTP proxy configured, skipping context proxy");
            }
        }

        // 配置设备缩放因子
        String deviceScaleFactor = PlaywrightManager.config().getDeviceScaleFactor();
        if (deviceScaleFactor == null || deviceScaleFactor.trim().isEmpty()) {
            double systemDpiScaleFactor = PlaywrightManager.config().getSystemDpiScaleFactor();
            deviceScaleFactor = String.valueOf(systemDpiScaleFactor);
        }
        contextOptions.setDeviceScaleFactor(Double.parseDouble(deviceScaleFactor));

        // 设置 viewport（使用逻辑尺寸，Playwright viewport 以 CSS 像素为单位）
        Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
        int viewportWidth = (int) screenSize.getWidth();
        int viewportHeight = (int) screenSize.getHeight();

        String browserType = PlaywrightManager.config().getBrowserType();

        // 统一 viewport 策略: viewport = 逻辑屏幕尺寸 - 浏览器 chrome 估算高度
        // stabilizePage 中 window.resizeTo(logicalWidth, logicalHeight) 将窗口外尺寸最大化到屏幕逻辑尺寸
        // 窗口外尺寸 = viewport + titleBar + tabBar + addressBar + ... ≈ viewport + 100px
        // 因此 viewport 应设为逻辑屏幕高度 - chrome估算值，才能填满最大化窗口
        int estimatedChromeHeight = 100;
        viewportHeight = Math.max(viewportHeight - estimatedChromeHeight, 600);
        contextOptions.setViewportSize(viewportWidth, viewportHeight);
        VerboseLogging.logInfoIfVerbose(logger,
            "Context viewport: {}x{} (logical screen {}x{}, adjusted for browser chrome ~{}px, browser={})",
            viewportWidth, viewportHeight, (int) screenSize.getWidth(), (int) screenSize.getHeight(),
            estimatedChromeHeight, browserType);

        contextOptions.setHasTouch(PlaywrightManager.config().hasTouch());
        contextOptions.setIsMobile(PlaywrightManager.config().isMobile());

        String colorScheme = PlaywrightManager.config().getColorScheme();
        contextOptions.setColorScheme(ColorScheme.valueOf(colorScheme.toUpperCase().replace("-", "_")));

        // 配置录屏
        if (PlaywrightManager.config().isRecordVideoEnabled()) {
            screenSize = PlaywrightManager.config().getAvailableScreenSize();
            //  录屏直接落到 per-case 目录（<outputDir>/<outcomeId>/records）——从源头避免
            //  「全局 videoDir 先落一份、context 关闭后再搬进 per-case」的两份与中途失败；
            //  解析不到活动用例句柄（如 context 建于场景开始前）时回落配置目录，
            //  仍由 closeContext 内的 attachContextVideoToReport 搬入 per-case。
            java.nio.file.Path perCaseVideoDir = null;
            try {
                perCaseVideoDir = ReportingBridgeRegistry.get().videoTarget();
            } catch (RuntimeException ignore) {
                perCaseVideoDir = null; // 报告旁路失败绝不影响录屏
            }
            java.nio.file.Path videoDir = perCaseVideoDir != null
                    ? perCaseVideoDir : Paths.get(PlaywrightManager.config().getRecordVideoDir());
            contextOptions.setRecordVideoDir(videoDir);
            contextOptions.setRecordVideoSize((int) screenSize.getWidth(), (int) screenSize.getHeight());
            //  自证落点：录屏"没进报告"时，第一现场要能看出到底录到哪个目录、多大
            VerboseLogging.logInfoIfVerbose(logger, "[video] recordVideo dir={} size={}x{} (perCase={})",
                    videoDir, (int) screenSize.getWidth(), (int) screenSize.getHeight(),
                    perCaseVideoDir != null);
        }
    }

    /**
     * 配置自定义 Context 选项
     *
     * @apiNote 框架内部能力（包级私有）：仅供 {@link #createContext()} 接线与同包单测断言。
     */
    static void configureCustomContextOptions(Browser.NewContextOptions contextOptions) {
        CustomOptions cm = PlaywrightManager.customOptions();

        // StorageState（newContext 冷启动 / 重建）：文件路径优先，内存 JSON 兜底。
        // 两者均走 Browser.NewContextOptions 的<b>原生重载</b>（setStorageStatePath(Path) / setStorageState(String)），
        // 各自直接生效，<b>不落临时文件</b>——临时文件只在「已启动的 live Context 就地换会话」
        // （PlaywrightManager.applyStorageState(String)）才需要，因为 live BrowserContext.setStorageState 仅接受 Path。
        // 文件路径优先：落盘的 .json 是 saveSession / reusePersistedSession 写入的权威凭证；
        // 内存 JSON 仅作为无文件时的缓存兜底。
        Path storagePath = cm.getStorageStatePath();
        if (storagePath != null && Files.exists(storagePath)) {
            contextOptions.setStorageStatePath(storagePath);
            VerboseLogging.logInfoIfVerbose(logger, "Using custom storageStatePath (cold start / rebuild): {}", storagePath);
        } else {
            String storageState = cm.getStorageState();
            if (storageState != null && !storageState.isEmpty()) {
                contextOptions.setStorageState(storageState);
                VerboseLogging.logInfoIfVerbose(logger, "Using in-memory storageState (from cache)");
            }
        }

        applyIfNotEmpty(cm.getLocale(), "locale", () -> contextOptions.setLocale(cm.getLocale()));
        applyIfNotEmpty(cm.getTimezoneId(), "timezoneId", () -> contextOptions.setTimezoneId(cm.getTimezoneId()));
        applyIfNotEmpty(cm.getUserAgent(), "userAgent", () -> contextOptions.setUserAgent(cm.getUserAgent()));
        applyIfNotNull(cm.getPermissions(), "permissions", () -> contextOptions.setPermissions(cm.getPermissions()));

        Geolocation geo = cm.getGeolocation();
        applyIfNotNull(geo, "geolocation", () -> contextOptions.setGeolocation(geo.latitude, geo.longitude));

        Integer sf = cm.getDeviceScaleFactor();
        applyIfNotNull(sf, "deviceScaleFactor", () -> contextOptions.setDeviceScaleFactor(sf / 100.0));

        applyIfNotNull(cm.getIsMobile(), "isMobile", () -> contextOptions.setIsMobile(cm.getIsMobile()));
        applyIfNotNull(cm.getHasTouch(), "hasTouch", () -> contextOptions.setHasTouch(cm.getHasTouch()));
        applyIfNotNull(cm.getColorScheme(), "colorScheme", () -> contextOptions.setColorScheme(cm.getColorScheme()));

        // Viewport（需要两个值均非空）
        Integer vw = cm.getViewportWidth();
        Integer vh = cm.getViewportHeight();
        if (vw != null && vh != null) {
            contextOptions.setViewportSize(vw, vh);
            VerboseLogging.logInfoIfVerbose(logger, "Using custom viewportSize: {}x{}", vw, vh);
        }
    }

    /**
     * 仅在值非空时应用自定义选项（适用于非 String 类型）
     */
    private static void applyIfNotNull(Object value, String name, Runnable applier) {
        if (value != null) {
            applier.run();
            VerboseLogging.logInfoIfVerbose(logger, "Using custom {}: {}", name, value);
        }
    }

    /**
     * 仅在值非空且非空字符串时应用（适用于 String 类型）
     */
    private static void applyIfNotEmpty(String value, String name, Runnable applier) {
        if (value != null && !value.isEmpty()) {
            applier.run();
            VerboseLogging.logInfoIfVerbose(logger, "Using custom {}: {}", name, value);
        }
    }

    /**
     * 非正值超时的统一安全下界（毫秒）—— 与 {@code playwright.element.wait.timeout} 的默认值一致。
     *
     * <p>{@link #positiveTimeoutOrDefault(String, int)} 供页面/导航/元素三类键共用：配置成 {@code 0}
     * 或负数（Playwright 语义 = 无限等待）时回落到本值，并留一条 WARN。</p>
     */
    private static final int DEFAULT_PAGE_TIMEOUT_MS = 15_000;

    /**
     * 配置超时。
     *
     * <p><b>N-16（doc 21 MEDIUM）</b>：Playwright 语义中 {@code 0} = <b>无限等待</b>。元素侧此前已补
     * {@code Math.max(1, ceil(ms/1000))} 下界，但页面 / 导航侧是<b>原样透传</b> —— 误配
     * {@code playwright.page.timeout=0}（或负值）会让所有页面级操作<b>永久挂起且没有任何信号</b>。
     * 现补同款下界：非正值回落到默认值并留 WARN（非 verbose 门控，绝不静默改写用户配置）。</p>
     */
    private static void configureTimeouts(BrowserContext context) {
        // ① 导航超时 ← playwright.page.navigationTimeout（goto/reload/goBack/waitForURL 等导航类操作）
        context.setDefaultNavigationTimeout(positiveTimeoutOrDefault(
                "playwright.page.navigationTimeout", PlaywrightManager.config().getNavigationTimeout()));
        // ② 默认动作超时 ← playwright.element.wait.timeout
        //    Playwright 的 setDefaultTimeout 语义 =「所有未显式传 timeout 的操作」的默认值，在本框架里
        //    这类操作就是元素操作（innerText / getAttribute / evaluate / allTextContents … 以及遗漏传参的动作）。
        //    2026-10-10 修正接线：原实现错用 playwright.page.timeout（语义=页面加载超时），导致元素读取按
        //    30s 等待（7.txt：Timeout 15000ms × 0、Timeout 30000ms × 118）。
        context.setDefaultTimeout(positiveTimeoutOrDefault(
                "playwright.element.wait.timeout", PlaywrightManager.config().getElementCheckTimeout()));
    }

    /**
     * N-16：把配置读取到的超时（页面 / 导航 / 元素三类键通用）规整为<b>正值</b>。
     *
     * <p>Playwright 的 {@code 0} 表示无限等待 —— 对测试框架而言那是「永久挂起且无信号」，
     * 远劣于「回落到默认值并告知」。故非正值一律回落 {@link #DEFAULT_PAGE_TIMEOUT_MS}，
     * 并写一条非 verbose 门控的 WARN（可观测，不静默）。</p>
     *
     * @param configKey  配置键名（仅用于告警定位）
     * @param configured 配置读取值
     * @return 可安全下发给 Playwright 的正值超时
     */
    static int positiveTimeoutOrDefault(String configKey, int configured) {
        if (configured > 0) {
            return configured;
        }
        logger.warn("[Playwright] {}={} 非法（Playwright 语义中 0/负数 = 无限等待 → 操作会永久挂起且无信号）；"
                        + "已回落到默认 {}ms，请修正该配置。",
                configKey, configured, DEFAULT_PAGE_TIMEOUT_MS);
        return DEFAULT_PAGE_TIMEOUT_MS;
    }

    /**
     * 启用 Tracing
     */
    private static void enableTracing(BrowserContext context) {
        //  方案 A：开启 tracing（chunk #0 随 context 开始），后续由 ScenarioTraceRecorder 在用例边界
        //  用 startChunk()/stopChunk(path) 切段 —— 使 trace 的时间区间 == 用例执行区间。
        ScenarioTraceRecorder.onContextCreated(context);
    }

    /**
     * 页面稳定化：确保页面加载完成并固定窗口/缩放状态
     */
    private static void stabilizePage(Page page) {
        try {
            VerboseLogging.logDebugIfVerbose(logger, "Page stabilization: ensuring correct window size...");

            int stabilizeWaitTimeout = PlaywrightManager.config().getStabilizeTimeout();
            LoadState loadState = getConfiguredLoadState();
            try {
                page.waitForLoadState(loadState, new Page.WaitForLoadStateOptions().setTimeout(stabilizeWaitTimeout));
            } catch (Exception e) {
                VerboseLogging.logDebugIfVerbose(logger, "Page load wait timed out (LoadState: {}), continuing stabilization: {}", loadState, e.getMessage());
            }

            Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
            int logicalWidth = (int) screenSize.getWidth();
            int logicalHeight = (int) screenSize.getHeight();

            // 统一使用 window.resizeTo 最大化窗口（所有浏览器）
            // 窗口外尺寸 = viewport + chrome，这里将外尺寸设为逻辑屏幕尺寸
            // context 中已设置 viewport = logicalHeight - chromeEstimate，两者配合正好填满
            //
            // 关键：window.resizeTo 会触发 OS 窗口管理器异步重排位置（居中），
            // moveTo(0,0) 必须通过 setTimeout 延迟执行，等 resize 彻底完成后才移到左上角
            // WebKit 不支持 --window-position launch arg，完全依赖此 JS 定位，延迟尤为关键
            page.evaluate(String.format(
                    "window.resizeTo(%d, %d);"
                    + "setTimeout(function() { window.moveTo(%d, %d); }, "
                    + WebFrameworkConfig.PLAYWRIGHT_CONTEXT_STABILIZE_DELAY_MS.getIntValue() + ");",
                    logicalWidth, logicalHeight, 0, 0
            ));
            VerboseLogging.logDebugIfVerbose(logger, "Window maximize: window.resizeTo({}, {}), moveTo(0,0) delayed 300ms", logicalWidth, logicalHeight);

            page.evaluate(
                    "document.body.style.zoom = '100%'; "
                            + "document.documentElement.style.zoom = '100%'; "
                            + "document.documentElement.style.transform = 'none'; "
                            + "document.documentElement.style.transformOrigin = '0 0';"
            );

            page.evaluate(
                    "window.addEventListener('resize', function(e) { e.stopPropagation(); }, true);"
                            + "document.addEventListener('DOMContentLoaded', function() {"
                            + "    if (window.devicePixelRatio !== 1) { "
                            + "    }"
                            + "});"
            );

            Integer customViewportWidthVal = CustomOptionsManager.getInstance().getViewportWidth();
            Integer customViewportHeightVal = CustomOptionsManager.getInstance().getViewportHeight();

            if (customViewportWidthVal != null && customViewportHeightVal != null) {
                // 用户自定义 viewport：直接应用
                page.setViewportSize(customViewportWidthVal, customViewportHeightVal);
                VerboseLogging.logDebugIfVerbose(logger, "Custom viewport applied: {}x{}",
                        customViewportWidthVal, customViewportHeightVal);
            } else {
                // context 已统一设置 viewport = logical - chromeEstimate
                // window.resizeTo + context viewport 配合 → 窗口填满屏幕
                VerboseLogging.logDebugIfVerbose(logger, "No custom viewport, keeping viewport from context (window-adapted)");
            }

            VerboseLogging.logDebugIfVerbose(logger, "Page stabilization completed");
        } catch (Exception e) {
            logger.warn("Page stabilization failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取配置的页面加载状态
     * 配置属性: playwright.page.load.state
     * 可选值: LOAD, DOMCONTENTLOADED, NETWORKIDLE
     * 默认值: DOMCONTENTLOADED
     */
    private static LoadState getConfiguredLoadState() {
        String loadStateConfig = PlaywrightManager.config().getPageLoadState();
        try {
            return LoadState.valueOf(loadStateConfig.toUpperCase());
        } catch (IllegalArgumentException e) {
            VerboseLogging.logWarnIfVerbose(logger, "Invalid LoadState configuration: {}, using default: DOMCONTENTLOADED", loadStateConfig);
            return LoadState.DOMCONTENTLOADED;
        }
    }

    /**
     * onPage 事件的新页归属判定结果（不可变；无副作用，供日志与同包单测断言）。
     *
     * @apiNote 框架内部能力（包级私有）：仅供 {@link #createContext()} 接线与同包单测使用。
     */
    static final class NewPageEvent {
        final boolean frameworkManaged;
        final int pageCount;
        final String url;
        final boolean aboutBlank;

        NewPageEvent(boolean frameworkManaged, int pageCount, String url) {
            this.frameworkManaged = frameworkManaged;
            this.pageCount = pageCount;
            this.url = url;
            this.aboutBlank = isAboutBlank(url);
        }
    }

    /**
     * 判定 onPage 事件中新页的归属：<b>框架受管页</b> vs <b>额外页</b>（业务 {@code window.open} 弹窗 /
     * 测试直接 {@code context.newPage()} / 泄漏页）。
     *
     * <p><b>纯函数</b>：无副作用、不读 verbose 配置，故可被同包单测确定性驱动并固化判据
     * （见 {@code PlaywrightContextManagerNewPageClassificationTest}）。</p>
     *
     * @param context          事件所属 Context
     * @param newPage          事件对应的新页
     * @param frameworkManaged {@link #createPage} 是否正在创建该页（由
     *                         {@link #MANAGED_PAGE_CREATION_IN_FLIGHT} 判定）
     * @return 判定结果（含 Context 内页数与 about:blank 标记）
     * @apiNote 框架内部能力（包级私有）。
     */
    static NewPageEvent classifyNewPageEvent(BrowserContext context, Page newPage, boolean frameworkManaged) {
        return new NewPageEvent(frameworkManaged, countPagesSafely(context), urlSafely(newPage));
    }

    /**
     * 记录 onPage 事件的归属判定日志。
     *
     * <p><b>受管页</b>：about:blank 是 {@code newPage()} 后、首次 {@code navigate()} 前的正常状态，
     * 非异常，故仅 verbose 记录并显式标注 {@code expected}。</p>
     *
     * <p><b>额外页</b>：恒记 INFO（含 aboutBlank 标记）；当 Context 内页数 &gt;1 时追加 WARN 与归属清单，
     * 便于现场判定是合法弹窗、测试造页还是泄漏。</p>
     *
     * @param context 事件所属 Context
     * @param newPage 事件对应的新页（用于清单中标 {@code (new)}）
     * @param event   {@link #classifyNewPageEvent} 的判定结果
     * @apiNote 框架内部能力（包级私有）。
     */
    static void logNewPageEvent(BrowserContext context, Page newPage, NewPageEvent event) {
        if (event.frameworkManaged) {
            VerboseLogging.logInfoIfVerbose(logger,
                    "New page created by framework (managed): ctx=#{} pages={} url={}{}",
                    System.identityHashCode(context), event.pageCount, event.url,
                    event.aboutBlank ? " [about:blank until first navigate — expected]" : "");
            return;
        }
        //  非框架创建 ⇒ 业务 window.open / target=_blank 弹窗，或某路径泄漏出的额外页。
        logger.info("[multi-page] Extra page (not framework-managed): ctx=#{} pages={} url={} aboutBlank={}",
                System.identityHashCode(context), event.pageCount, event.url, event.aboutBlank);
        if (event.pageCount > 1) {
            logger.warn("[multi-page] Context=#{} now holds {} pages (expected 1 managed): {}"
                            + " ([managed]=框架登记的当前受管页；[extra]=非登记页：业务 window.open 弹窗 / 测试直接 newPage / 泄漏)",
                    System.identityHashCode(context), event.pageCount,
                    describeContextPages(context, newPage));
        }
    }

    /** 安全读取 Context 内页数（Context 已关闭/失效时返回 0，不抛异常）。 */
    private static int countPagesSafely(BrowserContext context) {
        try {
            List<Page> pages = context.pages();
            return pages == null ? 0 : pages.size();
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "pages() unavailable while logging new page: {}", e.toString());
            return 0;
        }
    }

    /** 安全读取 Page 的 URL（页面/连接已失效时返回占位符，不抛异常）。 */
    private static String urlSafely(Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "<unavailable>";
        }
    }

    /** 是否为「尚未导航」的初始地址（框架受管页在首次 {@code navigate()} 前的正常状态）。 */
    private static boolean isAboutBlank(String url) {
        return url == null || url.isEmpty() || "about:blank".equals(url);
    }

    /**
     * 渲染 Context 内所有页面的归属清单（{@code [managed]} / {@code [extra]}，本次新出现的页标 {@code (new)}），
     * 用于「同 Context 多页」告警时现场判定是合法弹窗还是泄漏页。
     *
     * <p>归属判据：与 {@link PlaywrightManager#currentPageForThread()}（框架本线程受管页）或
     * 用例级 {@code PAGE_KEY}（{@code createNewContextAndPage} 路径）同一实例者为 {@code [managed]}，
     * 其余为 {@code [extra]}（业务弹窗 / 测试直接 {@code context.newPage()} / 泄漏页）。</p>
     *
     * @param context  目标 Context
     * @param appeared 本次 onPage 回调对应的新页（标 {@code (new)}）
     * @return 可读清单；{@code pages()} 不可用时返回降级说明（不抛异常）
     */
    private static String describeContextPages(BrowserContext context, Page appeared) {
        StringBuilder sb = new StringBuilder();
        Page managed = PlaywrightManager.currentPageForThread();
        Page registered = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
        try {
            for (Page p : context.pages()) {
                if (!sb.isEmpty()) {
                    sb.append(" || ");
                }
                sb.append((p == managed || p == registered) ? "[managed] " : "[extra] ");
                sb.append(p == appeared ? "(new) " : "");
                sb.append(p.isClosed() ? "(closed) " : "");
                sb.append(urlSafely(p));
            }
        } catch (Exception e) {
            sb.append("<pages() unavailable: ").append(e.getClass().getSimpleName()).append('>');
        }
        return sb.toString();
    }


}
package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch.RouteAction;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构分层门禁（T0-1 风格）：固化 route2 的"驱动协议调用收口" —— 所有同步阻塞的 Playwright
 * 驱动协议调用（route / unroute / Route.close / fetch）必须经由有界守卫
 * （{@code GuardedDriverCall} / {@code BoundedOps} / IO 线程池），禁止在 binding / dispatch 等
 * 业务代码里直接调用原生驱动 API。
 *
 * <p><b>背景</b>：{@code context.route()} / {@code context.unroute()} 在客户端是 {@code NO_TIMEOUT}
 * 且由调用线程自己泵消息，驱动不响应时会拖住调用线程 —— 故必须经有界守卫收口。任何"绕过守卫直接调
 * 原生 API"的改动都会让本测试失败，把根因挡在编译 / 架构期。</p>
 *
 * <p><b>2026-09-29 更正</b>：旧表述"route 注册卡 14 分钟 / unroute 卡 11 分钟"不准确 —— 探针实测该调用
 * 在界值后约 3~5 秒即抛 {@code Object doesn't exist}（客户端 {@code Connection.dispatch} 未按消息隔离异常），
 * 客户端自 2026-09-30 起改用上游 stock，由本层有界等待兜底。</p>
 *
 * <p><b>资源 / 线程池门禁（评审 23 号 V2-5）</b>：route 内禁止"污染 JVM 级共享池"与"无界线程池"用法 ——
 * {@code CompletableFuture.delayedExecutor}（唯一合法替代：{@code RouteDelayScheduler}）、
 * {@code ForkJoinPool.commonPool()}、{@code Executors.newCachedThreadPool()}。</p>
 *
 * <p><b>F7（2026-10-10）</b>：
 * <ul>
 *   <li><b>裸线程归口</b>：{@code new Thread(...)} 只允许出现在 {@link ThreadFactory} 实现
 *       （具名 daemon，由有界池管理）与 {@code BodyStore} 的 shutdown hook（一次性 JVM 收尾）中；</li>
 *   <li><b>fetch 收口扩展</b>：{@code APIRequestContext.fetch} 只允许在 {@code RouteAction} 内调用；</li>
 *   <li><b>固化 F5 修复</b>：{@code RouteAction.fetch} 的调用方白名单 = {@code RouteDispatcher} 唯一 ——
 *       禁止调度器/IO 线程回调里 fetch（历史缺陷：DELAY 到点回调在 {@code RouteDelayScheduler} 线程
 *       fetch ⇒ 与主线程并发驱动同一条连接 ⇒ O1 竞态面重现）。</li>
 * </ul>
 * </p>
 *
 * <p><b>执行方式（2026-10-10 重构）</b>：改用仓库已验证的<b>纯 JUnit 4 + {@link ClassFileImporter}
 * 程序化导入</b>（与 pw-reports {@code ReportingRouteDecouplingArchTest} 同模式）——此前
 * {@code @AnalyzeClasses + ArchUnitRunner} 在本环境导入集为空（规则空转，负向探针证明），
 * 手动导入可审计、可复现。每个规则一个 {@code @Test}，静态缓存导入结果。</p>
 *
 * <p>@apiNote 仅测试期执行；不影响运行时依赖（route2 运行时零额外依赖）。</p>
 */
public class RouteArchitectureTest {

    private static final JavaClasses ROUTE_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.hsbc.cmb.hk.dbb.automation.framework.route");

    /**
     * 驱动生命周期调用（route / unroute / Route.close）必须经 {@code GuardedDriverCall} 收口。
     * 唯二合法业务调用点：{@code PatternBinder}（经 guarded 包裹的 lambda）与 GuardedDriverCall 自身。
     */
    @Test
    public void driverLifecycleCallsMustGoThroughGuardedDriverCall() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..route..")
            .and().doNotHaveSimpleName("PatternBinder")
            .and().doNotHaveSimpleName("GuardedDriverCall")
            .and().doNotHaveSimpleName("GuardedDriverCallImpl")
            .and().doNotHaveSimpleName("GuardedDriverCallRegistry")
            .should().callMethod(BrowserContext.class, "route", String.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "route", String.class, Consumer.class, BrowserContext.RouteOptions.class)
            .orShould().callMethod(BrowserContext.class, "route", Pattern.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "route", Pattern.class, Consumer.class, BrowserContext.RouteOptions.class)
            .orShould().callMethod(BrowserContext.class, "route", Predicate.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "route", Predicate.class, Consumer.class, BrowserContext.RouteOptions.class)
            .orShould().callMethod(BrowserContext.class, "unroute", String.class)
            .orShould().callMethod(BrowserContext.class, "unroute", String.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "unroute", Pattern.class)
            .orShould().callMethod(BrowserContext.class, "unroute", Pattern.class, Consumer.class)
            .orShould().callMethod(BrowserContext.class, "unroute", Predicate.class)
            .orShould().callMethod(BrowserContext.class, "unroute", Predicate.class, Consumer.class)
            .orShould().callMethod(Route.class, "close");
        rule.check(ROUTE_CLASSES);
    }

    /**
     * 驱动 fetch（拦截真实响应）必须经 {@code BoundedOps} / IO 线程池
     * （{@code RouteAction} / {@code RouteIoExecutor} 已收口）。禁止其它业务代码直接调
     * {@code Route.fetch}（事件线程严禁同步 fetch，否则卡死主链）。
     */
    @Test
    public void driverFetchMustGoThroughBoundedOps() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..route..")
            .and().doNotHaveSimpleName("RouteAction")
            .and().doNotHaveSimpleName("RouteIoExecutor")
            .and().doNotHaveSimpleName("GuardedDriverCall")
            .and().doNotHaveSimpleName("GuardedDriverCallImpl")
            .and().doNotHaveSimpleName("GuardedDriverCallRegistry")
            .should().callMethod(Route.class, "fetch")
            .orShould().callMethod(Route.class, "fetch", Route.FetchOptions.class);
        rule.check(ROUTE_CLASSES);
    }

    /**
     * V2-5（评审 23 号）：禁止 {@code CompletableFuture.delayedExecutor} —— 其两参重载会把任务落到
     * {@code ForkJoinPool.commonPool()}（JVM 级共享池），与模块"有界并发"原则冲突。
     * 唯一合法替代：{@code RouteDelayScheduler}（模块自有 daemon、线程数有界）。
     */
    @Test
    public void noForkJoinCommonPoolDelayInRoute() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..route..")
            .should().callMethod(CompletableFuture.class, "delayedExecutor", long.class, java.util.concurrent.TimeUnit.class)
            .orShould().callMethod(CompletableFuture.class, "delayedExecutor", long.class, java.util.concurrent.TimeUnit.class, java.util.concurrent.Executor.class);
        rule.check(ROUTE_CLASSES);
    }

    /**
     * V2-5：禁止 {@code ForkJoinPool.commonPool()} —— JVM 级共享、并行度 = 核数 - 1，不受模块约束。
     */
    @Test
    public void noForkJoinCommonPoolInRoute() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..route..")
            .should().callMethod(ForkJoinPool.class, "commonPool");
        rule.check(ROUTE_CLASSES);
    }

    /**
     * V2-5：禁止 {@code Executors.newCachedThreadPool}（无界线程池）。有界池（newFixedThreadPool /
     * newScheduledThreadPool / newSingleThreadScheduledExecutor）不受限制 —— 模块既有 daemon 先例均属合法。
     */
    @Test
    public void noUnboundedThreadPoolInRoute() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..route..")
            .should().callMethod(Executors.class, "newCachedThreadPool")
            .orShould().callMethod(Executors.class, "newCachedThreadPool", java.util.concurrent.ThreadFactory.class);
        rule.check(ROUTE_CLASSES);
    }

    /**
     * F7（2026-10-10）：<b>禁止裸线程启动</b>——{@code Thread.start()} 只允许出现在两类合法位置之外
     * 的任何类中即违规：
     * <ol>
     *   <li><b>{@link ThreadFactory} 实现</b>：模块在 {@code ThreadFactory.newThread} 内构造具名 daemon
     *       线程（{@code sweeper} / {@code HangWatchdog} / {@code RouteDelayScheduler} /
     *       {@code RouteIoExecutor} / {@code RouteRetireExecutor} / {@code GuardedDriverCallImpl}），
     *       它们由<b>有界</b>线程池负责 start —— 池内不显式调 {@code Thread.start}，天然不命中；</li>
     *   <li><b>{@code BodyStore}</b>：{@code Runtime.addShutdownHook} 的落盘目录清理钩子 ——
     *       JVM 收尾时自行启动、非长驻 —— 不显式调 {@code Thread.start}，天然不命中。</li>
     * </ol>
     * <b>实现说明</b>：用 {@code Thread.start()} 而非 {@code Thread} 构造器 —— 本环境 ArchUnit 1.3.0
     * 的 {@code callConstructor} 不匹配构造器调用（负向探针实测），而 {@code start()} 是裸线程被
     * 真正启动的唯一入口，语义更贴近危害面（不可观测、不可回收、稀释 3 池线程预算）。
     */
    @Test
    public void noBareThreadsOutsideThreadFactoryOrShutdownHook() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..route..")
            .and().doNotImplement(ThreadFactory.class)
            .and().doNotHaveSimpleName("BodyStore")
            .should().callMethod(Thread.class, "start");
        rule.check(ROUTE_CLASSES);
    }

    /**
     * F7：<b>驱动 fetch（真实响应拦截）必须经 {@code RouteAction} 收口</b>——{@code APIRequestContext.fetch}
     * （同步 HTTP + 驱动泵连接）只允许在 {@code RouteAction.fetch} 内调用。
     * {@link #driverFetchMustGoThroughBoundedOps()} 查的是 {@code Route.fetch}（Playwright 原生拦截
     * 通道，已弃用）；本规则覆盖<b>实际使用</b>的 {@code APIRequestContext} 通道（O1/O1b 方案 A：
     * 事件线程拦截快照 + {@code page.request()} 从零重发）。
     */
    @Test
    public void apiRequestFetchMustGoThroughRouteAction() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..route..")
            .and().doNotHaveSimpleName("RouteAction")
            .should().callMethod(APIRequestContext.class, "fetch",
                    String.class, com.microsoft.playwright.options.RequestOptions.class);
        rule.check(ROUTE_CLASSES);
    }

    /**
     * F7（固化 F5 修复）：<b>{@code RouteAction.fetch} 的调用方白名单 = {@code RouteDispatcher} 唯一</b>。
     *
     * <p>fetch 是同步协议泵调用，只允许发生在<b>调用线程（事件线程/泵线程）</b>——即
     * {@code RouteDispatcher.dispatchMock}（intercept 就地 fetch）与
     * {@code RouteDispatcher.materializeResponse}（DELAY+intercept 预取物化，2026-10-10）。
     * <b>禁止在调度器/IO 线程回调里 fetch</b>（历史缺陷：DELAY 到点回调在
     * {@code RouteDelayScheduler} 线程 fetch ⇒ 与主线程并发驱动同一条连接 ⇒ O1 竞态面重现）。
     * 未来若有人把 fetch 挪进 {@code RouteDelayScheduler} / IO lambda 的宿主类，本规则立即失败。</p>
     */
    @Test
    public void fetchMustNotBeCalledFromSchedulerOrIoThreads() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..route..")
            .and().doNotHaveSimpleName("RouteDispatcher")
            .and().doNotHaveSimpleName("RouteAction")
            .should().callMethod(RouteAction.class, "fetch",
                    APIRequestContext.class, RouteAction.RequestSnapshot.class);
        rule.check(ROUTE_CLASSES);
    }
}

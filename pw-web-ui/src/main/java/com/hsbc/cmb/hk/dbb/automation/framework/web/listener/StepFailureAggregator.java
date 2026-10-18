package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.SoftAssertions;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionProbe;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageInteractionMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 *  收口 {@code PlaywrightListener} 的「框架级失败传播」职责
 * （原 checkAndMarkApiAssertionFailures / checkAndFailOnApiAssertions / checkAndFailOnPageErrors 三方法）。
 *
 * <p>这三方法是 B 改造中在步骤结束时兜底抛 AssertionError、使 IDE 正确标红的核心 seam，
 * 与监听器的生命周期编排正交。迁出后监听器只负责事件路由，失败聚合逻辑在此单一收敛，
 * 并复用 {@link ListenerGuard} 的 apiFailureAlreadyHandled 守卫与 {@link ListenerPerfStats} 的数据记录，
 * 保证与原实现行为完全等价（含防重入死循环与防重复打印）。
 *
 * <p><b>去 框架 改造（D4）：</b>本类不再依赖 框架 类型——证据经 ReportingBridge 端口
 * （由 pw-reports 的 SerenityReportingBridge 实现）写入报告；失败标记不再调用 {@code StepEventBus.testFailed}
 * 或置位 {@code TestOutcome}，统一由"抛 AssertionError"完成（serenity 事件总线会把该异常回传
 * 使场景判红），与步骤级失败传播语义一致。</p>
 */
final class StepFailureAggregator {

    private static final Logger logger = LoggerFactory.getLogger(StepFailureAggregator.class);

    private StepFailureAggregator() {
    }

    /**
     * 核心方法：检查 API 断言失败并上报证据（testFinished 兜底路径）。
     *
     * <p>失败标记已由步骤级 {@link #checkAndFailOnApiAssertions()} 的抛错完成，此处仅做证据记录；
     * 原生环境无 框架 {@code TestOutcome} 可置位，故不再调用 {@code result.setResult}。
     *
     * @param testName 当前测试名（用于报告归属）
     */
    static void checkAndMarkApiAssertionFailures(String testName) {
        // ROUTE-P0-1：用 resolveFailureCapture() 兜底解析失败上下文（优先 per-context，否则 SHARED），
        // 避免 route 事件线程经 SHARED 记录的失败标志被漏检。
        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
        if (lifecycle == null) {
            //  route 生命周期未注册（如非 route 场景 / 未引入 route 模块）：无失败上下文可解析，安全跳过。
            return;
        }
        CaptureContext context = lifecycle.resolveFailureCapture();
        if (context == null) {
            return;
        }

        long timeoutMs = FrameworkConfigManager.getLong(WebFrameworkConfig.API_ASSERTION_WAIT_TIMEOUT);
        try {
            boolean completed = context.awaitCompletion(timeoutMs);
            if (!completed) {
                logger.warn("Timed out waiting for API requests to complete ({} active, timeout={}ms)",
                        context.getActiveRequests(), timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted while waiting for API requests to complete");
        }

        if (context.hasAssertionFailures()) {
            //  防重复：若 checkAndFailOnApiAssertions 已在步骤结束时触发过一次，
            // 则跳过详细日志与报告记录，避免 stepFailed 回调与 testFinished 各调一次导致重复打印。
            boolean alreadyHandledAtStep = ListenerGuard.guards().isApiFailureAlreadyHandled();

            if (!alreadyHandledAtStep) {
                logger.error("API assertions failed for test: {}", testName);

                String failureReport = context.buildFailureReport();
                logger.error("API assertion failure details:\n{}", failureReport);

                ListenerPerfStats.record(testName, "apiAssertionFailure",
                        "API assertions failed during test execution");
                ListenerPerfStats.record(testName, "apiAssertionFailureDetails", failureReport);

                ReportingBridgeRegistry.get().recordReportData("API ASSERTION FAILURES DETECTED", failureReport);
            }
            //  失败标记已由步骤级抛错完成（原生环境无 框架 TestOutcome 可置位）。
        }
    }

    /**
     * D3-2：场景末统一上报<b>软断言</b>失败（collect → assertAll 入报告）。
     *
     * <p>与 {@link #checkAndMarkApiAssertionFailures} 走<b>同一 seam</b>。原生环境下，软断言失败必须在此
     * 抛出 AssertionError 才能使场景判红（不再依赖 框架 的 result 置位）。抛错发生在场景收尾，
     * 由适配器经 serenity 事件总线回传，框架 / IDE runner 据此标红。</p>
     */
    static void checkAndMarkSoftAssertionFailures() {
        if (!SoftAssertions.hasFailures()) {
            return;
        }
        //  先渲染再清空：保证"报告里看到的"与"记录的"是同一份内容，且绝不把失败带到下一场景
        String details = SoftAssertions.renderFailures();
        SoftAssertions.clearForCurrentThread();

        logger.error("Soft assertion failures detected at scenario end:\n{}", details);
        ReportingBridgeRegistry.get().recordReportData("Soft Assertion Failures", details);

        //  原生环境：抛出以使场景判红（assertAll 语义）
        throw new AssertionError("Soft assertion failures detected at scenario end:\n" + details);
    }

    /**
     * 框架级：每个步骤结束时自动检查 API 断言是否失败，即时抛 AssertionError 使场景判红。
     * 通过 apiFailureAlreadyHandled 守卫防止同一 case 内回调链递归重入（死循环）。
     */
    static void checkAndFailOnApiAssertions() {
        // ROUTE-P0-1：用 resolveFailureCapture() 兜底解析失败上下文（优先 per-context，否则 SHARED），
        // 避免 route 事件线程经 SHARED 记录的失败标志被漏检。
        RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
        if (lifecycle == null) {
            //  route 生命周期未注册（如非 route 场景 / 未引入 route 模块）：无失败上下文可解析，安全跳过。
            return;
        }
        CaptureContext context = lifecycle.resolveFailureCapture();
        if (context == null) {
            return;
        }

        if (!context.hasAssertionFailures()) {
            return;
        }

        //  防重入：同一 case 里只标记一次，防止回调链路再次进入此方法。
        if (ListenerGuard.guards().isApiFailureAlreadyHandled()) {
            return;
        }

        if (context.getActiveRequests() > 0) {
            try {
                boolean completed = context.awaitCompletion(5000);
                if (!completed) {
                    logger.warn("Step finished but {} API request(s) still active after 5s timeout",
                            context.getActiveRequests());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Interrupted while waiting for API request completion in step check");
            }
        }

        //  标记已处理，必须在调用之前设置（防回调重入）。
        ListenerGuard.guards().setApiFailureAlreadyHandled(true);

        String report = context.buildFailureReport();
        String details = context.buildFailureDetails();
        logger.error("API assertions failed during step - failing scenario:\n{}", report);
        ReportingBridgeRegistry.get().recordReportData("API Assertion Failures", details);

        //  关键：直接在 stepFinished 回调中抛 AssertionError，使异常沿
        //   StepInterceptor → Cucumber → JUnit 传播，令 IDE runner 正确标红。
        throw new AssertionError("API assertion failures detected — failing scenario:\n" + details);
    }

    /**
     * Route：每个步骤结束时检查 MONITOR 断言失败并即时抛 AssertionError。
     *
     * <p>与 {@link #checkAndFailOnApiAssertions}（老版 pw-route）同一 seam，但走独立的
     * {@link RouteAssertionRegistry} SPI：探针未注册（classpath 无 pw-route 或未使用）时
     * 空判断跳过，不改变既有行为。
     *
     * <p>幂等性：探针的 {@code drainAndResolveFailures()} 是<b>消费式</b>——失败被取走即清空，
     * 因此本方法（步骤结束抛错）与 {@link #checkAndMarkRouteAssertionFailures()}
     * （用例收尾标记）天然防重，无需额外的防重入标志；且不会把失败带到下一场景。</p>
     */
    static void checkAndFailOnRouteAssertions() {
        RouteAssertionProbe probe = RouteAssertionRegistry.get();
        if (probe == null) {
            // 路由模块未启用：跳过（与老版 route 未启用语义一致）
            return;
        }
        List<RouteAssertionFailure> failures = probe.drainAndResolveFailures();
        if (failures.isEmpty()) {
            return;
        }
        String report = buildRouteFailureReport(failures);
        logger.error("Route API assertions failed during step - failing scenario:\n{}", report);
        ReportingBridgeRegistry.get().recordReportData("Route API ASSERTION FAILURES DETECTED", report);
        // 直接在 stepFinished 回调中抛 AssertionError，使异常沿
        //  StepInterceptor → Cucumber → JUnit4 传播，令 IDE runner 正确标红。
        throw new AssertionError("Route API assertion failures detected — failing scenario:\n" + report);
    }

    /**
     * Route：用例收尾兜底——检查 MONITOR 断言失败并上报证据。
     *
     * <p>与老版 {@link #checkAndMarkApiAssertionFailures} 同一 seam 语义：本方法在
     * 场景收尾阶段执行，仅上报证据（不抛错）；若步骤结束路径已抛过 AssertionError，
     * 本方法 drain 到空列表自然跳过——消费式语义保证两条路径不会重复打印或重复标记。</p>
     */
    static void checkAndMarkRouteAssertionFailures() {
        RouteAssertionProbe probe = RouteAssertionRegistry.get();
        if (probe == null) {
            return;
        }
        List<RouteAssertionFailure> failures = probe.drainAndResolveFailures();
        if (failures.isEmpty()) {
            return;
        }
        String report = buildRouteFailureReport(failures);
        logger.error("Route API assertions failed at scenario end:\n{}", report);
        ReportingBridgeRegistry.get().recordReportData("Route API ASSERTION FAILURES DETECTED", report);
    }

    /** 构建 V2 断言失败明细（每条一行 summary）。 */
    private static String buildRouteFailureReport(List<RouteAssertionFailure> failures) {
        StringBuilder sb = new StringBuilder(failures.size() * 96);
        for (RouteAssertionFailure failure : failures) {
            sb.append("  ").append(failure.summary()).append(System.lineSeparator());
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 框架级未捕获页面异常检查（每个步骤结束时兜底执行）。
     * 仅当 playwright.page.error.failOnError=true 时生效：将 PageEventMonitor 收集的未捕获 JS 异常
     * 经 框架 标记失败并抛出。默认关闭（仅记录日志）；drain 即清空，天然幂等。
     */
    static void checkAndFailOnPageErrors() {
        if (!WebFrameworkConfig.PLAYWRIGHT_PAGE_ERROR_FAIL.getBooleanValue()) {
            return;
        }
        List<String> errors = PageEventMonitor.drainPendingPageErrors();
        if (errors.isEmpty()) {
            return;
        }
        String details = String.join("\n", errors);
        logger.error("Uncaught page errors detected during step - failing scenario:\n{}", details);
        ReportingBridgeRegistry.get().recordReportData("Uncaught Page Errors", details);
        throw new AssertionError("Uncaught page errors detected — failing scenario:\n" + details);
    }

    /**
     * 步骤失败时回放交互诊断（纯观测，不标记失败、不抛异常）。
     * 经 {@link PageInteractionMonitor} 取出当前线程的导航轨迹与未受管弹窗清单，补充失败上下文，
     * 便于还原"失败前页面去过哪些地址 / 是否漏捕获 app 弹窗"。幂等消费，不跨步骤重复。
     */
    static void logInteractionDiagnosticsOnFailure() {
        String trail = PageInteractionMonitor.drainNavigationTrail();
        String popups = PageInteractionMonitor.drainUnmanagedPopups();
        if (trail.isEmpty() && popups.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (!trail.isEmpty()) {
            sb.append("[导航轨迹 - 失败前页面去过]\n").append(trail);
        }
        if (!popups.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("[未受管弹窗 - 框架未认领]\n").append(popups);
        }
        String report = sb.toString();
        logger.warn("[interaction-diagnostics] 步骤失败交互诊断:\n{}", report);
        ReportingBridgeRegistry.get().recordReportData("Interaction Diagnostics (Failure)", report);
    }
}

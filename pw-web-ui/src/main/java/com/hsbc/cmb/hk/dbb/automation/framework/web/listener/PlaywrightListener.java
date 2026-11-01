package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.logging.LogContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge.PlaywrightLifecycleBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.CapturedScreenshot;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.result.ResultReporters;
import com.hsbc.cmb.hk.dbb.automation.framework.common.result.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkCore;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStatus;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Playwright 集成主监听器（原生生命周期版，已去 框架 直接依赖）。
 *
 * <p><b>D4 改造：</b>本类不再实现 serenity 的 {@code StepListener}，改为实现框架原生
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkLifecycleListener}，
 * 由 {@code ThucydidesStepsListenerAdapter}（serenity → 原生翻译桥）把 serenity 事件总线上的
 * 事件翻译成本接口的 6 个原生回调。serenity 仍作为事件机制存在（"借助 serenity 机制"），
 * 但本类零 serenity import：报告写入经 ReportingBridge 端口（pw-reports 内的 SerenityReportingBridge 实现），
 * 业务监听经 {@link FrameworkListenerBridge} 桥。
 *
 * <p>职责不变：per-thread 并发守卫、性能统计、截图采集、API/路由/页面错误失败聚合、
 * 路由状态重置、场景级资源清理与全局 teardown、MDC 与业务监听桥接。
 */
public class PlaywrightListener implements
        com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkLifecycleListener {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightListener.class);

    private final ScreenshotStrategy screenshotStrategy;

    // ═══════════════════════════════════════════════════════════
    // 线程状态键（per-thread，与 框架 事件总线 ThreadLocal 对齐）
    // ═══════════════════════════════════════════════════════════
    private static final ContextKey<TestResult> CURRENT_TEST_RESULT_KEY =
            ContextKey.of("playwright.listener.currentTestResult", TestResult.class);
    private static final ContextKey<String> CURRENT_STEP_KEY =
            ContextKey.of("playwright.listener.currentStep", String.class);
    private static final ContextKey<Long> CURRENT_TEST_START_TIME_KEY =
            ContextKey.of("playwright.listener.testStartTime", Long.class);
    private static final ContextKey<Long> CURRENT_STEP_START_TIME_KEY =
            ContextKey.of("playwright.listener.stepStartTime", Long.class);
    private static final ContextKey<Integer> CURRENT_STEP_SCREENSHOTS_TAKEN_KEY =
            ContextKey.of("playwright.listener.stepScreenshotsTaken", Integer.class);
    private static final ContextKey<Boolean> FAILURE_SCREENSHOTS_ALREADY_SENT_KEY =
            ContextKey.of("playwright.listener.failureScreenshotsAlreadySent", Boolean.class);
    private static final ContextKey<Boolean> STEP_FINISH_PROCESSED_KEY =
            ContextKey.of("playwright.listener.stepFinishProcessed", Boolean.class);
    private static final ContextKey<Boolean> TEARDOWN_COMPLETED_KEY =
            ContextKey.of("playwright.listener.teardownCompleted", Boolean.class);

    /** 当前场景信息（onScenarioStarted 时记录，供 onScenarioFinished 在信息缺失时回退）。 */
    private volatile LifecycleScenarioInfo currentScenarioInfo;

    // ═══════════════════════════════════════════════════════════
    // 构造函数 / 初始化
    // ═══════════════════════════════════════════════════════════
    public PlaywrightListener() {
        this.screenshotStrategy = ScreenshotStrategy.from();
    }

    // ═══════════════════════════════════════════════════════════
    // 原生生命周期事件
    // ═══════════════════════════════════════════════════════════

    /** 测试运行开始（对应 serenity testSuiteStarted）：全局一次性初始化 + 日志，不依赖 Story。 */
    @Override
    public void onTestRunStarted() {
        //  全局一次性初始化（Playwright.create / 监听器注册 / frameworkState）放这里——每 run 一次，
        //  与 javadoc 意图一致；per-scenario 的 initializeForScenario 仍留 onScenarioStarted。
        //  幂等（isInitialized 短路）；onScenarioStarted 的 beforeTest() 仍保留 !isInitialized 兜底重试。
        FrameworkCore.getInstance().initialize();
        logger.debug("Framework lifecycle: test run started");
    }

    /** 场景开始（对应 serenity testStarted）：建立 MDC、重置守卫、记录起点、桥接业务监听。 */
    @Override
    public void onScenarioStarted(LifecycleScenarioInfo info) {
        String scenarioName = info != null ? info.name() : "unknown";
        this.currentScenarioInfo = info;

        //  注入当前场景标签到原生 holder（供 AutoBrowserProcessor 在 getBrowserType 时消费；去 框架 后唯一标签源）
        AutoBrowserProcessor.setScenarioTags(info != null ? info.tags() : null);

        // 场景开始闩锁：scenarioStarted() 返回 true＝重入（已处于场景中，跳过）；false＝首次进入（继续）。
        // 修复 WIP 回退：此前误写 !scenarioStarted()，导致首次进入被跳过、 FrameworkCore.beforeTest() 初始化不触发。
        if (ListenerGuard.guards().scenarioStarted()) {
            return;
        }

        try {
            //  设置 MDC 供日志追踪（保持生产溯源一致）
            LogContext.beginScenario(scenarioName);

            //  重置当前场景状态（守卫 / 计时 / 截图计数）
            resetCurrentScenarioState();

            // 记录场景开始时间（性能统计）
            long start = System.currentTimeMillis();
            TestContextHolder.get().set(CURRENT_TEST_START_TIME_KEY, start);
            ListenerPerfStats.markTotal();

            String uniqueName = generateUniqueScenarioName(scenarioName);
            TestContextHolder.get().set(ListenerGuard.CURRENT_TEST_NAME_KEY, uniqueName);
            TestContextHolder.get().set(CURRENT_TEST_RESULT_KEY, TestResult.PENDING);

            //  桥接业务监听器（框架原生 FrameworkListener 接口，供业务订阅场景开始）
            FrameworkListenerBridge.beforeScenario(uniqueName);

            //  丢弃上一场景残留的待报告 API 记录，避免跨场景串扰
            ReportingBridgeRegistry.get().discardPendingApiOperations();

            //  重置当前场景的路由状态（每个场景独立隔离，关键修复越权访问）
            resetCurrentScenarioRouteState(uniqueName);

            //  关键修复：在测试开始前初始化框架（确保 PlaywrightManager 等已就绪）
            FrameworkCore.getInstance().beforeTest();

            //  BEFORE_AND_AFTER_EACH_STEP 策略：场景开始即截图
            if (ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP.equals(screenshotStrategy)) {
                takeScreenshotAndRegister(uniqueName + "-scenario-start");
            }

            // 记录场景开始的面包屑（便于失败排障）
            recordTestData("scenarioStartTimestamp", Instant.now().toString());

            logger.debug("Scenario started: {}", uniqueName);
        } catch (Exception e) {
            logger.error("Error in onScenarioStarted for: {}", scenarioName, e);
        }
    }

    /** 步骤开始（对应 serenity stepStarted）：重置步骤级状态、桥接业务监听、按需截图。 */
    @Override
    public void onStepStarted(LifecycleStepInfo step) {
        if (step == null) {
            return;
        }
        String stepTitle = step.stepName() != null ? step.stepName() : "step";

        // 步骤开始闩锁：stepStarted() 返回 true＝嵌套（重入，跳过）；false＝最外层（继续）。
        // 与 onScenarioStarted 同源修复：去掉误加的反转 !，避免最外层步骤处理被跳过。
        if (ListenerGuard.guards().stepStarted()) {
            return;
        }

        try {
            // 重置步骤级状态（截图计数 / 失败截图标记 / 步骤处理标记）
            TestContextHolder.get().set(CURRENT_STEP_KEY, stepTitle);
            TestContextHolder.get().set(CURRENT_STEP_START_TIME_KEY, System.currentTimeMillis());
            TestContextHolder.get().set(CURRENT_STEP_SCREENSHOTS_TAKEN_KEY, 0);
            TestContextHolder.get().set(FAILURE_SCREENSHOTS_ALREADY_SENT_KEY, Boolean.FALSE);
            TestContextHolder.get().set(STEP_FINISH_PROCESSED_KEY, Boolean.FALSE);

            //  桥接业务监听器
            FrameworkListenerBridge.beforeStep(stepTitle);

            //  重置当前步骤的截图列表（步骤级隔离）
            currentStepScreenshots().clear();

            //  BEFORE_AND_AFTER_EACH_STEP：步骤开始即截图（AFTER_EACH_STEP 仅在结束截，避免截图风暴）
            if (screenshotStrategy.shouldTakeScreenshotBeforeEachStep()) {
                captureScreenshotAtStepLevel(stepTitle);
            }

            // 记录步骤开始的面包屑
            recordTestData("stepStartTimestamp", Instant.now().toString());

            logger.debug("Step started: {}", stepTitle);
        } catch (Exception e) {
            logger.error("Error in onStepStarted: {}", stepTitle, e);
        }
    }

    /** 步骤结束（对应 serenity stepFinished/stepFailed/lastStepFailed/stepIgnored）。 */
    @Override
    public void onStepFinished(LifecycleStepResult stepResult) {
        if (stepResult == null) {
            return;
        }
        LifecycleStatus status = stepResult.status() != null ? stepResult.status() : LifecycleStatus.PASSED;
        boolean isSuccess = status == LifecycleStatus.PASSED || status == LifecycleStatus.SKIPPED
                || status == LifecycleStatus.IGNORED || status == LifecycleStatus.PENDING;

        //  failure 截图只需发一次（lastStepFailed 与 stepFailed 都会触发），防止重复
        if (!isSuccess && Boolean.TRUE.equals(
                TestContextHolder.get().get(FAILURE_SCREENSHOTS_ALREADY_SENT_KEY))) {
            return;
        }
        if (Boolean.TRUE.equals(TestContextHolder.get().get(STEP_FINISH_PROCESSED_KEY))) {
            return;
        }
        if (!ListenerGuard.guards().isStepActive()) {
            return;
        }

        String stepTitle = stepResult.stepName() != null ? stepResult.stepName()
                : (TestContextHolder.get().get(CURRENT_STEP_KEY) != null
                ? TestContextHolder.get().get(CURRENT_STEP_KEY) : "step");

        boolean outermost = ListenerGuard.guards().isOutermostStep();
        ListenerGuard.guards().stepFinished();

        //  嵌套步骤的"成功"收尾不重复处理；失败无论嵌套层级都处理（首次）
        if (isSuccess && !outermost) {
            return;
        }

        try {
            TestContextHolder.get().set(STEP_FINISH_PROCESSED_KEY, Boolean.TRUE);

            //  失败：补截图 + 记录失败诊断 + 聚合框架级失败
            if (!isSuccess) {
                TestContextHolder.get().set(FAILURE_SCREENSHOTS_ALREADY_SENT_KEY, Boolean.TRUE);
                captureScreenshotOnFailure(stepResult.error());
                logInteractionDiagnosticsOnFailure();
                aggregateStepLevelFailures();
            } else {
                //  成功：AFTER_EACH_STEP / BEFORE_AND_AFTER_EACH_STEP 步骤结束截图
                if (screenshotStrategy.shouldTakeScreenshotFor(LifecycleStepInfo.builder()
                        .stepName(stepTitle).build())) {
                    captureScreenshotAtStepLevel(stepTitle);
                }
            }

            //  桥接业务监听器（afterStep）
            FrameworkListenerBridge.afterStep(stepTitle);

            //  原生挂载当前步骤截图到报告（替代 serenity StepEventBus.stepFinished）
            List<CapturedScreenshot> screenshots = new ArrayList<>(currentStepScreenshots());
            currentStepScreenshots().clear();
            mergeAndAttachScreenshots(screenshots);

            recordTestData("stepFinishTimestamp", String.valueOf(System.currentTimeMillis()));
            logger.debug("Step finished: {} (success={})",
                    stepTitle, isSuccess);
        } catch (Exception e) {
            logger.error("Error in onStepFinished: {}", stepTitle, e);
        }
    }

    /** 场景结束（对应 serenity testFinished/testSkipped/testIgnored/testFailed）。 */
    @Override
    public void onScenarioFinished(LifecycleScenarioResult result) {
        LifecycleScenarioInfo info = result != null && result.info() != null
                ? result.info() : currentScenarioInfo;
        LifecycleStatus status = result != null && result.status() != null
                ? result.status() : LifecycleStatus.PENDING;
        String scenarioName = info != null && info.name() != null
                ? info.name() : (TestContextHolder.get().get(ListenerGuard.CURRENT_TEST_NAME_KEY) != null
                ? TestContextHolder.get().get(ListenerGuard.CURRENT_TEST_NAME_KEY) : "unknown");
        List<String> tags = info != null ? info.tags() : null;

        if (ListenerGuard.guards().scenarioFinished()) {
            return;
        }
        if (!ListenerGuard.guards().isScenarioActive()) {
            logger.debug("Skipping onScenarioFinished - scenarioStarted was not called for: {}", scenarioName);
            return;
        }

        long stop = System.currentTimeMillis();
        Long start = TestContextHolder.get().get(CURRENT_TEST_START_TIME_KEY);
        long durationMs = start != null ? (stop - start) : 0;

        try {
            //  记录场景结果到上报端口（汇总报告 / 框架 桥）
            TestResult frameworkResult = toFramework(status);

            //  性能统计：按结果计数（优先于下游上报，确保即便上报 / 截图失败也能正确计入）
            markScenarioResult(scenarioName, frameworkResult);

            ResultReporters.reportScenario(scenarioName, frameworkResult, durationMs);

            //  标记当前场景结果（供截图策略判断）
            TestContextHolder.get().set(CURRENT_TEST_RESULT_KEY, frameworkResult);

            //  失败场景：补截图 + 记录失败诊断
            if (status == LifecycleStatus.FAILED) {
                captureScreenshotAtScenarioLevel(scenarioName, frameworkResult);
                logInteractionDiagnosticsOnFailure();
            }

            //  场景级失败聚合（API / 路由 / 软断言 / 页面错误）—— 在步骤级已抛错的兜底证据上报
            aggregateScenarioLevelFailures(scenarioName);

            //  桥接业务监听器（afterScenario 带 failed 标志）
            FrameworkListenerBridge.afterScenario(scenarioName, status == LifecycleStatus.FAILED);

            //  原生挂载场景级截图到报告
            List<CapturedScreenshot> screenshots = new ArrayList<>(currentStepScreenshots());
            currentStepScreenshots().clear();
            mergeAndAttachScreenshots(screenshots);

            //  清空 per-scenario 路由状态与截图守卫（场景边界统一释放）
            TestContextHolder.get().set(FAILURE_SCREENSHOTS_ALREADY_SENT_KEY, Boolean.FALSE);

            recordTestData("scenarioFinishTimestamp", Instant.now().toString());
            logger.debug("Scenario finished: {} (result={}, durationMs={})",
                    scenarioName, frameworkResult, durationMs);
        } catch (Exception e) {
            logger.error("Error in onScenarioFinished for: {}", scenarioName, e);
        } finally {
            //  场景级 teardown（关闭 Context/Page、回收残留、刷新路由命中证据、回收 PageObject、释放会话/并发闸门）
            //  —— 去 框架 后原由 ThucydidesStepsListenerAdapter.testFinished 承担，现并入此处，
            //     保证原生生命周期管道也正确释放每场景资源。必须在 endScenario()/LogContext.endScenario() 之前，
            //     以保留场景标识供路由证据与 trace 收尾使用。
            try {
                PlaywrightLifecycleBridge.cleanupForScenario(status == LifecycleStatus.FAILED);
            } catch (Exception e) {
                logger.debug("cleanupForScenario encountered error", e);
            }

            //  场景边界统一复位原生生命周期闩锁 + MDC（防跨场景重入 / 双重处理残留）
            try {
                ListenerGuard.guards().endScenario();
                LogContext.endScenario();
            } catch (Exception e) {
                logger.debug("Error during lifecycle guard/scenario reset", e);
            }
            //  确保 per-scenario 状态清理（避免跨场景残留）
            try {
                cleanupThreadLocals();
            } catch (Exception e) {
                logger.debug("Error during cleanupThreadLocals", e);
            }
            //  清除原生标签 holder（与场景边界同步释放）
            try {
                AutoBrowserProcessor.clearScenarioTags();
            } catch (Exception e) {
                logger.debug("Error during scenario tag cleanup", e);
            }
        }
    }

    /** 测试运行结束（对应 serenity testSuiteFinished/testRunFinished）：全局资源清理。 */
    @Override
    public void onTestRunFinished() {
        try {
            logger.debug("Framework lifecycle: test run finished - global cleanup");
            //  全局清理：关闭所有 Playwright 实例 + 停止路由引擎（仅一次）
            if (!Boolean.TRUE.equals(TestContextHolder.get().get(TEARDOWN_COMPLETED_KEY))) {
                TestContextHolder.get().set(TEARDOWN_COMPLETED_KEY, Boolean.TRUE);
                try {
                    PlaywrightManager.cleanupAll();
                } catch (Exception e) {
                    logger.warn("PlaywrightManager.cleanupAll() failed during run finish", e);
                }
                try {
                    RouteLifecycle routeLifecycle = RouteLifecycleRegistry.get();
                    if (routeLifecycle != null) {
                        routeLifecycle.stopAllContextEngines();
                        routeLifecycle.drainForSuiteTeardown();
                    }
                } catch (Exception e) {
                    logger.debug("Route engine stop skipped/failed", e);
                }
            }
        } catch (Exception e) {
            logger.error("Error in onTestRunFinished", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 失败聚合（步骤级 / 场景级）—— 委托 StepFailureAggregator
    // ═══════════════════════════════════════════════════════════

    /** 步骤级失败聚合：API / 路由 / 页面错误断言（即时抛错使场景判红）。 */
    private void aggregateStepLevelFailures() {
        try {
            StepFailureAggregator.checkAndFailOnApiAssertions();
        } catch (AssertionError ae) {
            throw ae; // 透传：使步骤失败信号沿调用链上抛
        } catch (Exception e) {
            logger.debug("checkAndFailOnApiAssertions unexpected", e);
        }
        try {
            StepFailureAggregator.checkAndFailOnRouteAssertions();
        } catch (AssertionError ae) {
            throw ae;
        } catch (Exception e) {
            logger.debug("checkAndFailOnRouteAssertions unexpected", e);
        }
        try {
            StepFailureAggregator.checkAndFailOnPageErrors();
        } catch (AssertionError ae) {
            throw ae;
        } catch (Exception e) {
            logger.debug("checkAndFailOnPageErrors unexpected", e);
        }
    }

    /** 场景级失败聚合兜底：API / 路由 / 软断言证据上报（不抛错，标记已由步骤级抛错完成）。 */
    private void aggregateScenarioLevelFailures(String scenarioName) {
        StepFailureAggregator.checkAndMarkApiAssertionFailures(scenarioName);
        StepFailureAggregator.checkAndMarkRouteAssertionFailures();
        StepFailureAggregator.checkAndMarkSoftAssertionFailures();
    }

    private void logInteractionDiagnosticsOnFailure() {
        StepFailureAggregator.logInteractionDiagnosticsOnFailure();
    }

    // ═══════════════════════════════════════════════════════════
    // 截图采集（原生 CapturedScreenshot）
    // ═══════════════════════════════════════════════════════════

    private List<CapturedScreenshot> currentStepScreenshots() {
        ContextKey<List<CapturedScreenshot>> key = ContextKey.of(
                "playwright.listener.currentStepScreenshots", (Class<List<CapturedScreenshot>>) (Class<?>) List.class);
        List<CapturedScreenshot> list = TestContextHolder.get().get(key);
        if (list == null) {
            list = new ArrayList<>();
            TestContextHolder.get().set(key, list);
        }
        return list;
    }

    private void mergeAndAttachScreenshots(List<CapturedScreenshot> screenshots) {
        try {
            if (screenshots != null && !screenshots.isEmpty()) {
                ReportingBridge bridge = ReportingBridgeRegistry.get();
                for (CapturedScreenshot shot : screenshots) {
                    bridge.attachScreenshot(shot);
                    //  截图已进报告（attachScreenshot 会把字节写入 per-case screenshots/step-NNN.png），
                    //   框架侧落盘的临时文件随即失去引用价值——删除之，避免 per-case 目录里
                    //   堆积 sha256 命名的孤儿 png（2026-10-20 用户反馈"截图散落"）。
                    //   删除失败只降级日志：孤儿文件不影响报告正确性。
                    deleteFrameworkTempScreenshot(shot);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to attach screenshots", e);
        }
    }

    /** 删除框架为本次上报落盘的临时截图（幂等、静默失败）。 */
    private void deleteFrameworkTempScreenshot(CapturedScreenshot shot) {
        try {
            if (shot != null && shot.pngFile() != null) {
                Files.deleteIfExists(shot.pngFile().toPath());
            }
        } catch (Exception e) {
            logger.debug("Failed to delete framework temp screenshot {}: {}",
                    shot.pngFile(), e.toString());
        }
    }

    private void takeScreenshotAndRegister(String description) {
        try {
            CapturedScreenshot screenshot = FailureScreenshotHandler.capture(description);
            if (screenshot != null) {
                currentStepScreenshots().add(screenshot);
                recordScreenshotCount(description);
            }
        } catch (Exception e) {
            logger.debug("Failed to take screenshot: {}", description, e);
        }
    }

    private void captureScreenshotAtStepLevel(String stepTitle) {
        takeScreenshotAndRegister(stepTitle);
    }

    private void captureScreenshotAtScenarioLevel(String scenarioName, TestResult result) {
        if (screenshotStrategy.shouldTakeScreenshotFor(result)) {
            takeScreenshotAndRegister(scenarioName + "-scenario-complete");
        }
    }

    private void captureScreenshotOnFailure(Throwable throwable) {
        try {
            String uniqueName = String.format("%s-failure-%d",
                    FailureScreenshotHandler.sanitizeFilename(
                            TestContextHolder.get().get(ListenerGuard.CURRENT_TEST_NAME_KEY) != null
                                    ? TestContextHolder.get().get(ListenerGuard.CURRENT_TEST_NAME_KEY) : "scenario"),
                    System.currentTimeMillis());
            CapturedScreenshot screenshot = FailureScreenshotHandler.capture(uniqueName);
            if (screenshot != null) {
                currentStepScreenshots().add(screenshot);
                recordScreenshotCount(uniqueName);
            }
        } catch (Exception e) {
            logger.debug("Failed to take failure screenshot", e);
        }
    }

    private void recordScreenshotCount(String description) {
        Integer count = TestContextHolder.get().get(CURRENT_STEP_SCREENSHOTS_TAKEN_KEY);
        count = count == null ? 1 : count + 1;
        TestContextHolder.get().set(CURRENT_STEP_SCREENSHOTS_TAKEN_KEY, count);
        recordTestData("screenshotsTaken", String.valueOf(count));
    }

    // ═══════════════════════════════════════════════════════════
    // 路由状态 / 场景重置
    // ═══════════════════════════════════════════════════════════

    private void resetCurrentScenarioRouteState(String scenarioName) {
        //  ROUTE-P0-1：每个场景独立隔离路由状态，防止跨场景越权访问（关键安全修复）
        try {
            RouteLifecycle lifecycle = RouteLifecycleRegistry.get();
            if (lifecycle != null) {
                lifecycle.resetCaptureCurrent();
                lifecycle.stopCapture();
            }
        } catch (Exception e) {
            logger.debug("Failed to reset route state for scenario: {}", scenarioName, e);
        }
    }

    private void clearCurrentThreadResources(boolean contextHoldsLogin) {
        try {
            if (contextHoldsLogin) {
                //  仅做用例级清理，保留 Context+Page（避免跨场景串扰）
                PlaywrightManager.clearCurrentThreadRouteState();
            } else {
                //  彻底拆除
                PlaywrightManager.clearThreadResourcesOnCaseAbort();
            }
        } catch (Exception e) {
            logger.debug("Failed to clear thread resources", e);
        }
    }

    private void cleanupAfterAbnormalTermination(String scenarioName) {
        logger.debug("Scenario aborted abnormally - cleaning up: {}", scenarioName);
        try {
            //  步骤级失败不拆资源（2026-10-16 修正），仅 testSkipped 等异常终止路径走此清理
            SessionManager.resetCurrentSession();
            PlaywrightManager.clearCurrentThreadRouteState();
            clearCurrentThreadResources(false);
            PlaywrightManager.clearThreadResourcesOnCaseAbort();
        } catch (Exception e) {
            logger.debug("cleanupAfterAbnormalTermination encountered error", e);
        } finally {
            TestContextHolder.resetForCurrentThread();
            try {
                SessionManager.releaseSessionGate();
            } catch (Exception e) {
                logger.debug("releaseSessionGate failed", e);
            }
            try {
                ConcurrencyGate.releaseAllForCurrentThread();
            } catch (Exception e) {
                logger.debug("ConcurrencyGate release failed", e);
            }
        }
    }

    private void cleanupThreadLocals() {
        try {
            //  释放会话闸门 + 并发闸门（场景收口统一释放，防泄漏）
            SessionManager.releaseSessionGate();
            ConcurrencyGate.releaseAllForCurrentThread();
            //  重置 per-thread 截图重入标记
            FailureScreenshotHandler.clearThreadState();
        } catch (Exception e) {
            logger.debug("cleanupThreadLocals encountered error", e);
        }
    }

    private void resetCurrentScenarioState() {
        String testName = TestContextHolder.get().get(ListenerGuard.CURRENT_TEST_NAME_KEY);
        TestContextHolder.get().set(CURRENT_TEST_RESULT_KEY, TestResult.PENDING);
        TestContextHolder.get().set(CURRENT_STEP_KEY, null);
        TestContextHolder.get().set(CURRENT_TEST_START_TIME_KEY, null);
        TestContextHolder.get().set(CURRENT_STEP_START_TIME_KEY, null);
        TestContextHolder.get().set(CURRENT_STEP_SCREENSHOTS_TAKEN_KEY, 0);
        if (testName != null) {
            recordTestData("testName", testName);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 名称唯一化 / 性能统计 / 上报辅助
    // ═══════════════════════════════════════════════════════════

    private static final Map<String, AtomicInteger> SCENARIO_NAME_COUNTERS = new ConcurrentHashMap<>();

    private String generateUniqueScenarioName(String baseName) {
        if (baseName == null || baseName.trim().isEmpty()) {
            baseName = "unnamed-scenario";
        }
        AtomicInteger counter = SCENARIO_NAME_COUNTERS.computeIfAbsent(baseName, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count == 1) {
            return baseName; // 首次出现无需后缀
        }
        return baseName + "-" + count;
    }

    private void markScenarioResult(String scenarioName, TestResult result) {
        if (result == TestResult.SUCCESS) {
            ListenerPerfStats.markPassed();
        } else if (result == TestResult.FAILURE) {
            ListenerPerfStats.markFailed();
        } else if (result == TestResult.ERROR) {
            ListenerPerfStats.markFailed();
        } else if (result == TestResult.PENDING) {
            ListenerPerfStats.markSkipped();
        } else if (result == TestResult.SKIPPED) {
            ListenerPerfStats.markSkipped();
        }
    }

    /** 原生 LifecycleStatus → 框架 TestResult（用于上报端口与性能统计）。 */
    private static TestResult toFramework(LifecycleStatus status) {
        if (status == null) {
            return TestResult.PENDING;
        }
        switch (status) {
            case PASSED:
                return TestResult.SUCCESS;
            case FAILED:
                return TestResult.FAILURE;
            case SKIPPED:
            case IGNORED:
                return TestResult.SKIPPED;
            case PENDING:
                return TestResult.PENDING;
            case AMBIGUOUS:
            default:
                return TestResult.UNKNOWN;
        }
    }

    private void recordTestData(String key, String value) {
        try {
            TestContextHolder.get().set(ContextKey.of(key, String.class), value);
        } catch (Exception e) {
            logger.debug("Failed to record test data: {} = {}", key, value, e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 公开门面（性能统计，零变更）
    // ═══════════════════════════════════════════════════════════

    public static String getPerformanceStats() {
        return ListenerPerfStats.getPerformanceStats();
    }

    public static void resetStats() {
        ListenerPerfStats.resetStats();
    }
}

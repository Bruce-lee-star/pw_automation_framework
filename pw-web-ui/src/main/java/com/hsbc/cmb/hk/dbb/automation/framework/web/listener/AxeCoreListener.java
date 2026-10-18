package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility.AxeCoreScanner;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkLifecycleListener;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * Axe-core 无障碍扫描监听器（企业级 · 去 框架 专项 DEV-V5）。
 *
 * <p>原为 框架 {@code StepListener}，现改为原生 {@link FrameworkLifecycleListener}：
 * <ul>
 *   <li>{@code onTestRunStarted} 等价原 {@code testSuiteStarted} ⇒ {@link #initializeIfNeeded()}；</li>
 *   <li>{@code onTestRunFinished} 等价原 {@code testSuiteFinished}/{@code testRunFinished} 兜底 ⇒
 *       {@link #generateFinalReport()} + {@link #cleanupResources()}。</li>
 * </ul>
 * 扫描 API（{@link #scanCurrentPage}/{@link #scanElement}）保持静态、供业务 glue 直接调用，签名零变更。
 */
public final class AxeCoreListener implements FrameworkLifecycleListener {

    /** 单例（与原 {@code FrameworkLifecycleBus} 注册语义一致）。 */
    public static final AxeCoreListener INSTANCE = new AxeCoreListener();

    private static final Logger logger = LoggerFactory.getLogger(AxeCoreListener.class);

    //  T3-1 收拢：由 static ThreadLocal 迁入 TestContext；原 withInitial(() -> false) 的默认 false
    // 语义由布尔 helper（Boolean.TRUE.equals）等价保证，避免未设值时 null 自动拆箱 NPE。
    //  A-08（doc 22）：AXE_ENABLED 是【进程级】标志（全局配置 WebFrameworkConfig.AXE_SCAN_ENABLED +
    //    AxeCoreScanner 的静态 isInitialized 共同决定），【不应】落在 TestContext 的场景上下文中：
    //    initializeIfNeeded 仅套件级（!isInitialized 时才运行）写入，而场景级 scanCurrentPage 走同
    //    一调用时 isInitialized 已为真、不再重设 → 套件级写线程上下文、场景级读场景上下文 → 标志不可见、
    //    每页扫描被静默跳过。故改为进程级 static volatile 字段，与 AxeCoreScanner 的进程级生命周期对齐。
    //    REPORT_GENERATED_KEY 保留 TestContext：它在 generateFinalReport 同一次调用的同上下文内读+写，无跨级问题。
    private static volatile Boolean axeEnabled = null;
    private static final ContextKey<Boolean> REPORT_GENERATED_KEY = ContextKey.of("axeCoreListener.reportGenerated", Boolean.class);

    /** 等价原 axeEnabled.get()（默认 false）。进程级。 */
    private static boolean isAxeEnabled() {
        return Boolean.TRUE.equals(axeEnabled);
    }

    /** 等价原 reportGenerated.get()（默认 false）。 */
    private static boolean isReportGenerated() {
        return Boolean.TRUE.equals(TestContextHolder.get().get(REPORT_GENERATED_KEY));
    }

    /**
     * Initialize before test suite / run
     */
    public static void initializeIfNeeded() {
        if (!AxeCoreScanner.isInitialized()) {
            try {
                AxeCoreScanner.AxeScanConfig config = new AxeCoreScanner.AxeScanConfig();

                // Read configuration using FrameworkConfigManager
                boolean enabled = FrameworkConfigManager.getBoolean(WebFrameworkConfig.AXE_SCAN_ENABLED);
                String projectName = PlaywrightManager.config().getProjectName();
                String tags = FrameworkConfigManager.getString(WebFrameworkConfig.AXE_SCAN_TAGS);
                String outputDir = FrameworkConfigManager.getString(WebFrameworkConfig.AXE_SCAN_OUTPUT_DIR);

                axeEnabled = enabled;  // A-08：进程级，跨场景可见
                TestContextHolder.get().set(REPORT_GENERATED_KEY,false);  // Reset report flag

                if (isAxeEnabled()) {
                    config.setProjectName(projectName);
                    config.setReportOutputDir(outputDir);

                    // Parse tags
                    if (tags != null && !tags.isEmpty()) {
                        for (String tag : tags.split(",")) {
                            config.getTags().add(tag.trim());
                        }
                    }

                    AxeCoreScanner.initialize(config);
                    logger.info("AxeCoreListener initialized with project: {}, tags: {}", projectName, tags);
                }
            } catch (Exception e) {
                logger.error("Failed to initialize AxeCoreListener: {}", e.getMessage(), e);
                axeEnabled = false;
            }
        }
    }

    /**
     * Scan the current page
     */
    public static AxeCoreScanner.AxeScanResult scanCurrentPage(String pageName) {
        initializeIfNeeded();

        if (!isAxeEnabled()) {
            logger.debug("Axe-core scanning is disabled");
            return null;
        }

        try {
            return AxeCoreScanner.scanPage(pageName);
        } catch (Exception e) {
            logger.error("Error during axe-core scan: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Scan a specific element on the current page
     */
    public static AxeCoreScanner.AxeScanResult scanElement(String pageName, String contextSelector) {
        initializeIfNeeded();

        if (!isAxeEnabled()) {
            logger.debug("Axe-core scanning is disabled");
            return null;
        }

        try {
            return AxeCoreScanner.scanPage(pageName, contextSelector);
        } catch (Exception e) {
            logger.error("Error during axe-core scan: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Generate final report (ensure only called once)
     */
    public static void generateFinalReport() {
        // Prevent duplicate report generation
        if (isReportGenerated()) {
            logger.debug("Report already generated, skipping");
            return;
        }

        if (AxeCoreScanner.isInitialized()) {
            try {
                AxeCoreScanner.generateReport();
                TestContextHolder.get().set(REPORT_GENERATED_KEY,true);
            } catch (Exception e) {
                logger.error("Failed to generate axe-core report: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Cleanup resources (AxeCoreScanner)
     */
    private static void cleanupResources() {
        try {
            if (AxeCoreScanner.isInitialized()) {
                AxeCoreScanner.cleanup();
            }
        } catch (Exception e) {
            logger.error("Error during AxeCoreScanner cleanup: {}", e.getMessage(), e);
        } finally {
            // Always clean up ThreadLocals to prevent memory leaks
            axeEnabled = null;  // A-08：进程级复位
            TestContextHolder.get().remove(REPORT_GENERATED_KEY);
        }
    }

    /**
     * Check if axe scanning is enabled
     */
    public static boolean isEnabled() {
        return isAxeEnabled();
    }

    // ==================== FrameworkLifecycleListener Implementation ====================
    // Axe-core 仅在测试运行级别工作（初始化/报告生成），不需要逐步骤追踪。

    @Override
    public void onTestRunStarted() {
        initializeIfNeeded();
    }

    @Override
    public void onTestRunFinished() {
        // 等价原 testSuiteFinished + testRunFinished 兜底：确保资源清理与报告生成
        if (isAxeEnabled()) {
            generateFinalReport();
            cleanupResources();
        }
    }

    @Override
    public void onScenarioStarted(LifecycleScenarioInfo info) {
        // axe-core 不需要逐场景跟踪
    }

    @Override
    public void onScenarioFinished(LifecycleScenarioResult result) {
        // 报告在 onTestRunFinished 统一生成
    }

    @Override
    public void onStepStarted(LifecycleStepInfo step) {
        // no-op
    }

    @Override
    public void onStepFinished(LifecycleStepResult stepResult) {
        // no-op
    }
}

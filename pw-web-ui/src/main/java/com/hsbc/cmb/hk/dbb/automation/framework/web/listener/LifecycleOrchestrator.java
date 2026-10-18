package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkLifecycleListener;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生生命周期编排器（替代 框架 适配器中"与 story/suite 事件强耦合"的编排逻辑）。
 *
 * <p>企业级 · 去 框架 专项（DEV-V5）：原先由 {@code ThucydidesStepsListenerAdapter} 依托 框架 的
 * {@code testSuiteStarted(Story)} / {@code testRunFinished} 驱动的编排，现改由本原生监听器承接，
 * 等价覆盖以下能力：
 * <ul>
 *   <li><b>跨 Feature Context 清理</b>：Cucumber 无 框架 的 "Story" 概念，以 feature 文件 uri 作为等价边界；
 *       同一线程上 uri 变化即视为"进入新 feature"，关闭上一 feature 的 Context/Page（{@link PlaywrightManager#cleanupForFeature()}）。</li>
 *   <li><b>轮次横幅</b>：区分"新套件开始"与"同名套件再现（可能是 failsafe 重跑）"，仅陈述可观测事实，不误报重跑。</li>
 *   <li><b>run 计数</b>：{@code onTestRunFinished} 推进轮次并重置"本轮已见套件"集合。</li>
 * </ul>
 *
 * <p>派发隔离由 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkLifecycleBus}
 * 保证（单监听器异常不拖垮其它监听器）；本类自身状态均为进程级静态 + 每线程"上一 feature uri"，线程安全。</p>
 *
 * <p>与业务无关：本类不依赖任何 框架 类型，是纯框架原生实现。</p>
 */
public final class LifecycleOrchestrator implements FrameworkLifecycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleOrchestrator.class);

    /** 跨所有 run 见过的 feature 标识（uri/name），用于"同名套件再现"判定。 */
    private static final Set<String> EVER_SEEN_FEATURES = ConcurrentHashMap.newKeySet();

    /** 本轮（当前 run）已见过的 feature 标识，用于"每轮只报一次横幅"。run 结束时清空。 */
    private static final Set<String> SEEN_IN_CURRENT_RUN = ConcurrentHashMap.newKeySet();

    /** 当前 run 序号（从 0 开始）。 */
    private static final AtomicInteger CURRENT_RUN_NUMBER = new AtomicInteger(0);

    /** 每线程"上一 feature uri"，用于检测 feature 边界触发跨 feature 清理。 */
    private static final ThreadLocal<String> LAST_FEATURE_URI = ThreadLocal.withInitial(() -> null);

    private LifecycleOrchestrator() {
    }

    /** 单例（与 {@code FrameworkLifecycleBus} 注册语义一致）。 */
    public static final LifecycleOrchestrator INSTANCE = new LifecycleOrchestrator();

    /** 轮次横幅级别（逐字对齐原 {@code ThucydidesStepsListenerAdapter.RoundBanner}）。 */
    public enum RoundBanner {
        /** 无需报横幅。 */
        NONE,
        /** 新套件开始（不同 suite；不是重跑）。 */
        NEW_SUITE,
        /** 同名套件再现（同一套件被再次执行，可能是 failsafe 重跑）。 */
        SAME_SUITE_RE_EXECUTED
    }

    @Override
    public void onScenarioStarted(LifecycleScenarioInfo info) {
        if (info == null) {
            return;
        }
        String featureKey = info.uri() != null && !info.uri().isEmpty() ? info.uri() : info.featureName();

        // 跨 Feature Context 清理：同一线程上 feature 边界（uri 变化）即关闭上一 feature 的 Context。
        String last = LAST_FEATURE_URI.get();
        if (last != null && !last.equals(featureKey)) {
            try {
                LOGGER.info("[Lifecycle] New feature starting: {} - closing previous feature's Context "
                        + "(cross-feature cleanup)", featureKey);
                PlaywrightManager.cleanupForFeature();
            } catch (Exception e) {
                LOGGER.warn("[Lifecycle] Failed to cleanup context at feature boundary for '{}': {}",
                        featureKey, e.getMessage());
            }
        }
        LAST_FEATURE_URI.set(featureKey);

        // 轮次横幅（只陈述可观测事实）。
        RoundBanner banner = classifyRoundStart(featureKey);
        if (banner != RoundBanner.NONE) {
            logRoundBanner(banner, featureKey);
        }
    }

    @Override
    public void onTestRunFinished() {
        advanceRun();
    }

    /**
     * 推进一轮（测试与生产共用）：run 序号 +1，并清空"本轮已见套件"集合。
     */
    public static void advanceRun() {
        CURRENT_RUN_NUMBER.incrementAndGet();
        SEEN_IN_CURRENT_RUN.clear();
    }

    /**
     * 轮次横幅判据（逐字对齐原 {@code ThucydidesStepsListenerAdapter.classifyRoundStart}）。
     *
     * <p>判据：<b>同名套件再现</b> ⇒ 同一套件被再次执行（才可能是 failsafe 重跑）；
     * <b>新套件</b> ⇒ 只是另一个套件开始，不是重跑。首轮（{@code currentRunNumber==0}）不报任何横幅。</p>
     *
     * @param featureKey feature 标识（uri/name）；null 安全
     * @return 横幅级别（{@link RoundBanner#NONE} 表示不报）
     */
    public static RoundBanner classifyRoundStart(String featureKey) {
        if (featureKey == null) {
            return RoundBanner.NONE;
        }
        boolean everSeen = EVER_SEEN_FEATURES.contains(featureKey);
        if (!everSeen) {
            EVER_SEEN_FEATURES.add(featureKey);
            SEEN_IN_CURRENT_RUN.add(featureKey);
            return CURRENT_RUN_NUMBER.get() == 0 ? RoundBanner.NONE : RoundBanner.NEW_SUITE;
        }
        if (SEEN_IN_CURRENT_RUN.contains(featureKey)) {
            return RoundBanner.NONE; // 本轮已报过，不再重复
        }
        SEEN_IN_CURRENT_RUN.add(featureKey);
        return RoundBanner.SAME_SUITE_RE_EXECUTED; // 前一轮见过、本轮首次出现 ⇒ 同名再现（可能重跑）
    }

    private static void logRoundBanner(RoundBanner banner, String featureKey) {
        LOGGER.info("==========================================================================");
        if (banner == RoundBanner.NEW_SUITE) {
            LOGGER.info("  NEW SUITE STARTING - Round {} for '{}' (different suite; this is NOT a rerun)",
                    CURRENT_RUN_NUMBER.get(), featureKey);
        } else if (banner == RoundBanner.SAME_SUITE_RE_EXECUTED) {
            LOGGER.info("  RERUN STARTING - Round {} for '{}' (same suite re-executed; consistent with a "
                    + "failsafe rerun, confirm from the build output)", CURRENT_RUN_NUMBER.get(), featureKey);
        }
        LOGGER.info("==========================================================================");
    }

    /** 测试夹具：归零 run 计数并清空"已见套件"集合（逐字对齐原 {@code resetRoundStateForTest}）。 */
    public static void resetRoundStateForTest() {
        CURRENT_RUN_NUMBER.set(0);
        EVER_SEEN_FEATURES.clear();
        SEEN_IN_CURRENT_RUN.clear();
        LAST_FEATURE_URI.remove();
    }

    // ── 其余生命周期回调：编排器只负责场景开始（清理/横幅）与 run 结束（计数），其余 no-op ──

    @Override
    public void onTestRunStarted() {
        // no-op：原生无"套件级"初始化需求（AxeCoreListener 等各自负责）
    }

    @Override
    public void onStepStarted(LifecycleStepInfo step) {
        // no-op
    }

    @Override
    public void onStepFinished(LifecycleStepResult stepResult) {
        // no-op
    }

    @Override
    public void onScenarioFinished(LifecycleScenarioResult result) {
        // no-op
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy;

import com.hsbc.cmb.hk.dbb.automation.framework.common.result.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleScenarioResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStepInfo;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.LifecycleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

public enum ScreenshotStrategy {
    FOR_FAILURES {
        public boolean shouldTakeScreenshotFor(LifecycleScenarioResult result) {
            return result != null && result.status() == LifecycleStatus.FAILED;
        }

        public boolean shouldTakeScreenshotFor(LifecycleStepInfo step) {
            return false; // 仅在失败时截图，不考虑步骤
        }

        public boolean shouldTakeScreenshotFor(TestResult result) {
            return result == TestResult.FAILURE || result == TestResult.ERROR;
        }
    },

    DISABLED {
        public boolean shouldTakeScreenshotFor(LifecycleScenarioResult result) {
            return false; // 禁用截图
        }

        public boolean shouldTakeScreenshotFor(LifecycleStepInfo step) {
            return false; // 禁用截图
        }

        public boolean shouldTakeScreenshotFor(TestResult result) {
            return false; // 禁用截图
        }
    },

    AFTER_EACH_STEP {
        public boolean shouldTakeScreenshotFor(LifecycleScenarioResult result) {
            return true; // 对所有测试结果都截图
        }

        public boolean shouldTakeScreenshotFor(LifecycleStepInfo step) {
            return step != null; // 对每个步骤都截图
        }

        public boolean shouldTakeScreenshotFor(TestResult result) {
            return true; // 对所有结果都截图
        }
    },

    BEFORE_AND_AFTER_EACH_STEP {
        public boolean shouldTakeScreenshotFor(LifecycleScenarioResult result) {
            return true; // 对所有测试结果都截图
        }

        public boolean shouldTakeScreenshotFor(LifecycleStepInfo step) {
            return step != null; // 对每个步骤都截图
        }

        public boolean shouldTakeScreenshotFor(TestResult result) {
            return true; // 对所有结果都截图
        }

        @Override
        public boolean shouldTakeScreenshotBeforeEachStep() {
            return true; // 步骤开始也截图（BEFORE 半边）
        }
    },

    MANUAL {
        public boolean shouldTakeScreenshotFor(LifecycleScenarioResult result) {
            return false; // 仅手动截图
        }

        public boolean shouldTakeScreenshotFor(LifecycleStepInfo step) {
            return false; // 仅手动截图
        }

        public boolean shouldTakeScreenshotFor(TestResult result) {
            return false; // 仅手动截图
        }
    };

    // 抽象方法定义（原生事件模型，不再依赖 框架）
    public abstract boolean shouldTakeScreenshotFor(LifecycleScenarioResult result);
    public abstract boolean shouldTakeScreenshotFor(LifecycleStepInfo step);
    public abstract boolean shouldTakeScreenshotFor(TestResult result);

    /**
     * 步骤【开始】时是否截图（对应 框架 {@code BEFORE_AND_AFTER_EACH_STEP} 的 BEFORE 半边）。
     *
     * <p>默认 {@code false}：{@code AFTER_EACH_STEP} / {@code FOR_FAILURES} 等只在<b>步骤结束</b>截图，
     * 不在开始截。仅 {@code BEFORE_AND_AFTER_EACH_STEP} 覆写为 {@code true}。</p>
     *
     * <p><b>为何需要独立判定</b>：{@link #shouldTakeScreenshotFor(LifecycleStepInfo)} 对
     * {@code AFTER_EACH_STEP} 与 {@code BEFORE_AND_AFTER_EACH_STEP} 都返回 {@code step != null}
     * （即"任意步骤都截"），无法区分 before/after。若 {@code onStepStarted} 直接调用后者，默认策略
     * 下每个 Cucumber 步骤的<b>开始</b>也会截一张图，与步骤结束截图叠加成"截图风暴"
     * （见 {@code AFTER_EACH_STEP} 注释中的踩坑史）。故步骤开始截图必须单独门控。</p>
     */
    public boolean shouldTakeScreenshotBeforeEachStep() {
        return false;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenshotStrategy.class);

    /**
     * 配置名 → 策略（大小写不敏感）。
     *
     * <p><b>为什么要别名表而不是 {@code valueOf}</b>：官方策略名（{@code AFTER_FAILING_STEP} 等）
     * 与框架枚举名并不一致。原实现直接 {@code valueOf} 并在 {@link IllegalArgumentException}
     * 时静默退回 {@code AFTER_EACH_STEP}，后果是「配置写 {@code AFTER_FAILING_STEP}（只失败时截图）
     * → 实际每步都截图」——实测一轮 E2E 因此产出 85 张截图，且因为兜底值与默认值恰好相同而无人察觉。</p>
     */
    private static final Map<String, ScreenshotStrategy> NAME_ALIASES = Map.ofEntries(
            Map.entry("FOR_FAILURES", FOR_FAILURES),
            Map.entry("AFTER_FAILING_STEP", FOR_FAILURES),
            Map.entry("AFTER_EACH_STEP", AFTER_EACH_STEP),
            Map.entry("FOR_EACH_STEP", AFTER_EACH_STEP),
            Map.entry("BEFORE_AND_AFTER_EACH_STEP", BEFORE_AND_AFTER_EACH_STEP),
            Map.entry("DISABLED", DISABLED),
            Map.entry("NONE", DISABLED),
            Map.entry("MANUAL", MANUAL));

    // 从配置中获取策略（不再需要 框架 EnvironmentVariables）
    public static ScreenshotStrategy from() {
        return resolve(FrameworkConfigManager.getString(WebFrameworkConfig.FRAMEWORK_SCREENSHOT_STRATEGY));
    }

    /**
     * 解析配置值（可单测的纯函数）。
     *
     * <p>未配置 → 维持既有默认 {@link #AFTER_EACH_STEP}；<b>配置了但无法识别 → 退回
     * {@link #FOR_FAILURES} 并告警</b>（而不是退回"每步都截"）：无法识别时应向"少截"这一侧失败，
     * 且必须留痕，否则拼错的策略名会变成查不出来的截图风暴。</p>
     */
    static ScreenshotStrategy resolve(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return AFTER_EACH_STEP; // 未配置：保持既有默认（见 ConfigKeys 的默认值）
        }
        ScreenshotStrategy strategy = NAME_ALIASES.get(configured.trim().toUpperCase(Locale.ROOT));
        if (strategy != null) {
            return strategy;
        }
        LOGGER.warn("[Screenshot] unknown strategy '{}' -- falling back to {} (supported values: {})",
                configured, FOR_FAILURES, NAME_ALIASES.keySet());
        return FOR_FAILURES;
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.config;

/**
 * 截图模式——与框架 {@code ScreenshotStrategy}（web 侧 {@code framework.screenshot.strategy}）
 * <b>同一套值域</b>，避免两侧词汇漂移导致「配了却不对应」。
 *
 * <p>规范四值（与框架逐字一致）：</p>
 * <ul>
 *   <li>{@link #BEFORE_AND_AFTER_EACH_STEP} —— 每步开始与结束都截图；</li>
 *   <li>{@link #AFTER_EACH_STEP} —— 每步结束截图；</li>
 *   <li>{@link #FOR_FAILURES} —— 仅失败时截图（默认，对齐 Serenity）；</li>
 *   <li>{@link #DISABLED} —— 关闭自动截图。</li>
 * </ul>
 *
 * <p>别名兼容（历史值/Serenity 拼法）：{@code ON_FAILURE} / {@code AFTER_FAILING_STEP} /
 * {@code FOR_FAILURES} → {@link #FOR_FAILURES}；{@code FOR_EACH_STEP} → {@link #AFTER_EACH_STEP}；
 * {@code BEFORE_AND_AFTER_EACH_STEP} → {@link #BEFORE_AND_AFTER_EACH_STEP}；
 * {@code OFF} / {@code NONE} → {@link #DISABLED}。
 * 无法识别的值 → {@link #DISABLED}（向少截一侧失败）+ 告警，绝不静默按默认值放行。</p>
 *
 * <p><b>为何不再有 MANUAL</b>：报告侧只有「自动采集」与「不采集」两种决策，业务显式截图走
 * {@code BasePage.takeScreenshot} 直接落盘，无需第三种半自动态（曾因语义模糊被用户要求下线）。</p>
 */
public enum ScreenshotMode {

    /** 失败即拍（默认，对齐 Serenity）。 */
    FOR_FAILURES,

    /** 每步结束都拍。 */
    AFTER_EACH_STEP,

    /** 每步开始与结束都拍。 */
    BEFORE_AND_AFTER_EACH_STEP,

    /** 禁用自动截图。 */
    DISABLED;

    /**
     * 解析配置值（大小写不敏感，含别名表）。
     *
     * @param raw   原始配置值，null/空 → 默认 {@link #FOR_FAILURES}
     * @param onUnknown 无法识别时的告警回调（可为 null）
     */
    public static ScreenshotMode parse(String raw, java.util.function.Consumer<String> onUnknown) {
        if (raw == null || raw.isBlank()) {
            return FOR_FAILURES;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        switch (v) {
            case "FOR_FAILURES":
            case "ON_FAILURE":        // 历史值
            case "AFTER_FAILING_STEP": // Serenity 别名
                return FOR_FAILURES;
            case "AFTER_EACH_STEP":
            case "FOR_EACH_STEP":      // 历史值
                return AFTER_EACH_STEP;
            case "BEFORE_AND_AFTER_EACH_STEP":
                return BEFORE_AND_AFTER_EACH_STEP;
            case "DISABLED":
            case "OFF":
            case "NONE":
                return DISABLED;
            default:
                if (onUnknown != null) {
                    onUnknown.accept("Unrecognized screenshot mode '" + raw + "', falling back to DISABLED");
                }
                return DISABLED;
        }
    }
}

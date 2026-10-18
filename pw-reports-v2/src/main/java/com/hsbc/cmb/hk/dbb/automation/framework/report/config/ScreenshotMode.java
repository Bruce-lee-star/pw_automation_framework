package com.hsbc.cmb.hk.dbb.automation.framework.report.config;

/**
 * 截图模式——语义对齐 {@code serenity.take.screenshots} 值域（DR-2 机械层）。
 *
 * <p>别名兼容（对齐框架既有 {@code ScreenshotStrategy} 别名表）：
 * {@code AFTER_FAILING_STEP} → {@link #ON_FAILURE}；
 * {@code BEFORE_AND_AFTER_EACH_STEP} → {@link #AFTER_EACH_STEP}。
 * 无法识别的值 → {@link #DISABLED}（向少截一侧失败）+ 告警，绝不静默按默认值放行。
 */
public enum ScreenshotMode {

    /** 失败即拍（默认，对齐 Serenity）。 */
    ON_FAILURE,

    /** 每步都拍。 */
    AFTER_EACH_STEP,

    /** 禁用。 */
    DISABLED,

    /** 仅由调用方显式采集时拍。 */
    MANUAL;

    /**
     * 解析配置值（大小写不敏感，含别名表）。
     *
     * @param raw   原始配置值，null/空 → 默认 ON_FAILURE
     * @param onUnknown 无法识别时的告警回调（可为 null）
     */
    public static ScreenshotMode parse(String raw, java.util.function.Consumer<String> onUnknown) {
        if (raw == null || raw.isBlank()) {
            return ON_FAILURE;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        switch (v) {
            case "ON_FAILURE":
            case "AFTER_FAILING_STEP": // Serenity 别名
            case "FOR_FAILURES":       // Serenity 旧值
                return ON_FAILURE;
            case "AFTER_EACH_STEP":
            case "BEFORE_AND_AFTER_EACH_STEP":
                return AFTER_EACH_STEP;
            case "DISABLED":
            case "OFF":
                return DISABLED;
            case "MANUAL":
                return MANUAL;
            default:
                if (onUnknown != null) {
                    onUnknown.accept("Unrecognized screenshot mode '" + raw + "', falling back to DISABLED");
                }
                return DISABLED;
        }
    }
}

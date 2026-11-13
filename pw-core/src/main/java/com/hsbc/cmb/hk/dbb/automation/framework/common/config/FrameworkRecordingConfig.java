package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

/**
 * 报告动作录制开关：唯一事实来源为 {@code framework.recording.enabled}。
 *
 * <p>与日志配置完全解耦：日志阈值由 {@code logging.root.level} 控制，
 * 录制不受任何日志相关配置影响。</p>
 */
public final class FrameworkRecordingConfig {

    private static final ConfigRegistry KEY = ConfigRegistry.FRAMEWORK_RECORDING_ENABLED;

    private FrameworkRecordingConfig() {
    }

    public static boolean isEnabled() {
        return parseBoolean(ConfigReader.resolve(KEY.key(), KEY.defaultValue()), KEY.key());
    }

    private static boolean parseBoolean(String value, String key) {
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean value for " + key + ": " + value);
    }
}

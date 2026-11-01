package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

/** Configuration for adding browser actions to the test report, independent of application logs. */
public final class FrameworkRecordingConfig {

    private FrameworkRecordingConfig() {
    }

    public static boolean isEnabled() {
        ConfigRegistry recordingKey = ConfigRegistry.FRAMEWORK_RECORDING_ENABLED;
        ConfigReader.Resolved recording = ConfigReader.resolveWithLayer(
                recordingKey.key(), recordingKey.defaultValue());
        if (recording.layer() != ConfigReader.Layer.DEFAULT) {
            return parseBoolean(recording.value(), recordingKey.key());
        }

        ConfigRegistry legacyKey = ConfigRegistry.WEB_FRAMEWORK_LOGGING;
        String legacyValue = ConfigReader.resolve(legacyKey.key(), legacyKey.defaultValue());
        return "VERBOSE".equalsIgnoreCase(legacyValue) || "TRACE".equalsIgnoreCase(legacyValue);
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

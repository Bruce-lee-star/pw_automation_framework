package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrameworkRecordingConfigTest {

    private static final String RECORDING_KEY = ConfigRegistry.FRAMEWORK_RECORDING_ENABLED.key();
    /** 已下线的历史键名：保留同名系统属性回归护栏，防止兼容回退被重新引入。 */
    private static final String LEGACY_KEY = "framework.logging";
    private final String previousRecording = System.getProperty(RECORDING_KEY);
    private final String previousLegacy = System.getProperty(LEGACY_KEY);

    @After
    public void restoreProperties() {
        restore(RECORDING_KEY, previousRecording);
        restore(LEGACY_KEY, previousLegacy);
    }

    @Test
    public void usesDedicatedRecordingSettingWhenConfigured() {
        System.setProperty(RECORDING_KEY, "false");
        assertFalse(FrameworkRecordingConfig.isEnabled());

        System.setProperty(RECORDING_KEY, "true");
        assertTrue(FrameworkRecordingConfig.isEnabled());
    }

    @Test
    public void defaultsToEnabledWhenRecordingSettingIsAbsent() {
        System.clearProperty(RECORDING_KEY);
        assertTrue(FrameworkRecordingConfig.isEnabled());
    }

    @Test
    public void legacyLoggingKeyNeverDisablesRecording() {
        System.clearProperty(RECORDING_KEY);
        System.setProperty(LEGACY_KEY, "QUIET");
        assertTrue(FrameworkRecordingConfig.isEnabled());

        System.setProperty(RECORDING_KEY, "true");
        System.setProperty(LEGACY_KEY, "OFF");
        assertTrue(FrameworkRecordingConfig.isEnabled());
    }

    @Test
    public void legacyLoggingKeyNeverEnablesRecording() {
        System.setProperty(RECORDING_KEY, "false");
        System.setProperty(LEGACY_KEY, "VERBOSE");
        assertFalse(FrameworkRecordingConfig.isEnabled());

        System.setProperty(LEGACY_KEY, "TRACE");
        assertFalse(FrameworkRecordingConfig.isEnabled());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidRecordingSetting() {
        System.setProperty(RECORDING_KEY, "sometimes");
        FrameworkRecordingConfig.isEnabled();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}

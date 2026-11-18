package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrameworkRecordingConfigTest {

    private static final String RECORDING_KEY = ConfigRegistry.FRAMEWORK_RECORDING_ENABLED.key();
    private final String previousRecording = System.getProperty(RECORDING_KEY);

    @After
    public void restoreProperties() {
        restore(RECORDING_KEY, previousRecording);
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

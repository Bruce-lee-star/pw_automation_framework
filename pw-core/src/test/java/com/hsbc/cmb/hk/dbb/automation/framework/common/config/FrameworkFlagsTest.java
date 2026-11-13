package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link FrameworkFlags} 的确定性解析契约：只认系统属性 → 环境变量 → 默认值。
 *
 * <p>回归 2026-11-12 {@code StackOverflowError}：本类曾被实现为
 * {@code ConfigReader.getProperty(key)}，把日志格式化链路接到了会打日志的文件配置层。
 */
public class FrameworkFlagsTest {

    private static final String KEY = "framework.unit.test.flag";

    @After
    public void clearProbe() {
        System.clearProperty(KEY);
    }

    @Test
    public void systemPropertyWinsAndBooleanIsParsed() {
        System.setProperty(KEY, "true");
        assertEquals("true", FrameworkFlags.resolve(KEY, "fallback"));
        assertTrue(FrameworkFlags.isEnabled(KEY, false));

        System.setProperty(KEY, "false");
        assertFalse(FrameworkFlags.isEnabled(KEY, true));
    }

    @Test
    public void envKeyMappingIsDeterministic() {
        assertEquals("FRAMEWORK_LOG_SANITIZE_ENABLED",
                FrameworkFlags.toEnvKey("framework.log.sanitize.enabled"));
        assertEquals("A_B_C", FrameworkFlags.toEnvKey("a-b.c"));
    }

    @Test
    public void blankSystemPropertyFallsThroughToDefault() {
        System.setProperty(KEY, "   ");
        assertEquals("fallback", FrameworkFlags.resolve(KEY, "fallback"));
        assertTrue(FrameworkFlags.isEnabled(KEY, true));
        assertFalse(FrameworkFlags.isEnabled(KEY, false));
    }

    @Test
    public void missingValueUsesDefault() {
        assertNull(FrameworkFlags.resolve(KEY, null));
        assertEquals("fallback", FrameworkFlags.resolve(KEY, "fallback"));
        assertTrue(FrameworkFlags.isEnabled(KEY, true));
    }

    @Test
    public void nullOrBlankKeyReturnsDefaultWithoutThrowing() {
        assertEquals("d", FrameworkFlags.resolve(null, "d"));
        assertEquals("d", FrameworkFlags.resolve("  ", "d"));
    }
}

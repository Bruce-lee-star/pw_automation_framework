package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 两个脱敏 Converter 的递归防御契约。
 *
 * <p>复刻 2026-11-12 线上失败路径：配置加载器在格式化期间打日志，日志又回到 Converter。
 * 关键不变量：<b>重入时不再读任何配置开关</b>，且走「继续脱敏」的安全侧。
 * 用「显式把运维开关设为 false」来构造可判定的证据——若重入时仍读开关，
 * 就会返回未脱敏的原文，断言必然失败。
 */
public class SanitizingConverterRecursionTest {

    private static final String KEY = SanitizingMessageConverter.ENABLED_KEY;
    private static final String SECRET = "password=hunter2";

    @After
    public void restore() {
        System.clearProperty(KEY);
        SanitizerSwitch.exit();
    }

    private static LoggingEvent event(String message) {
        LoggingEvent e = new LoggingEvent();
        e.setLoggerName("unit.test");
        e.setLevel(Level.INFO);
        e.setMessage(message);
        return e;
    }

    @Test
    public void defaultEnabled_sanitizesMessage() {
        System.clearProperty(KEY);

        String out = new SanitizingMessageConverter().convert(event(SECRET));

        assertFalse("明文不得出域", out.contains("hunter2"));
        assertTrue(out.contains(SensitiveDataSanitizer.maskToken()));
    }

    @Test
    public void explicitlyDisabled_returnsOriginal() {
        System.setProperty(KEY, "false");

        assertEquals("运维显式关闭属既有契约，保持不变",
                SECRET, new SanitizingMessageConverter().convert(event(SECRET)));
    }

    /** 核心回归：重入时即便开关为 false 也必须继续脱敏（证明没读配置）。 */
    @Test
    public void reentrantFormatting_ignoresDisabledSwitch_andStillSanitizes() {
        System.setProperty(KEY, "false");
        assertTrue(SanitizerSwitch.enter());       // 外层正在格式化
        try {
            String out = new SanitizingMessageConverter().convert(event(SECRET));

            assertFalse("重入读到了配置开关 ⇒ 递归防御失效", out.contains("hunter2"));
            assertTrue(out.contains(SensitiveDataSanitizer.maskToken()));
        } finally {
            SanitizerSwitch.exit();
        }
    }

    /** 内层 Converter 结束不得清掉外层标记（否则环重新打开）。 */
    @Test
    public void reentrantFormatting_doesNotClearOuterGuard() {
        assertTrue(SanitizerSwitch.enter());
        try {
            new SanitizingMessageConverter().convert(event("plain message"));
            assertFalse("外层标记必须仍在", SanitizerSwitch.enter());
        } finally {
            SanitizerSwitch.exit();
        }
    }

    /** %ex 与 %msg 共享同一守卫，异常栈出口同样不得在重入时读配置。 */
    @Test
    public void throwableConverter_sharesGuardAndStillSanitizes() {
        System.setProperty(KEY, "false");
        LoggingEvent e = event("boom");
        e.setThrowableProxy(new ThrowableProxy(new IllegalStateException(SECRET)));
        assertTrue(SanitizerSwitch.enter());
        try {
            String out = new SanitizingThrowableConverter().convert(e);

            assertFalse("异常栈明文不得出域", out.contains("hunter2"));
            assertTrue(out.contains(SensitiveDataSanitizer.maskToken()));
        } finally {
            SanitizerSwitch.exit();
        }
    }

    /** CORE-C2：脱敏失败仍必须抑制原文，守卫不得改变该契约。 */
    @Test
    public void sanitizerFailure_isSuppressed() {
        System.clearProperty(KEY);
        SanitizingMessageConverter converter = new SanitizingMessageConverter() {
            @Override
            protected String sanitize(String message) {
                throw new IllegalStateException("sanitizer broken");
            }
        };

        assertEquals(SanitizingThrowableConverter.suppressedMarker(),
                converter.convert(event(SECRET)));
    }
}

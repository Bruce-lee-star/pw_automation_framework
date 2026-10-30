package com.hsbc.cmb.hk.dbb.automation.framework.common.context;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link LanguageState#setLanguage(String)} 幂等守卫契约：同语言重复设置是幂等无操作，<b>不刷日志</b>
 * （拾取引擎 / 生成线程每任务都会调用，否则 80 秒内 30+ 条 [NLS] 刷屏淹没诊断信息）；只有实际语言
 * 变化（或清除）才记录。
 */
public class LanguageStateTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger langLogger;

    @Before
    public void setUp() {
        langLogger = (Logger) LoggerFactory.getLogger(LanguageState.class);
        appender = new ListAppender<>();
        appender.start();
        langLogger.addAppender(appender);
        LanguageState.reset();
    }

    @After
    public void tearDown() {
        langLogger.detachAppender(appender);
        LanguageState.reset();
    }

    private long switchedCount() {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("[NLS] language switched to:"))
                .count();
    }

    @Test
    public void sameLanguageTwiceLogsOnlyOnce() {
        LanguageState.setLanguage("en-US");
        LanguageState.setLanguage("en-US");   // 幂等：不应再打
        assertEquals("en-US", LanguageState.getLanguage());
        assertEquals(1, switchedCount());
    }

    @Test
    public void languageChangeLogsOnce() {
        LanguageState.setLanguage("en-US");
        LanguageState.setLanguage("zh-HK");    // 实际变化：应记录
        assertEquals("zh-HK", LanguageState.getLanguage());
        assertEquals(2, switchedCount());
    }

    @Test
    public void nullClearsOnlyWhenPreviouslySet() {
        // 未设置时清除：不记录
        LanguageState.setLanguage(null);
        assertEquals(0, switchedCount());

        // 设置后再清除：记录一次 cleared
        LanguageState.setLanguage("en-US");
        LanguageState.setLanguage(null);
        assertNull(LanguageState.getLanguage());
        assertTrue(appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("[NLS] language cleared")));
    }
}

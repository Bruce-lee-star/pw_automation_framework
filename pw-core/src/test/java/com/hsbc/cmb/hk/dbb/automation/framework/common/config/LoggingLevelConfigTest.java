package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoggingLevelConfigTest {

    private static final String LEVEL_KEY = ConfigRegistry.API_LOGGING_ROOT_LEVEL.key();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private Level originalRootLevel;
    private String previousLevelProperty;

    @Before
    public void setUp() {
        originalRootLevel = root.getLevel();
        previousLevelProperty = System.getProperty(LEVEL_KEY);
    }

    @After
    public void tearDown() {
        if (previousLevelProperty == null) {
            System.setProperty(LEVEL_KEY, ConfigRegistry.API_LOGGING_ROOT_LEVEL.defaultValue());
        } else {
            System.setProperty(LEVEL_KEY, previousLevelProperty);
        }
        root.setLevel(originalRootLevel);
    }

    @Test
    public void appliesStandardRootLevels() {
        System.setProperty(LEVEL_KEY, "DEBUG");
        LoggingLevelConfig.apply();
        assertEquals(Level.DEBUG, root.getLevel());
        assertTrue(root.isDebugEnabled());
        assertFalse(root.isTraceEnabled());

        System.setProperty(LEVEL_KEY, "TRACE");
        LoggingLevelConfig.apply();
        assertEquals(Level.TRACE, root.getLevel());
        assertTrue(root.isTraceEnabled());

        System.setProperty(LEVEL_KEY, "INFO");
        LoggingLevelConfig.apply();
        assertEquals(Level.INFO, root.getLevel());
        assertFalse(root.isDebugEnabled());
        assertFalse(root.isTraceEnabled());
    }

    @Test
    public void supportsWarnErrorAndOff() {
        for (String level : new String[] {"WARN", "ERROR", "OFF"}) {
            System.setProperty(LEVEL_KEY, level);
            LoggingLevelConfig.apply();
            assertEquals(Level.toLevel(level), root.getLevel());
        }
    }

    @Test
    public void rootThresholdControlsActualDebugAndInfoEvents() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                LoggingLevelConfigTest.class.getName() + ".threshold");
        Level originalLoggerLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(null);
        logger.addAppender(appender);

        try {
            System.setProperty(LEVEL_KEY, "DEBUG");
            LoggingLevelConfig.apply();
            logger.debug("debug-visible");

            System.setProperty(LEVEL_KEY, "INFO");
            LoggingLevelConfig.apply();
            logger.debug("debug-hidden");
            logger.info("info-visible");

            assertEquals(2, appender.list.size());
            assertEquals("debug-visible", appender.list.get(0).getFormattedMessage());
            assertEquals("info-visible", appender.list.get(1).getFormattedMessage());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLoggerLevel);
            appender.stop();
        }
    }
}

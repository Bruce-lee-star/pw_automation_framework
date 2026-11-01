package com.hsbc.cmb.hk.dbb.automation.framework.web.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrameworkCoreConfigLoggingTest {

    @Test
    public void logsEffectiveConfigurationOnlyAtDebugAndMasksSecrets() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger frameworkLogger = (Logger) LoggerFactory.getLogger(FrameworkCore.class);
        Level originalRootLevel = root.getLevel();
        Level originalLoggerLevel = frameworkLogger.getLevel();
        String key = ConfigRegistry.WEB_PLAYWRIGHT_PROXY_HTTP_PASSWORD.key();
        String previousValue = System.getProperty(key);
        String secret = "framework-config-secret";
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        frameworkLogger.setLevel(null);
        frameworkLogger.addAppender(appender);

        try {
            System.setProperty(key, secret);
            root.setLevel(Level.DEBUG);
            FrameworkCore.logFrameworkConfigurationIfDebugEnabled();

            String debugOutput = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(debugOutput.contains(key));
            assertTrue(debugOutput.contains(SensitiveDataSanitizer.maskToken()));
            assertFalse(debugOutput.contains(secret));

            int debugEventCount = appender.list.size();
            root.setLevel(Level.INFO);
            FrameworkCore.logFrameworkConfigurationIfDebugEnabled();
            assertEquals(debugEventCount, appender.list.size());
        } finally {
            frameworkLogger.detachAppender(appender);
            appender.stop();
            root.setLevel(originalRootLevel);
            frameworkLogger.setLevel(originalLoggerLevel);
            if (previousValue == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previousValue);
            }
        }
    }
}

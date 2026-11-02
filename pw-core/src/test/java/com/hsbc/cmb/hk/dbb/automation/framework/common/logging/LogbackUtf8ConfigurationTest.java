package com.hsbc.cmb.hk.dbb.automation.framework.common.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import org.junit.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LogbackUtf8ConfigurationTest {

    @Test
    public void consoleAndFileEncodersUseUtf8() throws Exception {
        URL config = getClass().getClassLoader().getResource("logback.xml");
        assertNotNull("logback.xml must be on the test classpath", config);

        LoggerContext context = new LoggerContext();
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(config);

            ConsoleAppender<ILoggingEvent> console =
                    (ConsoleAppender<ILoggingEvent>) context.getLogger(
                            ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
            RollingFileAppender<ILoggingEvent> file =
                    (RollingFileAppender<ILoggingEvent>) context.getLogger(
                            ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getAppender("FILE");

            assertNotNull("Console appender must be configured", console);
            assertNotNull("File appender must be configured", file);
            assertEquals(StandardCharsets.UTF_8,
                    ((ch.qos.logback.core.encoder.LayoutWrappingEncoder<ILoggingEvent>) console.getEncoder())
                            .getCharset());
            assertEquals(StandardCharsets.UTF_8,
                    ((ch.qos.logback.core.encoder.LayoutWrappingEncoder<ILoggingEvent>) file.getEncoder())
                            .getCharset());
        } finally {
            context.stop();
        }
    }
}

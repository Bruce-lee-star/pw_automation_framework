package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Applies the framework's standard root log level from the unified configuration source.
 */
public final class LoggingLevelConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingLevelConfig.class);
    private static final Map<String, Level> LEVELS = Map.of(
            "ALL", Level.ALL,
            "TRACE", Level.TRACE,
            "DEBUG", Level.DEBUG,
            "INFO", Level.INFO,
            "WARN", Level.WARN,
            "ERROR", Level.ERROR,
            "OFF", Level.OFF);

    private LoggingLevelConfig() {
    }

    /**
     * Configure Logback's root logger. Call once during framework startup; values resolve
     * using the standard system property, environment, config-file and default precedence.
     */
    public static void apply() {
        String key = ConfigRegistry.API_LOGGING_ROOT_LEVEL.key();
        String configured = ConfigReader.resolve(
                key, ConfigRegistry.API_LOGGING_ROOT_LEVEL.defaultValue());
        Level level = parse(configured);
        if (level == null) {
            LOGGER.error("Invalid {} value '{}'; using INFO. Valid values: {}",
                    key, configured, LEVELS.keySet());
            level = Level.INFO;
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(level);
    }

    private static Level parse(String value) {
        return value == null ? null : LEVELS.get(value.trim().toUpperCase(Locale.ROOT));
    }
}

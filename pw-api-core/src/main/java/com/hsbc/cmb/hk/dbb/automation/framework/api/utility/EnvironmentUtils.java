package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compatibility facade for legacy environment/property access.
 *
 * <p>Configuration reads delegate to {@link ConfigReader}; new framework code should use
 * {@code ConfigReader} directly. This facade remains available for existing business code and
 * retains the explicit system-property mutation methods.</p>
 */
public class EnvironmentUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentUtils.class);

    /**
     * Get the current environment instance (for compatibility)
     *
     * @return this instance for method chaining
     */
    public static EnvironmentUtils currentEnvironment() {
        return new EnvironmentUtils();
    }

    /**
     * Get property value from system properties
     *
     * @param key the property key
     * @return the property value, or null if not found
     */
    public String getProperty(String key) {
        return ConfigReader.getProperty(key);
    }

    /**
     * Get property value from system properties with default value
     *
     * @param key the property key
     * @param defaultValue the default value if property not found
     * @return the property value, or default value if not found
     */
    public static String getProperty(String key, String defaultValue) {
        return ConfigReader.getProperty(key, defaultValue);
    }

    /**
     * Get environment variable value
     *
     * @param key the environment variable key
     * @return the environment variable value, or null if not found
     */
    public String getEnvironmentVariable(String key) {
        return ConfigReader.getEnvironmentVariable(key);
    }

    /**
     * Set system property value
     *
     * @param key the property key
     * @param value the property value
     */
    public void setProperty(String key, String value) {
        System.setProperty(key, value);
        LOGGER.debug("Set system property: {}", key);
    }

    /**
     * Clear system property
     *
     * @param key the property key
     */
    public void clearProperty(String key) {
        System.clearProperty(key);
        LOGGER.debug("Cleared system property: {}", key);
    }

    /**
     * Get current environment name (for 框架 compatibility)
     * This returns the environment from system properties
     *
     * @return the current environment name
     */
    public String getEnvironmentName() {
        return getProperty("env");
    }
}

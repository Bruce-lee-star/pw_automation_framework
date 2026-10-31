package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link ConfigReader#getProperty} 宽松读取契约（Serenity 风格业务入口，2026-10-31）。
 *
 * <p>语义对齐 {@link ConfigReader#resolve(String, String)}：空白值算命中、null 才算未命中；
 * 未登记键走 {@link ConfigReader#DEFAULT_PRECEDENCE}，已登记键自动用注册表声明层序；
 * 环境变量层不可在单测中注入（{@code System.getenv} 只读），以 {@link ConfigReader#toEnvKey}
 * 键名归一映射 + 系统属性层命中为代表验证。
 */
public class ConfigReaderTest {

    private static final String UNREGISTERED_KEY = "config.reader.test.no.such.key";

    private final List<String> dirtyProps = new ArrayList<>();

    @After
    public void cleanup() {
        for (String k : dirtyProps) {
            System.clearProperty(k);
        }
        dirtyProps.clear();
    }

    private void setProp(String key, String value) {
        System.setProperty(key, value);
        dirtyProps.add(key);
    }

    @Test
    public void getPropertyReturnsNullWhenAllLayersMiss() {
        assertNull(ConfigReader.getProperty(UNREGISTERED_KEY));
    }

    @Test
    public void getPropertyFallsBackToDefaultValue() {
        assertEquals("fallback",
                ConfigReader.getProperty(UNREGISTERED_KEY, "fallback"));
    }

    @Test
    public void getPropertyWithExplicitNullDefaultReturnsNull() {
        assertNull(ConfigReader.getProperty(UNREGISTERED_KEY, null));
    }

    @Test
    public void getPropertyReadsSystemPropertyLayer() {
        setProp(UNREGISTERED_KEY, "sysprop-value");
        assertEquals("sysprop-value", ConfigReader.getProperty(UNREGISTERED_KEY));
        assertEquals("sysprop-value", ConfigReader.getProperty(UNREGISTERED_KEY, "fallback"));
    }

    @Test
    public void getPropertyBlankValueCountsAsHit() {
        setProp(UNREGISTERED_KEY, "");
        assertEquals("", ConfigReader.getProperty(UNREGISTERED_KEY));
        // 空白命中 ⇒ 默认值不生效（与 resolve 语义一致）
        assertEquals("", ConfigReader.getProperty(UNREGISTERED_KEY, "fallback"));
    }

    @Test
    public void getPropertyRespectsRegisteredKeyPrecedence() {
        String registeredKey = ConfigRegistry.WEB_FRAMEWORK_LOGGING.key();
        setProp(registeredKey, "false");
        assertEquals("false", ConfigReader.getProperty(registeredKey));
        assertEquals("false", ConfigReader.getProperty(registeredKey, "true"));
    }

    @Test
    public void envKeyNormalizationMapsDotsAndDashes() {
        assertEquals("API_BASE_URI", ConfigReader.toEnvKey("api.base.uri"));
        assertEquals("FRAMEWORK_PLAYWRIGHT_HEADLESS",
                ConfigReader.toEnvKey("framework.playwright.headless"));
        assertEquals("ROUTE_CAPTURE_ALL_TYPE", ConfigReader.toEnvKey("route-capture-all-type"));
    }
}

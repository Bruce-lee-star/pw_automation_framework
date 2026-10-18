package com.hsbc.cmb.hk.dbb.automation.framework.web.junit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * CucumberWithPw 的框架默认值注入：补齐、幂等、尊重业务显式配置。
 *
 * <p>系统属性是 JVM 全局状态，故每个用例前后都完整保存/还原原值（含 null），
 * 不污染同 JVM 的其它测试类。</p>
 */
public class CucumberWithPwTest {

    private String originalPlugins;
    private String originalFactory;

    @Before
    public void save() {
        originalPlugins = System.getProperty(CucumberWithPw.PLUGIN_PROPERTY);
        originalFactory = System.getProperty(CucumberWithPw.OBJECT_FACTORY_PROPERTY);
    }

    @After
    public void restore() {
        restore(CucumberWithPw.PLUGIN_PROPERTY, originalPlugins);
        restore(CucumberWithPw.OBJECT_FACTORY_PROPERTY, originalFactory);
    }

    @Test
    public void absentPropertiesAreFilledWithFrameworkDefaults() {
        System.clearProperty(CucumberWithPw.PLUGIN_PROPERTY);
        System.clearProperty(CucumberWithPw.OBJECT_FACTORY_PROPERTY);

        CucumberWithPw.ensureFrameworkDefaults();

        String plugins = System.getProperty(CucumberWithPw.PLUGIN_PROPERTY);
        for (String expected : CucumberWithPw.FRAMEWORK_PLUGINS) {
            assertTrue("missing framework plugin: " + expected, plugins.contains(expected));
        }
        assertEquals(CucumberWithPw.FRAMEWORK_OBJECT_FACTORY,
                System.getProperty(CucumberWithPw.OBJECT_FACTORY_PROPERTY));
    }

    @Test
    public void repeatedInvocationDoesNotDuplicatePlugins() {
        System.clearProperty(CucumberWithPw.PLUGIN_PROPERTY);

        CucumberWithPw.ensureFrameworkDefaults();
        CucumberWithPw.ensureFrameworkDefaults();

        String plugins = System.getProperty(CucumberWithPw.PLUGIN_PROPERTY);
        for (String expected : CucumberWithPw.FRAMEWORK_PLUGINS) {
            assertEquals("duplicated plugin: " + expected, 1, countOccurrences(plugins, expected));
        }
    }

    @Test
    public void businessDeclaredPluginsArePreserved() {
        System.setProperty(CucumberWithPw.PLUGIN_PROPERTY, "com.business.MyPlugin,pretty");

        CucumberWithPw.ensureFrameworkDefaults();

        String plugins = System.getProperty(CucumberWithPw.PLUGIN_PROPERTY);
        assertTrue("business plugin lost", plugins.contains("com.business.MyPlugin"));
        assertTrue("pretty lost", plugins.contains("pretty"));
        for (String expected : CucumberWithPw.FRAMEWORK_PLUGINS) {
            assertTrue("missing framework plugin: " + expected, plugins.contains(expected));
        }
    }

    @Test
    public void explicitObjectFactoryWins() {
        System.setProperty(CucumberWithPw.OBJECT_FACTORY_PROPERTY, "com.business.MyFactory");

        CucumberWithPw.ensureFrameworkDefaults();

        assertEquals("com.business.MyFactory",
                System.getProperty(CucumberWithPw.OBJECT_FACTORY_PROPERTY));
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null) {
            return 0;
        }
        int count = 0;
        for (String part : Arrays.asList(haystack.split(","))) {
            if (part.trim().equals(needle)) {
                count++;
            }
        }
        return count;
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}

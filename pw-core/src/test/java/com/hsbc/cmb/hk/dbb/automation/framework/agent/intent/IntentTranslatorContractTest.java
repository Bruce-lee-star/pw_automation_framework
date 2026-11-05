package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * IntentTranslator SPI 结构契约（设计 §10.4 R6：AI 无直接浏览器调用能力）。
 *
 * <p>用纯 JDK 反射锁定：SPI 与默认实现的方法签名<b>不得</b>出现任何浏览器 / 通道 /
 * Playwright 类型。这把「AI 只产不可信建议」从注释升级为可证伪的结构护栏。</p>
 */
public class IntentTranslatorContractTest {

    private static final Set<String> FORBIDDEN_PACKAGES = Set.of(
            "com.microsoft.playwright",
            "com.hsbc.cmb.hk.dbb.automation.framework.web",
            "org.openqa.selenium");

    private static final Set<String> FORBIDDEN_SIMPLE_NAMES = Set.of(
            "Page", "Browser", "BrowserContext", "Playwright", "BrowserCommandChannel", "Locator");

    @Test
    public void spiDeclaresOnlyPureDataTranslateMethod() {
        Method[] declared = IntentTranslator.class.getDeclaredMethods();
        Set<String> names = new TreeSet<>();
        for (Method m : declared) {
            // JaCoCo 离线插桩会注入 $jacocoInit 合成方法，不属于 API 契约
            if (!m.isSynthetic()) {
                names.add(m.getName());
            }
        }
        assertEquals("SPI 只应声明 translate + 默认 id", Set.of("translate", "id"), names);
    }

    @Test
    public void spiSignaturesExposeNoBrowserHandle() {
        for (Method m : IntentTranslator.class.getDeclaredMethods()) {
            assertNoBrowserType(m.getReturnType());
            for (Class<?> p : m.getParameterTypes()) {
                assertNoBrowserType(p);
            }
        }
    }

    @Test
    public void ruleBasedImplementationExposesNoBrowserHandle() {
        for (Method m : RuleBasedIntentTranslator.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            assertNoBrowserType(m.getReturnType());
            for (Class<?> p : m.getParameterTypes()) {
                assertNoBrowserType(p);
            }
        }
        for (java.lang.reflect.Constructor<?> c : RuleBasedIntentTranslator.class.getDeclaredConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertNoBrowserType(p);
            }
        }
    }

    @Test
    public void translateReturnTypeIsStepPlanDraftOnly() {
        for (Method m : IntentTranslator.class.getDeclaredMethods()) {
            if ("translate".equals(m.getName())) {
                assertEquals("translate 必须只返回 StepPlan（draft 建议），不返回执行结果",
                        "com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan",
                        m.getReturnType().getName());
                assertEquals(1, m.getParameterCount());
                assertEquals(RecordedSkeleton.class, m.getParameterTypes()[0]);
            }
        }
    }

    @Test
    public void noServiceLoaderRegistrationForIntentTranslator() {
        // 治理：IntentTranslator 必须手动注入，禁止 classpath 自动发现（防未审查 LLM 静默启用）
        assertFalse("IntentTranslator 不应有 ServiceLoader 自动发现入口",
                Arrays.stream(IntentTranslator.class.getDeclaredMethods())
                        .anyMatch(m -> m.getName().toLowerCase().contains("resolve")));
        assertTrue(java.util.ServiceLoader.load(IntentTranslator.class).stream().findAny().isEmpty());
    }

    private static void assertNoBrowserType(Class<?> type) {
        if (type.isPrimitive() || type.isArray()) {
            return;
        }
        String name = type.getName();
        for (String pkg : FORBIDDEN_PACKAGES) {
            assertFalse("SPI 泄漏浏览器类型: " + name, name.startsWith(pkg));
        }
        assertFalse("SPI 泄漏浏览器句柄: " + name, FORBIDDEN_SIMPLE_NAMES.contains(type.getSimpleName()));
    }
}

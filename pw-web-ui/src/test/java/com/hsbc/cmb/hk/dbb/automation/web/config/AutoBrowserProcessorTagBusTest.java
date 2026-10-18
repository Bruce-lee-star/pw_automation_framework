package com.hsbc.cmb.hk.dbb.automation.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * AutoBrowser tags 读取（原生生命周期 holder 语义）回归守卫（DEV-V5）。
 *
 * <p><b>被守卫的真实缺陷</b>：旧实现从 {@code StepEventBus}（per-thread ThreadLocal，测试/glue 线程上是空 bus）
 * 读 scenario tags，导致 {@code @firefox}/{@code @edge} 覆盖永久失效且静默无告警。
 *
 * <p>DEV-V5 改为：Cucumber 标签经 {@link AutoBrowserProcessor#setScenarioTags(List)} 注入原生 holder，
 * 由 {@link AutoBrowserProcessor#processAutoBrowserAnnotation()} 在 {@code getBrowserType()} 时消费。
 * 本用例直接验证"holder 注入 → 注解驱动 → 标签生效"链路。</p>
 *
 * <p>注：测试类故意放在 {@code com.hsbc...web.config}（非 {@code framework.} 前缀），
 * 因为 {@link AutoBrowserProcessor#processAutoBrowserAnnotation()} 的栈扫描会跳过 {@code framework.} 包，
 * 只有 glue/业务类才会被识别（与生产一致）。</p>
 */
@AutoBrowser(enabled = true, verbose = false)
public class AutoBrowserProcessorTagBusTest {

    @Before
    public void setUp() {
        AutoBrowserProcessor.clearProcessingState();
        AutoBrowserProcessor.clearScenarioTags();
    }

    @After
    public void tearDown() {
        AutoBrowserProcessor.clearProcessingState();
        AutoBrowserProcessor.clearScenarioTags();
    }

    /** 已就绪：从原生 holder 读到的 tags 必须能驱动浏览器覆盖（firefox → 覆盖生效）。 */
    @Test
    public void readsScenarioTagsFromLifecycleContext() {
        AutoBrowserProcessor.setScenarioTags(List.of("@firefox", "@test"));
        AutoBrowserProcessor.processAutoBrowserAnnotation();

        // 诊断：确认 processAutoBrowserAnnotation 已识别 @AutoBrowser 并完成消费
        assertTrue("processAutoBrowserAnnotation 应识别 @AutoBrowser 并激活",
                AutoBrowserProcessor.hasAutoBrowserActive());

        // 标签链路真正打通：browser tag 被识别
        assertEquals("必须能从 tags 解出浏览器类型（firefox）",
                "firefox", BrowserOverrideManager.extractBrowserFromTags(new String[]{"firefox"}));
        assertEquals("覆盖后的有效浏览器类型应为 firefox", "firefox",
                BrowserOverrideManager.getEffectiveBrowserType());
    }

    /** 标签为空：不得注入任何覆盖，亦不得抛异常（由下一个 step 自然重试）。 */
    @Test
    public void emptyTagsDoNotOverride() {
        AutoBrowserProcessor.setScenarioTags(List.of());
        AutoBrowserProcessor.processAutoBrowserAnnotation();

        assertFalse("空标签时不得产生浏览器覆盖", BrowserOverrideManager.hasOverride());
    }
}

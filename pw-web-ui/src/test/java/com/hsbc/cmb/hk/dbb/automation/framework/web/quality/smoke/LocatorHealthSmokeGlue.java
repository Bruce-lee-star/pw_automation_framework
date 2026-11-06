package com.hsbc.cmb.hk.dbb.automation.framework.web.quality.smoke;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.LocatorHealthRecorder;
import com.microsoft.playwright.PlaywrightException;
import io.cucumber.java.en.Given;

/**
 * 门禁端到端冒烟用的最小 glue：模拟一次 Playwright strict 违例（一次定位命中多个元素）。
 *
 * <p>选择器由系统属性注入，使同一份 feature 可以在"首次运行（播种）"与
 * "第二次运行（新增选择器 → 应判红）"之间切换，无需改文件。
 *
 * <p>本类只用于 {@code LocatorHealthGateCucumberRuntimeTest}，不进入生产包。
 */
public class LocatorHealthSmokeGlue {

    /** 冒烟用系统属性：本次运行要记录哪个选择器的违例。 */
    public static final String SELECTOR_PROPERTY = "pw.locator.health.smoke.selector";

    /** 缺省选择器（＝首次运行播种时固化的那一个）。 */
    public static final String DEFAULT_SELECTOR = "#login";

    @Given("a locator violation is recorded")
    public void recordViolation() {
        String selector = System.getProperty(SELECTOR_PROPERTY, DEFAULT_SELECTOR);
        LocatorHealthRecorder.recordUse(selector);
        LocatorHealthRecorder.recordFailure(new PlaywrightException(
                        "strict mode violation: locator('" + selector + "') resolved to 2 elements"),
                selector, "click");
    }
}

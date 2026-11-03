package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * AutoBrowser Processor - 框架层自动处理 @AutoBrowser 注解（企业级 · 去 框架 专项 DEV-V5）。
 *
 * <p>工作原理：
 * <ol>
 *   <li>在 {@code PlaywrightManager.getBrowserType()} 被调用时自动触发；</li>
 *   <li>通过堆栈跟踪找到 Glue 类；</li>
 *   <li>检查是否有 {@code @AutoBrowser} 注解；</li>
 *   <li>从<b>原生生命周期上下文</b>（{@link #setScenarioTags} 注入的当前场景标签）读取标签；</li>
 *   <li>自动调用 {@link BrowserOverrideManager#setScenarioTags(String[])}。</li>
 * </ol>
 *
 * <p>标签来源：原经 框架 {@code StepEventBus} 读取，去 框架 后改为由
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkCucumberEventListener}
 * 在场景开始时将 Cucumber 标签写入本 holder（{@link #setScenarioTags}），本类直接消费，
 * 不再依赖任何第三方总线。</p>
 *
 * <p>优势：零配置、自动化、对测试代码透明。</p>
 */
public class AutoBrowserProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AutoBrowserProcessor.class);

    // 缓存已检查过的类，避免重复扫描
    private static final ThreadLocal<Boolean> processedForCurrentScenario = new ThreadLocal<>();

    /** 当前场景标签（原生生命周期上下文；由 onScenarioStarted 注入，场景结束清除）。 */
    private static final ContextKey<List<String>> CURRENT_SCENARIO_TAGS =
            ContextKey.of("autoBrowser.currentScenarioTags", (Class<List<String>>) (Class<?>) List.class);

    /**
     * 注入当前场景标签（由原生生命周期监听器在场景开始时调用）。
     *
     * @param tags Cucumber 标签（如 {@code @test}）；null/空按空集合处理
     */
    public static void setScenarioTags(List<String> tags) {
        TestContextHolder.get().set(CURRENT_SCENARIO_TAGS, tags == null ? List.of() : List.copyOf(tags));
    }

    /** 清除当前场景标签（由原生生命周期监听器在场景结束时调用）。 */
    public static void clearScenarioTags() {
        TestContextHolder.get().remove(CURRENT_SCENARIO_TAGS);
    }

    /**
     * 处理 @AutoBrowser 注解。
     *
     * 在 PlaywrightManager.getBrowserType() 中调用此方法，自动检测并设置浏览器覆盖配置。
     */
    public static void processAutoBrowserAnnotation() {
        // 避免在同一个 Scenario 中重复处理
        if (Boolean.TRUE.equals(processedForCurrentScenario.get())) {
            logger.debug("Already processed for current scenario, skipping");
            return;
        }

        logger.debug("Processing @AutoBrowser annotation...");

        try {
            // 1. 从堆栈跟踪找到 Glue 类
            Class<?> glueClass = findGlueClass();

            if (glueClass == null) {
                logger.warn("No class with @AutoBrowser found in call stack");
                logger.debug("Call stack trace for debugging:");
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                for (int i = 0; i < Math.min(15, stackTrace.length); i++) {
                    logger.debug("  [{}] {}", i, stackTrace[i].getClassName());
                }
                return;
            }

            logger.debug("Found class '{}' with @AutoBrowser", glueClass.getName());

            // 2. 检查 @AutoBrowser 注解
            AutoBrowser autoBrowser = glueClass.getAnnotation(AutoBrowser.class);

            if (autoBrowser == null || !autoBrowser.enabled()) {
                logger.warn("@AutoBrowser annotation not enabled on class '{}'",
                        glueClass.getSimpleName());
                return;
            }

            logger.debug("@AutoBrowser annotation is enabled (verbose={})",
                    autoBrowser.verbose());

            // 3. 从原生生命周期上下文读取当前 scenario 标签
            List<String> tags = TestContextHolder.get().get(CURRENT_SCENARIO_TAGS);

            if (tags == null || tags.isEmpty()) {
                logger.debug("No tags found in lifecycle context - this is normal during early test initialization");
                return;
            }

            logger.debug("Found {} tags: {}", tags.size(), tags);

            // 4. 设置 Scenario 标签
            BrowserOverrideManager.setScenarioTags(tags.toArray(new String[0]));

            String effectiveBrowser = BrowserOverrideManager.getEffectiveBrowserType();
            logger.debug("Effective browser type set to: {}", effectiveBrowser);

            // 标记为已处理
            processedForCurrentScenario.set(true);

        } catch (Exception e) {
            logger.error("ERROR processing @AutoBrowser annotation: {}", e.getMessage(), e);
        }
    }

    /**
     * 清除处理状态（在 Scenario 结束时调用）。
     */
    public static void clearProcessingState() {
        processedForCurrentScenario.remove();

        // 同时清理 BrowserOverrideManager
        if (BrowserOverrideManager.hasOverride()) {
            BrowserOverrideManager.clearOverrideBrowser();
        }
        BrowserOverrideManager.clearScenarioTags();
        clearScenarioTags(); // 原生标签 holder
    }

    /**
     * 从当前调用堆栈中找到带有 @AutoBrowser 注解的类。
     *
     * 只要类有 @AutoBrowser 注解就会被识别，不限包名。
     *
     * @return 带 @AutoBrowser 注解的类，如果没找到则返回 null
     */
    private static Class<?> findGlueClass() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        logger.debug("Scanning {} stack trace elements for @AutoBrowser annotation", stackTrace.length);

        for (int i = 0; i < stackTrace.length; i++) {
            StackTraceElement element = stackTrace[i];
            String className = element.getClassName();

            logger.trace("[{}] Checking class: {}", i, className);

            // 跳过框架自身的类
            if (className.startsWith("com.hsbc.cmb.hk.dbb.automation.framework.")) {
                continue;
            }

            // 跳过 JDK / 测试框架 / 第三方库的类（io.cucumber 等），仅作防御性跳过
            if (className.startsWith("java.")
                    || className.startsWith("sun.")
                    || className.startsWith("org.junit.")
                    || className.startsWith("io.cucumber.")) {
                continue;
            }

            try {
                Class<?> clazz = Class.forName(className);

                // 只要类有 @AutoBrowser 注解就处理
                if (clazz.isAnnotationPresent(AutoBrowser.class)) {
                    AutoBrowser autoBrowser = clazz.getAnnotation(AutoBrowser.class);
                    logger.debug("[{}] Found @AutoBrowser annotation on class: {} (enabled={})",
                            i, className, autoBrowser.enabled());

                    if (autoBrowser.enabled()) {
                        return clazz;
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.trace("[{}] Could not load class: {}", i, className);
            } catch (NoClassDefFoundError e) {
                logger.trace("[{}] Could not load class (dependency issue): {}", i, className);
            } catch (Throwable e) {
                logger.trace("[{}] Error loading class {}: {}", i, className, e.getMessage());
            }
        }

        return null;
    }

    /**
     * 检查当前是否有 @AutoBrowser 注解生效。
     *
     * @return true 如果有注解生效
     */
    public static boolean hasAutoBrowserActive() {
        return Boolean.TRUE.equals(processedForCurrentScenario.get());
    }
}

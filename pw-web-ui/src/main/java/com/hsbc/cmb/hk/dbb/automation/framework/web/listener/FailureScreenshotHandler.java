package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.regex.Pattern;

/**
 *  收口 {@code PlaywrightListener} 的截图捕获与文件名清洗职责（原 takeScreenshot / sanitize* 簇）。
 *
 * <p>截图是监听器中最独立的子系统：仅依赖 {@link PlaywrightManager} 产出 PNG/HTML 路径并封装为
 * Serenity 的 {@link ScreenshotAndHtmlSource}。迁移后日志路由回本类 logger（保持生产溯源一致），
 * 重入守卫（takingScreenshot）经 core 的 {@code TestContext}/{@code ContextKey} 收拢为 per-thread 持有（T3-1 收口），避免污染监听器的失败守卫状态。</p>
 *
 * <p><b>线程安全：</b>{@code takingScreenshot} 为 per-thread ThreadLocal，并行 scenario 互不干扰；
 * 截图计数经 {@link ListenerPerfStats} 的原子计数器，无竞态。</p>
 */
final class FailureScreenshotHandler {

    private static final Logger logger = LoggerFactory.getLogger(FailureScreenshotHandler.class);

    private static final Pattern SANITIZE_FILENAME_PATTERN = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern SANITIZE_NAME_PATTERN = Pattern.compile("[^a-zA-Z0-9]");

    private static final ContextKey<Boolean> TAKING_SCREENSHOT_KEY =
            ContextKey.of("failureScreenshotHandler.takingScreenshot", Boolean.class);

    private FailureScreenshotHandler() {
    }

    /**
     * 截图并封装为 Serenity 对象（不注册到步骤列表，由调用方决定是否加入）。
     *
     * @param screenshotName 截图名称
     * @return 截图对象；递归重入或未生成文件时返回 null
     */
    static ScreenshotAndHtmlSource capture(String screenshotName) {
        if (Boolean.TRUE.equals(TestContextHolder.get().get(TAKING_SCREENSHOT_KEY))) {
            VerboseLogging.logDebugIfVerbose(logger,
                    "Skipping screenshot - already taking screenshot to prevent recursion");
            return null;
        }
        try {
            TestContextHolder.get().set(TAKING_SCREENSHOT_KEY, Boolean.TRUE);
            // §9.4 登录页位图脱敏：URL 含 /logon 时，对密码框叠加 DOM 覆盖层（避免密码出现在截图中）。
            // 脱敏准备失败（如页面半死）时，出于安全优先跳过截图，而非带密码截图。
            boolean onLogon = isOnLogonPage();
            boolean redactionPrepared = false;
            if (onLogon) {
                redactionPrepared = prepareLogonRedaction();
                if (!redactionPrepared) {
                    VerboseLogging.logInfoIfVerbose(logger,
                            "On logon page but password-field redaction failed -> skipping screenshot for security");
                    return null;
                }
            }
            try {
                String screenshotPath = PlaywrightScreenshotManager.takeScreenshot(screenshotName);
                if (screenshotPath != null) {
                    File pngFile = new File(screenshotPath);
                    File htmlFile = new File(screenshotPath.replace(".png", ".html"));
                    if (pngFile.exists()) {
                        ScreenshotAndHtmlSource result = new ScreenshotAndHtmlSource(
                                pngFile, htmlFile.exists() && htmlFile.length() > 0 ? htmlFile : null);
                        VerboseLogging.logDebugIfVerbose(logger, "Screenshot captured: {} -> {}",
                                screenshotName, pngFile.getName());
                        ListenerPerfStats.incrementScreenshot();
                        return result;
                    }
                    VerboseLogging.logInfoIfVerbose(logger, "Screenshot file not found: {}", screenshotPath);
                }
            } finally {
                if (redactionPrepared) {
                    clearLogonRedaction();
                }
            }
        } catch (Exception e) {
            VerboseLogging.logInfoIfVerbose(logger, "Failed to capture screenshot: {}", screenshotName, e);
        } finally {
            TestContextHolder.get().set(TAKING_SCREENSHOT_KEY, Boolean.FALSE);
        }
        return null;
    }

    /** 仅替换非 a-zA-Z0-9_- 字符（用于文件名）。 */
    static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "unnamed";
        }
        return SANITIZE_FILENAME_PATTERN.matcher(name).replaceAll("_");
    }

    /** 替换所有非 a-zA-Z0-9 字符（用于 display name）。 */
    static String sanitizeName(String name) {
        return name == null ? "unnamed" : SANITIZE_NAME_PATTERN.matcher(name).replaceAll("_");
    }

    /** scenario 结束时清理 per-context 重入标记（由 TestContext.resetForCurrentThread 统一解绑）。 */
    static void clearThreadState() {
        TestContextHolder.get().remove(TAKING_SCREENSHOT_KEY);
    }

    // ===================== §9.4 登录页密码框位图脱敏 =====================

    /** 当前页面是否处于登录页（URL 含 /logon）。半死 / 已关闭页面返回 false。 */
    private static boolean isOnLogonPage() {
        Page page = PlaywrightManager.getPageThreadLocal();
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            String url = page.url();
            return url != null && url.toLowerCase().contains("/logon");
        } catch (Exception e) {
            return false;
        }
    }

    /** 对密码框叠加不透明 DOM 覆盖层，使截图中不暴露密码。返回是否成功叠加。 */
    private static boolean prepareLogonRedaction() {
        Page page = PlaywrightManager.getPageThreadLocal();
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            Locator password = page.locator("input[type=password]");
            if (password.count() == 0) {
                return false;
            }
            password.evaluateAll("els => els.forEach(el => {"
                    + " const r = el.getBoundingClientRect();"
                    + " const d = document.createElement('div');"
                    + " d.setAttribute('data-pw-sso-redact', '1');"
                    + " d.style.position='fixed';"
                    + " d.style.left=r.left+'px';"
                    + " d.style.top=r.top+'px';"
                    + " d.style.width=r.width+'px';"
                    + " d.style.height=r.height+'px';"
                    + " d.style.background='#000';"
                    + " d.style.zIndex='2147483647';"
                    + " (document.body || document.documentElement).appendChild(d);"
                    + "});");
            VerboseLogging.logDebugIfVerbose(logger,
                    "Logon redaction overlay applied to {} password field(s)", password.count());
            return true;
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "Logon redaction overlay failed: {}", e.getMessage());
            return false;
        }
    }

    /** 移除 §9.4 叠加的密码框覆盖层，避免影响后续页面操作。 */
    private static void clearLogonRedaction() {
        Page page = PlaywrightManager.getPageThreadLocal();
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            page.evaluate("() => document.querySelectorAll('[data-pw-sso-redact]').forEach(d => d.remove());");
        } catch (Exception ignored) {
            VerboseLogging.logDebugIfVerbose(logger,
                    "Failed to clear logon redaction overlay: {}", ignored.getMessage());
        }
    }
}

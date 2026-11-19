package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 失败时当前页面源码快照采集（报告 per-case 页 "Page source" Tab 的数据源）。
 *
 * <p>与失败截图并列采集，但走独立链路（{@link ReportingBridge#pageSourceTarget()} /
 * {@link ReportingBridge#attachPageSource(Path)}，同 trace/video 模式），不塞进文本附件：
 * 源码作为<b>独立 HTML 文件</b>用 iframe 渲染，才能保留页面自身样式、看到完整渲染后的页面。</p>
 *
 * <p><b>样式保真</b>：{@code page.content()} 里的外链 CSS/JS/图片多为相对路径，脱离原站点后失效。
 * 故在 {@code <head>} 注入 {@code <base href="<page.url()>">}，让相对路径回源站解析——
 * 页面样式、布局、图片与失败现场一致。源码本身完整保留（含 doctype/head/body），
 * 既能在 iframe 看渲染页，也能切到源码视图逐行排查。</p>
 *
 * <p>诊断证据不得影响测试：全程 try/catch 静默，任何失败只 debug 日志、不抛异常。</p>
 */
final class FailurePageSourceHandler {

    private static final Logger logger = LoggerFactory.getLogger(FailurePageSourceHandler.class);
    private static final Pattern HEAD_CLOSE = Pattern.compile("(?i)</head>");

    private FailurePageSourceHandler() {
    }

    /** 在失败回调处调用；非报告环境 / 无页面 / 采集失败均安全 no-op。 */
    static void capture() {
        try {
            ReportingBridge bridge = ReportingBridgeRegistry.get();
            if (!bridge.isReportingActive()) {
                return;
            }
            Path target = bridge.pageSourceTarget();
            if (target == null) {
                return;
            }
            Page page = PlaywrightManager.getPageThreadLocal();
            if (page == null || page.isClosed()) {
                return;
            }
            String html = page.content();
            if (html == null || html.isEmpty()) {
                return;
            }
            String pageUrl = safeUrl(page);
            String withBase = injectBase(html, pageUrl);
            Files.createDirectories(target.getParent());
            Files.writeString(target, withBase, StandardCharsets.UTF_8);
            bridge.attachPageSource(target);
            logger.debug("Failure page source captured: {}", target.getFileName());
        } catch (Throwable t) {
            logger.debug("Failed to capture page source on failure", t);
        }
    }

    /** 注入 {@code <base href>}：有 head 就插在 </head> 前，否则置于文档开头。 */
    static String injectBase(String html, String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return html;
        }
        String escaped = pageUrl.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
        String baseTag = "  <base href=\"" + escaped + "\">\n</head>";
        if (HEAD_CLOSE.matcher(html).find()) {
            return HEAD_CLOSE.matcher(html).replaceFirst(java.util.regex.Matcher.quoteReplacement(baseTag));
        }
        return baseTag + "\n" + html;
    }

    private static String safeUrl(Page page) {
        try {
            return page.url();
        } catch (Throwable t) {
            return null;
        }
    }
}

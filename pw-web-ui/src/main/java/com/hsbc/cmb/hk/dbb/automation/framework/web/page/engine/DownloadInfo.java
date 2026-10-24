package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 下载结果值对象：{@link PageLifecycleCoordinator#waitForDownloadSaved} 完成后返回的一次下载的已落盘信息。
 *
 * <p>企业级下载契约：把 Playwright 原生 {@link com.microsoft.playwright.Download} 的「临时文件陷阱」封装掉——
 * 返回的 {@link #path()} 保证是已通过 {@code Download.saveAs(...)} 落到 {@link #saveDir()}
 * （即 {@code <playwright.browser.downloadsPath>/thread-<线程序号>}）的<b>稳定文件</b>，
 * 不会因 context 关闭 / 被 GC 而回收。调用方无需再直接理解 {@code Download.path()} 的临时性。</p>
 *
 * <p><b>生命周期提醒</b>：{@link #saveDir()} 所在目录在<b>用例结束</b>时由框架按线程子目录自动清理，
 * 故 {@link #path()} 仅在「本用例内」稳定。要把下载文件作为跨用例的报告证据留存，必须在用例内
 * 调用 {@link #attachToReport()}（复制进报告产物目录）——这也是
 * {@code playwright.browser.download.attachToReport.enabled=true} 时 {@code waitForDownloadSaved}
 * 自动做的事。</p>
 *
 * @param saveDir  本次落盘根目录（本线程隔离目录，用例结束由框架自动清理）
 * @param path     已落盘文件的绝对路径（稳定、可读、可挂报告证据）
 * @param filename 服务器建议的文件名（{@code Download.suggestedFilename()}）
 * @param url      下载来源 URL（{@code Download.url()}）
 */
public record DownloadInfo(Path saveDir, Path path, String filename, String url) {

    /**
     * 把本次下载文件挂载到测试报告：复制进报告产物目录（{@code <outputDir>/<outcomeId>/<file>}），
     * 出现在 per-case 报告的「Downloads」Tab，可在线查看/下载，<b>不再受用例结束清理影响</b>。
     *
     * <p>不受 {@code playwright.browser.download.attachToReport.enabled} 开关约束——调用即上报；
     * 该开关只控制 {@code waitForDownloadSaved} 是否<b>自动</b>调用本方法。</p>
     *
     * @return {@code true} 已上报；{@code false} 上报失败（仅降级日志，不影响业务）
     */
    public boolean attachToReport() {
        try {
            ReportingBridgeRegistry.get().recordDownloadableFile(filename, path);
            return true;
        } catch (Exception | LinkageError e) {
            LoggerFactory.getLogger(DownloadInfo.class)
                    .warn("[download] attachToReport failed for {}: {}", filename, e.toString());
            return false;
        }
    }
}

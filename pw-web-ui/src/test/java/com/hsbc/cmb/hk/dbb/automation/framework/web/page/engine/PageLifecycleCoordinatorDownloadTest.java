package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下载落盘契约测试（无浏览器，纯 Mockito 隔离 BasePage / Page / Download）。
 * 验证 {@link PageLifecycleCoordinator#waitForDownloadSaved(BasePage, Runnable, int, Path)}
 * 一次性返回稳定路径 / 文件名 / 目录，并确实触发 {@code saveAs} 到指定目录。
 */
public class PageLifecycleCoordinatorDownloadTest {

    @Test
    public void waitForDownloadSaved_returnsStablePathAndTriggersSaveAs() throws Exception {
        Path dir = Files.createTempDirectory("dl-test");

        Page page = mock(Page.class);
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(page);

        Download dl = mock(Download.class);
        when(dl.suggestedFilename()).thenReturn("invoice.pdf");
        when(dl.url()).thenReturn("https://example.com/invoice.pdf");
        when(page.waitForDownload(any(Page.WaitForDownloadOptions.class), any(Runnable.class)))
                .thenReturn(dl);

        // 显式落盘目录重载（无需 stub 静态 PlaywrightManager.downloadDirectoryForCurrentThread()）
        DownloadInfo info = PageLifecycleCoordinator.waitForDownloadSaved(bp, () -> {}, 30, dir);

        assertEquals(dir, info.saveDir());
        assertEquals("invoice.pdf", info.filename());
        assertEquals("https://example.com/invoice.pdf", info.url());
        assertEquals(dir.resolve("invoice.pdf"), info.path());
        verify(dl).saveAs(dir.resolve("invoice.pdf"));
    }

    @Test
    public void downloadInfo_isImmutableValueObject() throws Exception {
        Path dir = Files.createTempDirectory("dl-test");
        Path file = dir.resolve("a.pdf");
        DownloadInfo info = new DownloadInfo(dir, file, "a.pdf", "https://x/a.pdf");

        assertEquals(dir, info.saveDir());
        assertEquals(file, info.path());
        assertEquals("a.pdf", info.filename());
        assertEquals("https://x/a.pdf", info.url());
    }
}

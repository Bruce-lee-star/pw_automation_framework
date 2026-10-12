package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media;

import org.junit.Test;

import static org.junit.Assert.assertNull;

/**
 * PlaywrightScreenshotManager 契约测试（无浏览器，仅验证降级）。
 *
 * <p>真实 PNG 编码需活动页面，由 web 套件的失败截图链路间接覆盖；
 * 此处固化"无页面时安全返回 null"，防止误改破坏契约。
 */
public class PlaywrightScreenshotManagerTest {

    @Test
    public void takeScreenshot_returnsNullWhenNoPage() {
        // 当前线程无绑定页面，Serenity PNG 链路应安全降级返回 null（不抛 NPE/不初始化浏览器）
        assertNull(PlaywrightScreenshotManager.takeScreenshot("no-page"));
    }
}

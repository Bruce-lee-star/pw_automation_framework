package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.panel;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 面板浏览器来源契约：复用框架主浏览器的 channel / 可执行路径；Firefox/WebKit 不支持 CDP，
 * 不再套用 Chromium 的启动配置（面板由 {@code CodegenPanelManager.open} 直接拒绝）。
 */
public class CodegenPanelConfigTest {

    private static final String TYPE = "playwright.browser.type";
    private static final String CHANNEL = "playwright.browser.channel";
    private static final String CHROME_EXE = "playwright.browser.chrome.executablePath";
    private static final String EDGE_EXE = "playwright.browser.edge.executablePath";

    private String prevType;
    private String prevChannel;
    private String prevChrome;
    private String prevEdge;

    @Before
    public void remember() {
        prevType = System.getProperty(TYPE);
        prevChannel = System.getProperty(CHANNEL);
        prevChrome = System.getProperty(CHROME_EXE);
        prevEdge = System.getProperty(EDGE_EXE);
    }

    @After
    public void restore() {
        restore(TYPE, prevType);
        restore(CHANNEL, prevChannel);
        restore(CHROME_EXE, prevChrome);
        restore(EDGE_EXE, prevEdge);
    }

    @Test
    public void chromiumUsesFrameworkChannelAndExecutablePath() {
        System.setProperty(TYPE, "chromium");
        System.setProperty(CHANNEL, "chrome");
        System.setProperty(CHROME_EXE, "C:/chrome.exe");

        CodegenPanelConfig cfg = CodegenPanelConfig.fromSystem();

        Assert.assertEquals("chromium", cfg.browserType());
        Assert.assertEquals("chrome", cfg.browserChannel());
        Assert.assertEquals("C:/chrome.exe", cfg.browserExecutablePath());
    }

    @Test
    public void edgeUsesEdgeExecutablePath() {
        System.setProperty(TYPE, "chromium");
        System.setProperty(CHANNEL, "msedge");
        System.setProperty(EDGE_EXE, "C:/msedge.exe");

        CodegenPanelConfig cfg = CodegenPanelConfig.fromSystem();

        Assert.assertEquals("msedge", cfg.browserChannel());
        Assert.assertEquals("C:/msedge.exe", cfg.browserExecutablePath());
    }

    @Test
    public void firefoxAndWebkitClearChromiumOptions() {
        System.setProperty(TYPE, "firefox");
        System.setProperty(CHANNEL, "chrome");
        System.setProperty(CHROME_EXE, "C:/chrome.exe");

        CodegenPanelConfig cfg = CodegenPanelConfig.fromSystem();

        Assert.assertEquals("firefox", cfg.browserType());
        Assert.assertNull(cfg.browserChannel());
        Assert.assertNull(cfg.browserExecutablePath());
    }

    @Test
    public void isChromiumFamilyRecognizesVariants() {
        Assert.assertTrue(CodegenPanelConfig.isChromiumFamily(null));
        Assert.assertTrue(CodegenPanelConfig.isChromiumFamily("chromium"));
        Assert.assertTrue(CodegenPanelConfig.isChromiumFamily("chrome"));
        Assert.assertTrue(CodegenPanelConfig.isChromiumFamily("msedge"));
        Assert.assertFalse(CodegenPanelConfig.isChromiumFamily("firefox"));
        Assert.assertFalse(CodegenPanelConfig.isChromiumFamily("webkit"));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}

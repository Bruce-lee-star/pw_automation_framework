package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link BrowserStartupImpl#mergeBrowserEntry} 契约：把 {@code name:version} 合并进既有浏览器 CSV，
 * 按名字（忽略大小写）覆盖旧版本、保持插入顺序、剔除空段与首尾空白。
 * 该 CSV 即报告首页 / 邮件 Browsers 区块的数据源（{@code pw.report.browsers}）。
 */
public class BrowserStartupImplReportTest {

    @Test
    public void emptyExistingYieldsSingleEntry() {
        assertEquals("Chromium:132.0.6834.83",
                BrowserStartupImpl.mergeBrowserEntry("", "Chromium", "132.0.6834.83"));
    }

    @Test
    public void nullExistingYieldsSingleEntry() {
        assertEquals("Chromium:132.0.6834.83",
                BrowserStartupImpl.mergeBrowserEntry(null, "Chromium", "132.0.6834.83"));
    }

    @Test
    public void distinctBrowsersAppendInInsertionOrder() {
        assertEquals("Firefox:120.0,Chromium:132.0",
                BrowserStartupImpl.mergeBrowserEntry("Firefox:120.0", "Chromium", "132.0"));
    }

    @Test
    public void sameNameOverridesVersionKeepingFirstPosition() {
        assertEquals("Chromium:132.0",
                BrowserStartupImpl.mergeBrowserEntry("Chromium:130.0", "Chromium", "132.0"));
    }

    @Test
    public void sameNameMatchIsCaseInsensitive() {
        assertEquals("Chromium:132.0",
                BrowserStartupImpl.mergeBrowserEntry("chromium:130.0", "Chromium", "132.0"));
    }

    @Test
    public void blankPartsAreTrimmedAndDropped() {
        assertEquals("Chromium:130.0,Firefox:99.0",
                BrowserStartupImpl.mergeBrowserEntry(" , Chromium:130.0 ,", "Firefox", "99.0"));
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 失败页源码采集：base href 注入（保障外链样式相对路径回源站、页面保真）。 */
public class FailurePageSourceHandlerTest {

    @Test
    public void injectsBaseBeforeHeadClose() {
        String html = "<!doctype html><html><head><title>T</title></head><body><div id='x'>hi</div></body></html>";
        String out = FailurePageSourceHandler.injectBase(html, "https://site.test/login?a=1");

        assertTrue(out.contains("<base href=\"https://site.test/login?a=1\">"));
        assertTrue(out.indexOf("<base") < out.indexOf("</head>"));
        assertTrue(out.contains("<!doctype html>"));
        assertTrue(out.contains("<div id='x'>hi</div>"));
        assertTrue(out.contains("</html>"));
    }

    @Test
    public void escapesUrlSpecialChars() {
        String html = "<html><head></head><body></body></html>";
        String out = FailurePageSourceHandler.injectBase(html, "https://site.test/p?a=1&b=\"2\"");
        assertTrue(out.contains("<base href=\"https://site.test/p?a=1&amp;b=&quot;2&quot;\">"));
    }

    @Test
    public void injectsBaseWhenNoHead() {
        String html = "<div>fragment</div>";
        String out = FailurePageSourceHandler.injectBase(html, "https://site.test/");
        assertTrue(out.contains("<base href=\"https://site.test/\">"));
        assertTrue(out.contains("<div>fragment</div>"));
    }

    @Test
    public void noBaseForBlankUrl() {
        String html = "<html><head></head></html>";
        assertEquals(html, FailurePageSourceHandler.injectBase(html, "  "));
        assertEquals(html, FailurePageSourceHandler.injectBase(html, null));
    }
}

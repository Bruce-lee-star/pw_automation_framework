package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * WEB-P1-5 种子测试：国际化语言状态（无浏览器，纯逻辑）。
 * 覆盖默认无语言、set/get 往返、reset 清状态、可见文本剥 HTML、模板编译占位符替换。
 */
public class NLSUtilsTest {

    @After
    public void tearDown() {
        NLSUtils.reset();
    }

    @Test
    public void setGetReset_roundTrip() {
        NLSUtils.reset();
        assertNull(NLSUtils.getLanguage());
        NLSUtils.setLanguage("zh");
        assertEquals("zh", NLSUtils.getLanguage());
        NLSUtils.reset();
        assertNull("reset 应清空语言状态", NLSUtils.getLanguage());
    }

    @Test
    public void visibleText_stripsHtmlTags() {
        String visible = NLSUtils.visibleText("<b>Hello</b> world");
        assertFalse("visibleText 应剥离 HTML 标签", visible.contains("<"));
        assertTrue(visible.contains("Hello"));
        assertTrue(visible.contains("world"));
    }

    @Test
    public void templateRegexSource_replacesPlaceholder() {
        String src = NLSUtils.templateRegexSource("Hi {{name}}");
        assertTrue("模板占位符应编译为正则捕获组", src.contains("(.*?)"));
        assertFalse("模板占位符不应残留 {{name}}", src.contains("{{name}}"));
    }

    /** codegen 的 visibleText 与 templateRegexSource 都应解码<b>全部</b> HTML 实体（统一到 NlsText）。 */
    @Test
    public void visibleText_decodesAllEntities() {
        assertEquals("Brand™", NLSUtils.visibleText("Brand&trade;"));
        assertEquals("Café", NLSUtils.visibleText("Caf&eacute;"));
        assertEquals("© 2026", NLSUtils.visibleText("&#169; 2026"));
    }

    @Test
    public void templateRegexSource_decodesEntitiesLikeVisibleText() {
        // codegen 模板定位路径与可见文本路径解码同一套实体，避免不一致
        String tpl = NLSUtils.templateRegexSource("Price &euro;{{amount}}");
        assertTrue("应解码 &euro; → €", tpl.contains("€"));
        assertTrue("占位符应编译为捕获组", tpl.contains("(.*?)"));
        assertFalse("原始实体名不应残留", tpl.contains("&euro;"));
    }

    /** {@code NlsBundle.visibleText(key)} 应等于手写 {@code NLSUtils.visibleText(bind(file).get(key))}，且剥离 HTML。 */
    @Test
    public void bundleVisibleText_stripsHtmlAndMatchesManualForm() throws Exception {
        Path dir = Files.createTempDirectory("nls-bundle");
        Path f = dir.resolve("b.nls.json");
        Files.writeString(f, "{\"en\":{\"greet\":\"Hello <b>World</b> &copy;\"}}");
        NLSUtils.setLanguage("en");

        String viaBundle = NLSUtils.bind(f.toString()).visibleText("greet");
        String manual = NLSUtils.visibleText(NLSUtils.bind(f.toString()).get("greet"));
        assertEquals("visibleText(key) 应等价于手写 visibleText(get(key))", manual, viaBundle);
        assertFalse("应剥离 HTML 标签", viaBundle.contains("<"));
        assertTrue("应解码实体 &copy; → ©", viaBundle.contains("©"));
        assertTrue(viaBundle.contains("Hello"));
        assertTrue(viaBundle.contains("World"));
    }
}

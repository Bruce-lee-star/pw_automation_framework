package com.hsbc.cmb.hk.dbb.automation.framework.agent.nls;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * NLS 目录与「按 key 定位」的核心语义（设计 §4.7）。
 *
 * <p>覆盖三件事：① key → 当前语言可见文本（含 HTML/实体归一化）；② 可见文本 → key 反查（含模板）；
 * ③ {@link LocatorSpec} 的 key 形态能被解析为当前语言可执行的定位符。全部零浏览器。
 */
public class NlsCatalogTest {

    private static final String FILE = "nls/agent.nls.json";

    @Test
    public void resolvesValueAndVisibleTextPerLanguage() {
        NlsCatalog zh = JsonNlsCatalog.ofLanguage("zh", FILE);
        assertEquals("我同意条款", zh.value("link.agree"));
        assertEquals("我同意条款", zh.visibleText("link.agree"));

        NlsCatalog en = JsonNlsCatalog.ofLanguage("en", FILE);
        assertEquals("I agree to the terms", en.visibleText("link.agree"));
    }

    @Test
    public void stripsHtmlTagsAndDecodesEntitiesForLocating() {
        // nls 值里常内嵌 <b>/&nbsp;，而页面可见文本已剥标签 → 定位必须用归一化结果
        NlsCatalog zh = JsonNlsCatalog.ofLanguage("zh", FILE);
        assertEquals("开户 申请", zh.visibleText("html.title"));
        assertTrue(zh.value("html.title").contains("<b>"));
    }

    @Test
    public void reverseLooksUpKeyFromLocalizedVisibleText() {
        NlsCatalog zh = JsonNlsCatalog.ofLanguage("zh", FILE);
        assertEquals("link.agree", zh.keyOf("我同意条款"));
        assertEquals("link.agree", zh.keyOf("  我同意条款  "));
        // 归一化后仍能命中（页面文本不带标签，而 nls 值带标签）
        assertEquals("html.title", zh.keyOf("开户 申请"));
        assertNull(zh.keyOf("no such label"));
    }

    @Test
    public void reverseMatchesTemplateValuesByPattern() {
        NlsCatalog zh = JsonNlsCatalog.ofLanguage("zh", FILE);
        // 模板值 {{user}} 运行时被页面替换成真实值，只能按正则反查
        assertEquals("welcome", zh.keyOf("欢迎 张三"));
        assertEquals("welcome", JsonNlsCatalog.ofLanguage("en", FILE).keyOf("Welcome Alice"));
    }

    @Test
    public void missingKeyFailsLoudly() {
        NlsCatalog zh = JsonNlsCatalog.ofLanguage("zh", FILE);
        try {
            zh.visibleText("login.not.exists");
            fail("expected IllegalStateException for missing key");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("missing key"));
        }
    }

    @Test
    public void unsetLanguageFailsWithActionableMessage() {
        // 不指定语言且线程未设置 → 必须显式报错，而不是静默退化成"查不到 key"
        com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState.reset();
        NlsCatalog catalog = JsonNlsCatalog.of(FILE);
        try {
            catalog.language();
            fail("expected IllegalStateException when language is not set");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("current language is not set"));
        }
    }

    @Test
    public void locatorSpecCarriesKeyAndLocalizes() {
        LocatorSpec keyed = LocatorSpec.roleOfKey("checkbox", "link.agree", 0);
        assertTrue(keyed.usesNlsKey());
        assertEquals("link.agree", keyed.nameKey());
        assertNull(keyed.name());
        assertEquals("role=checkbox;nameKey=link.agree", keyed.canonical());

        // canonical 可无损往返（资产落库/传输依赖它）
        LocatorSpec parsed = LocatorSpec.parse(keyed.canonical());
        assertEquals("link.agree", parsed.nameKey());

        // 解析为当前语言：交给执行层的就是普通 role+name 定位
        LocatorSpec localized = keyed.localize(JsonNlsCatalog.ofLanguage("zh", FILE));
        assertFalse(localized.usesNlsKey());
        assertEquals("我同意条款", localized.name());
        assertEquals("role=checkbox;name=我同意条款", localized.canonical());
    }

    @Test
    public void templateKeyIsNotStaticallyLocalized() {
        // 模板值无法静态求值（真实值由页面注入），localize 保留 key，由执行层按正则匹配
        LocatorSpec keyed = LocatorSpec.roleOfKey("heading", "welcome");
        assertTrue(keyed.localize(JsonNlsCatalog.ofLanguage("en", FILE)).usesNlsKey());
        assertTrue(NlsText.pattern("Welcome {{user}}").matcher("Welcome Alice").find());
    }
}

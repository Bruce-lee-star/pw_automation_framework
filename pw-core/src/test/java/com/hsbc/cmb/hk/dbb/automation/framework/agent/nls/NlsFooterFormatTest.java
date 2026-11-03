package com.hsbc.cmb.hk.dbb.automation.framework.agent.nls;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 真实业务 NLS 格式回归（对应 {@code NLS_footer.json} 这类文件）。
 *
 * <p>格式特征：① 顶层是<b>带地区的语言标签</b> {@code en-US / zh-CN / zh-HK}（直接当语言 key 用）；
 * ② key 是<b>英文短语</b>（如 {@code "Privacy and Security footer"}），而非点分 id；
 * ③ 同一语义有两层 key（{@code "X footer"} → 短标签，{@code "X"} → 带 "此连结..." 的长可访问名）；
 * ④ link key（{@code footer_*_link}）存的是 URL；⑤ 模板值含 {@code {{currentYear}}}。</p>
 *
 * <p>核心断言：无论顶层语言标签带不带地区、key 是不是英文短语，<b>反查出的 NLS key 三语言完全一致</b>
 * —— 这就是资产跨语言稳定、不随语言重产的根。</p>
 */
public class NlsFooterFormatTest {

    private static final String FILE = "nls/footer-format.nls.json";

    @Test
    public void topLevelRegionTagsAreUsedVerbatimAsLanguage() {
        // 顶层 key 是 "en-US"/"zh-CN"/"zh-HK" 这类带地区的标签，直接作为 language() 的标识
        assertEquals("en-US", JsonNlsCatalog.ofLanguage("en-US", FILE).language());
        assertEquals("zh-CN", JsonNlsCatalog.ofLanguage("zh-CN", FILE).language());
        assertEquals("zh-HK", JsonNlsCatalog.ofLanguage("zh-HK", FILE).language());

        JsonNlsCatalog en = JsonNlsCatalog.ofLanguage("en-US", FILE);
        assertTrue(en.hasKey("Privacy and Security footer"));
        assertTrue(en.hasKey("footer_privacy_and_security_link"));
    }

    @Test
    public void englishPhraseKeysReverseLookupCorrectly() {
        // 英文短语 key：en 看到短标签 "Privacy and Security" → 反查回 "Privacy and Security footer"
        assertEquals("Privacy and Security footer",
                JsonNlsCatalog.ofLanguage("en-US", FILE).keyOf("Privacy and Security"));
        // 长可访问名 → 反查回 "Privacy and Security"（两层 key 各自独立、确定可测）
        assertEquals("Privacy and Security",
                JsonNlsCatalog.ofLanguage("en-US", FILE)
                        .keyOf("Privacy and Security. This link will open in a new window."));
    }

    @Test
    public void sameKeyAcrossAllThreeLanguages() {
        // 三语言看到各自文案，但反查出的 key 必须一致 —— 资产跨语言稳定
        String en = JsonNlsCatalog.ofLanguage("en-US", FILE).keyOf("Privacy and Security");
        String zh = JsonNlsCatalog.ofLanguage("zh-CN", FILE).keyOf("私隐与保安");
        String hk = JsonNlsCatalog.ofLanguage("zh-HK", FILE).keyOf("私隱與保安");

        assertEquals("Privacy and Security footer", en);
        assertEquals(en, zh);
        assertEquals(en, hk);
    }

    @Test
    public void templateCopyrightReverseLooksUpAcrossLanguages() {
        // 模板 {{currentYear}}：页面显示真实年份，反查仍回 "Copyright footer"（trim 修复后稳定命中）
        String en = JsonNlsCatalog.ofLanguage("en-US", FILE)
                .keyOf("© Copyright. The Hongkong and Shanghai Banking Corporation Limited, 2002-2026. All rights reserved.");
        String zh = JsonNlsCatalog.ofLanguage("zh-CN", FILE)
                .keyOf("© 版权所有。 The Hongkong and Shanghai Banking Corporation Limited 香港上海汇丰银行有限公司，2002-2026。不得转载。");
        String hk = JsonNlsCatalog.ofLanguage("zh-HK", FILE)
                .keyOf("© 版權所有。 The Hongkong and Shanghai Banking Corporation Limited 香港上海滙豐銀行有限公司，2002-2026。不得轉載。");

        assertEquals("Copyright footer", en);
        assertEquals("Copyright footer", zh);
        assertEquals("Copyright footer", hk);
    }

    @Test
    public void resolvesKeyToVisibleTextPerLanguage() {
        JsonNlsCatalog zh = JsonNlsCatalog.ofLanguage("zh-CN", FILE);
        assertEquals("私隐与保安", zh.visibleText("Privacy and Security footer"));
        // 实体 &copy; 被解码为 ©
        assertTrue(zh.visibleText("Copyright footer").startsWith("©"));
    }

    @Test
    public void linkKeyHoldsUrl() {
        // link key 的值是 URL（href），不是可见文本，照常按 key 存取即可
        JsonNlsCatalog en = JsonNlsCatalog.ofLanguage("en-US", FILE);
        assertTrue(en.value("footer_privacy_and_security_link").startsWith("https://"));
        // 若某链接的可访问名恰好是裸 URL，反查也能回到该 link key（无害，精确命中）
        assertEquals("footer_privacy_and_security_link",
                en.keyOf("https://www.business.hsbc.com.hk/en-gb/hk/generic/privacy-and-security"));
    }
}

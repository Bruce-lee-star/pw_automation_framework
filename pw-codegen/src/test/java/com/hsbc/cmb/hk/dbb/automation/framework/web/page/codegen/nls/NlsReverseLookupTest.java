package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.nls;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NLS 反查必须按 {@link NLSUtils} 的「语言 → key → value」两级结构读取，且<b>先设置语言</b>。
 *
 * <p>这里同时固化一个历史 bug：早期实现把 nls json 当 {@code {key: value}} 单层解析，
 * 而文件首层实际是语言 ⇒ 取到的 value 是对象不是字符串 ⇒ 索引恒空、反查从未命中（静默失效）。
 */
public class NlsReverseLookupTest {

    private Path written;

    @After
    public void tearDown() {
        NLSUtils.reset();
        if (written != null) {
            try {
                Files.deleteIfExists(written);
            } catch (Exception ignore) {
                // 清理失败不影响断言
            }
        }
    }

    private Path writeBundle(String json) throws Exception {
        Path p = Files.createTempFile("codegen-nls-", ".json");
        Files.writeString(p, json, StandardCharsets.UTF_8);
        written = p;
        return p;
    }

    @Test
    public void reverseLookupUsesCurrentLanguageTable() throws Exception {
        Path f = writeBundle("{\"zh\":{\"login.username\":\"用户名\",\"login.submit\":\"登录\"},"
                + "\"en\":{\"login.username\":\"Username\",\"login.submit\":\"Sign in\"}}");

        NLSUtils.setLanguage("zh");
        NlsReverseLookup zh = new NlsReverseLookup(new String[]{f.toString()});
        Assert.assertEquals("login.username", zh.lookup("用户名"));
        Assert.assertEquals("login.submit", zh.lookup("登录"));
        Assert.assertNull("未命中应返回 null 以回落 name= 定位", zh.lookup("不存在的文案"));
    }

    @Test
    public void switchingLanguageReindexes() throws Exception {
        Path f = writeBundle("{\"zh\":{\"k.a\":\"中文\"},\"en\":{\"k.a\":\"English\"}}");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});

        NLSUtils.setLanguage("zh");
        Assert.assertEquals("k.a", lookup.lookup("中文"));
        Assert.assertNull("切语言前不应命中另一语言的值", lookup.lookup("English"));

        NLSUtils.setLanguage("en");
        Assert.assertEquals("k.a", lookup.lookup("English"));
    }

    @Test
    public void withoutLanguageLookupFailsLoudly() throws Exception {
        Path f = writeBundle("{\"zh\":{\"k.a\":\"中文\"}}");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});
        try {
            lookup.lookup("中文");
            Assert.fail("未设置语言时必须抛异常，而不是静默查不到");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("setLanguage"));
        }
    }

    @Test
    public void noNlsFilesReturnsNullWithoutTouchingLanguage() throws Exception {
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{});
        Assert.assertNull(lookup.lookup("任意"));
    }

    /**
     * 同一可见文案命中多个 key 时，必须优先采纳元素自带的 key（如 {@code data-i18n}），
     * 而不是文案反查的"文件顺序先到先得"。这里 {@code login.title} 排在 {@code signin.title} 之前，
     * 故纯文案反查得 {@code login.title}；传入 {@code signin.title} 必须覆盖它。
     */
    @Test
    public void explicitKeyWinsOverAmbiguousTextLookup() throws Exception {
        Path f = writeBundle("{\"en\":{\"login.title\":\"Log on\",\"signin.title\":\"Log on\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});

        Assert.assertEquals("login.title", lookup.lookup("Log on"));
        Assert.assertEquals("signin.title", lookup.lookup("Log on", "signin.title"));
    }

    /** 元素自带的 key 不是合法 key（属性缺失 / 它其实是 value）⇒ 回落到文案反查，不报错。 */
    @Test
    public void explicitKeyIgnoredWhenUnknownFallsBackToText() throws Exception {
        Path f = writeBundle("{\"en\":{\"login.title\":\"Log on\",\"signin.title\":\"Log on\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});

        Assert.assertEquals("login.title", lookup.lookup("Log on", "not.a.real.key"));
        Assert.assertEquals("login.title", lookup.lookup("Log on", null));
    }

    /** NLS 值含 HTML（可见文本是元素的超集）：元素可见名只是其中一段，精确查不到 ⇒ containsString 兜底反查到 key。 */
    @Test
    public void substringFallbackFindsKeyWhenValueWrapsHtml() throws Exception {
        Path f = writeBundle("{\"en\":{\"greet\":\"Hello <b>World</b>\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});

        Assert.assertEquals("greet", lookup.lookup("Hello World")); // 精确（去标签后）
        Assert.assertEquals("greet", lookup.lookup("World"));        // 元素名是 HTML 值的一段 ⇒ 兜底命中
    }

    /** 反向：元素可见名包含某 NLS 值（元素是值的超集）⇒ 同样兜底反查到 key。 */
    @Test
    public void substringFallbackWhenNameContainsValue() throws Exception {
        Path f = writeBundle("{\"en\":{\"welcome\":\"Welcome\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});
        Assert.assertEquals("welcome", lookup.lookup("Welcome back"));
    }

    /** 单字符噪声不参与 containsString 兜底，避免误命中。 */
    @Test
    public void singleCharNameDoesNotTriggerSubstringFallback() throws Exception {
        Path f = writeBundle("{\"en\":{\"a\":\"Alpha\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});
        Assert.assertNull(lookup.lookup("A"));
    }

    /** 回归：容器元素聚合出的整页文案（远超阈值、且内含某 NLS 值子串）不得误命中，否则生成 bank_logo 这类
     *  永远解析不到的 role 选择器。短名（恰好等于该值）仍可正常反查。 */
    @Test
    public void longAggregatedNameIsNotReverseLookedUp() throws Exception {
        Path f = writeBundle("{\"en\":{\"bank_logo\":\"Log on to HSBC Online Business Banking\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});
        String value = "Log on to HSBC Online Business Banking";
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() <= 120) sb.append(" padding padding");
        Assert.assertNull(lookup.lookup(sb.toString()));   // 护栏生效：含子串也不误命中
        Assert.assertEquals("bank_logo", lookup.lookup(value)); // 短名仍正常
    }

    /**
     * 后代节点上的 key（{@code <button><span data-i18n="continue">Continue</span></button>}）必须能消歧：
     * 真实 reset.json 里 8 个 key 的值都是 "Continue"，纯文案反查只能拿到文件里第一个
     * （{@code reset_password_5}），语义不对；传入后代 key 必须拿到 {@code continue}。
     */
    @Test
    public void descendantKeyDisambiguatesSameVisibleText() throws Exception {
        Path f = writeBundle("{\"en\":{\"reset_password_5\":\"Continue\","
                + "\"reset_password_11\":\"Continue\",\"continue\":\"Continue\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});

        Assert.assertEquals("纯文案反查只能先到先得", "reset_password_5", lookup.lookup("Continue"));
        Assert.assertEquals("continue", lookup.lookupByDescendantKey("Continue", "continue"));
        Assert.assertEquals("reset_password_11",
                lookup.lookupByDescendantKey("Continue", "reset_password_11"));
    }

    /** 加载中播报会把可访问名变成超集（{@code "Loading, please wait. Continue"}）⇒ 仍应采纳后代 key。 */
    @Test
    public void descendantKeyAcceptsNameSuperset() throws Exception {
        Path f = writeBundle("{\"en\":{\"reset_password_5\":\"Continue\",\"continue\":\"Continue\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});

        Assert.assertEquals("continue",
                lookup.lookupByDescendantKey("Loading, please wait. Continue", "continue"));
    }

    /**
     * 后代 key 的<b>值</b>与本元素可访问名对不上（装饰性子节点的 key）⇒ 不采纳，退回文案反查。
     * 宁可拿到语义粗糙的 key，也不能产出一个运行期解析不到文案的 key。
     */
    @Test
    public void descendantKeyRejectedWhenValueDoesNotMatchName() throws Exception {
        Path f = writeBundle("{\"en\":{\"icon.tooltip\":\"Info\",\"continue\":\"Continue\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});

        Assert.assertEquals("continue", lookup.lookupByDescendantKey("Continue", "icon.tooltip"));
    }

    /** 后代 key 缺失 / 为空 / 在当前语言包里不存在 ⇒ 与「没给」等价，回落文案反查（绝不返回坏 key）。 */
    @Test
    public void unknownOrBlankDescendantKeyFallsBackToText() throws Exception {
        Path f = writeBundle("{\"en\":{\"continue\":\"Continue\"}}");
        NLSUtils.setLanguage("en");
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{f.toString()});

        Assert.assertEquals("continue", lookup.lookupByDescendantKey("Continue", "no.such.key"));
        Assert.assertEquals("continue", lookup.lookupByDescendantKey("Continue", "  "));
        Assert.assertEquals("continue", lookup.lookupByDescendantKey("Continue", null));
    }

    /** 无 nls 文件（单语言环境）时 descendants 路径直接返回 null，不触碰语言状态。 */
    @Test
    public void noNlsFilesDescendantLookupReturnsNull() {
        NlsReverseLookup lookup = new NlsReverseLookup(new String[]{});
        Assert.assertNull(lookup.lookupByDescendantKey("Continue", "continue"));
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.nls;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link NlsText} 的「nls 值 → 页面可见文本」归一化（设计 §4.7）。
 *
 * <p>重点覆盖两类输入：① 直写的 HTML 标签（{@code <b>}）；② <b>转义过的</b>标签与实体
 * （{@code &lt;b&gt;} / {@code &nbsp;} / {@code &copy;}）。两者必须被一致处理成页面实际可见的纯文本，
 * 否则 {code getByRole(role,{name})} 定位与反查会失败。</p>
 */
public class NlsTextTest {

    @Test
    public void stripsLiteralHtmlTags() {
        // 直写的标签被剥掉，与浏览器渲染后的可访问名一致
        assertEquals("Account opening", NlsText.visibleText("<b>Account</b>&nbsp;opening"));
        assertEquals("开户 申请", NlsText.visibleText("<b>开户</b>&nbsp;申请"));
    }

    @Test
    public void decodesEscapedTagsThenStripsThem() {
        // 关键修复点：&lt;b&gt; 解码成 <b> 后必须继续被当作标签剥掉，不能残留在可见文本
        assertEquals("bold", NlsText.visibleText("&lt;b&gt;bold&lt;/b&gt;"));
        assertEquals("Account open", NlsText.visibleText("Account &lt;b&gt;open&lt;/b&gt;"));
        assertEquals("x y", NlsText.visibleText("x&lt;br/&gt;y"));
    }

    @Test
    public void preservesLoneAngleBracketsThatAreNotTags() {
        // 不成对（无闭合 >）的转义尖括号按字面字符保留，不误剥
        assertEquals("a < b", NlsText.visibleText("a &lt; b"));
        assertEquals("1 > 2", NlsText.visibleText("1 &gt; 2"));
    }

    @Test
    public void decodesCommonEntities() {
        assertEquals("A B", NlsText.visibleText("A&nbsp;B"));
        assertEquals("© 2026", NlsText.visibleText("&copy; 2026"));
        assertEquals("Tom & Jerry", NlsText.visibleText("Tom &amp; Jerry"));
        assertEquals("he said \"hi\"", NlsText.visibleText("he said &quot;hi&quot;"));
    }

    @Test
    public void templatePatternSurvivesEscapedTags() {
        // 模板值里的转义标签解码后被剥，正则仍能匹配页面（标签已被浏览器剥掉）的真实文本
        assertTrue(NlsText.pattern("Click &lt;b&gt;{{user}}&lt;/b&gt;").matcher("Click Alice").find());
        assertFalse(NlsText.pattern("Click &lt;b&gt;{{user}}&lt;/b&gt;").matcher("Miss").find());
    }

    @Test
    public void decodesNamedEntitiesBeyondBasics() {
        // 命名实体（不止 &lt; &gt;）：符号 / 排版 / 货币 / 重音 / 希腊 / 分数
        assertEquals("Registered®", NlsText.visibleText("Registered&reg;"));
        assertEquals("Brand™", NlsText.visibleText("Brand&trade;"));
        assertEquals("Café", NlsText.visibleText("Caf&eacute;"));
        assertEquals("naïve", NlsText.visibleText("na&iuml;ve"));
        assertEquals("1—10", NlsText.visibleText("1&mdash;10"));
        assertEquals("wait…", NlsText.visibleText("wait&hellip;"));
        assertEquals("37°C", NlsText.visibleText("37&deg;C"));
        assertEquals("2×3", NlsText.visibleText("2&times;3"));
        assertEquals("price €99", NlsText.visibleText("price &euro;99"));
        assertEquals("price £99", NlsText.visibleText("price &pound;99"));
        assertEquals("α β γ", NlsText.visibleText("&alpha; &beta; &gamma;"));
        assertEquals("¼ ½ ¾", NlsText.visibleText("&frac14; &frac12; &frac34;"));
    }

    @Test
    public void decodesNumericCharacterReferences() {
        // 数字实体（十进制 &#nnn; 与十六进制 &#xhh;）可表示任意码点，覆盖命名表之外的字符
        assertEquals("© 2026", NlsText.visibleText("&#169; 2026"));
        assertEquals("Café", NlsText.visibleText("Caf&#xe9;"));        // é = U+00E9
        assertEquals("price €99", NlsText.visibleText("price &#8364;99"));
        assertEquals("\uD83D\uDE00 smile", NlsText.visibleText("&#128512; smile")); // astral 代理对
    }

    @Test
    public void numericNbspNormalizedToSpace() {
        // &#160; 解码为 nbsp 字符后，应被归一为普通空格并折叠
        assertEquals("a b", NlsText.visibleText("a&#160;&#160;b"));
    }

    @Test
    public void decodeEntitiesPublicApi() {
        // 公共解码入口：命名 + 数字混合；不误伤无分号的 & 与未知实体
        assertEquals("a < b & c &unknown; d",
                NlsText.decodeEntities("a &lt; b &amp; c &unknown; d"));
        assertEquals("©é€", NlsText.decodeEntities("&copy;&#xe9;&#8364;"));
    }

    @Test
    public void doesNotOverDecodeDoubleAmp() {
        // 单趟解码，与浏览器一致：&amp;amp; → &amp;（字面），不被过度解成 &
        assertEquals("&amp;", NlsText.decodeEntities("&amp;amp;"));
    }
}

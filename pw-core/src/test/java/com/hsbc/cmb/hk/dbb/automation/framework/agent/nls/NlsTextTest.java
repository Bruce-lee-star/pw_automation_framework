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
}

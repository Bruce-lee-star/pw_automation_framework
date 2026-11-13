package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SelectorStability} 契约：识别框架/随机生成的一次性属性值，并给出可用于部分匹配的稳定片段。
 */
public class SelectorStabilityTest {

    @Test
    public void frameworkGeneratedValuesAreVolatile() {
        assertTrue("UUID", SelectorStability.isVolatile("8f14e45f-ea1b-4c3d-9f2e-1a2b3c4d5e6f"));
        assertTrue("React useId", SelectorStability.isVolatile(":r1:"));
        assertTrue("radix useId 前缀", SelectorStability.isVolatile("radix-:r7:"));
        assertTrue("Angular Material 序号", SelectorStability.isVolatile("mat-input-3"));
        assertTrue("Element Plus 序号 + 时间戳", SelectorStability.isVolatile("el-id-1729930000123-0"));
        assertTrue("emotion hash", SelectorStability.isVolatile("css-1a2b3c"));
        assertTrue("十六进制 hash", SelectorStability.isVolatile("a3f9c1d2"));
        assertTrue("纯数字序号", SelectorStability.isVolatile("1234567890"));
        assertTrue("时间戳后缀", SelectorStability.isVolatile("btn-1729930000123"));
        assertTrue("ember 生成的 id", SelectorStability.isVolatile("ember1234"));
        assertTrue("空值不可用", SelectorStability.isVolatile("   "));
        assertTrue("null 不可用", SelectorStability.isVolatile(null));
    }

    @Test
    public void semanticValuesStayStable() {
        assertTrue(SelectorStability.isStable("userName"));
        assertTrue("含 id 的语义容器", SelectorStability.isStable("newPasswordForm"));
        assertTrue(SelectorStability.isStable("otp_radio_label"));
        assertTrue(SelectorStability.isStable("profile-list"));
        assertTrue(SelectorStability.isStable("login-button"));
        assertTrue("用户名也常出现在 id 里，不能误杀", SelectorStability.isStable("WP7UAT2_2"));
        assertTrue(SelectorStability.isStable("main-content"));
    }

    @Test
    public void stablePrefixKeepsLeadingSemanticSegments() {
        assertEquals("mat-input", SelectorStability.stablePrefix("mat-input-3"));
        assertEquals("login-form", SelectorStability.stablePrefix("login-form-a3f9c1d2"));
        assertEquals("首段随机 ⇒ 没有稳定前缀", "", SelectorStability.stablePrefix("a3f9c1d2-userName"));
    }

    @Test
    public void stableSuffixKeepsTrailingSemanticSegments() {
        assertEquals("userName-input", SelectorStability.stableSuffix("a3f9c1d2-userName-input"));
        assertEquals("尾段随机 ⇒ 没有稳定后缀", "", SelectorStability.stableSuffix("mat-input-3"));
        assertEquals("submit-btn", SelectorStability.stableSuffix("a3f9c1d2-submit-btn"));
    }

    @Test
    public void stableClassNamesSplitByWhitespaceAndDropHashClasses() {
        assertEquals(List.of("login-form", "primary"),
                SelectorStability.stableClassNames("login-form primary css-1a2b3c"));
    }

    @Test
    public void stableTokensDropVolatileFragments() {
        assertEquals(List.of("userName", "input"),
                SelectorStability.stableTokens("a3f9c1d2-userName-input"));
        assertEquals("过短片段不可用", List.of(), SelectorStability.stableTokens("a1b2-c3"));
        assertFalse(SelectorStability.stableTokens("mat-input-3").contains("3"));
    }

    /**
     * 回归 P0-3：hex hash 的字母段可能是<b>纯字母</b>，不得再要求「同时含数字与字母」。
     *
     * <p>{@code deadbeef}/{@code feedface}/{@code cafebabe} 是最典型的 hash 形态，
     * 漏判会让它们被当成稳定 id 生成 {@code #deadbeef}——本次能跑、下次构建即失效。
     */
    @Test
    public void pureLetterHexHashesAreVolatile() {
        assertTrue("纯字母 hex hash", SelectorStability.isVolatile("deadbeef"));
        assertTrue("纯字母 hex hash", SelectorStability.isVolatile("feedface"));
        assertTrue("纯字母 hex hash", SelectorStability.isVolatile("cafebabe"));
        assertTrue("大小写不敏感", SelectorStability.isVolatile("DEADBEEF"));
        assertFalse("随机段不得进 *= 候选",
                SelectorStability.stableTokens("deadbeef-userName").contains("deadbeef"));
        assertEquals("首段随机 ⇒ 没有稳定前缀", "", SelectorStability.stablePrefix("deadbeef-userName"));
    }

    /** 段级判定要比整串保守：4~7 位的纯字母段多是语义词（card-face 的 face），不得误杀。 */
    @Test
    public void shortPureLetterSegmentsStayStable() {
        assertTrue(SelectorStability.stableTokens("card-face").contains("face"));
        assertTrue(SelectorStability.stableTokens("login-cafe").contains("cafe"));
        assertTrue("4~7 位字母数字混排仍是 hash 片段",
                !SelectorStability.stableTokens("a1b2-userName").contains("a1b2"));
        assertTrue("4~7 位整值短 hex 也必须是 hash（评审实测 a1b2 漏放）",
                SelectorStability.isVolatile("a1b2"));
        assertTrue(SelectorStability.isVolatile("9f3c"));
    }

    /**
     * leet 变体 hex（o→0）：{@code c0ffee} 必须判随机，但同字符集的真语义词 {@code coffee} 不得误杀。
     * 分界判据是「至少含一位数字」——手写 hash 的刻意数字替换特征。
     */
    @Test
    public void leetHexHashesAreVolatileButPlainWordsStayStable() {
        assertTrue("leet hex hash", SelectorStability.isVolatile("c0ffee"));
        assertTrue("leet hex hash", SelectorStability.isVolatile("d00d"));
        assertTrue("leet hex hash 段", SelectorStability.isVolatile("btn-c0ffee"));
        assertTrue("纯字母同形词不得误杀", SelectorStability.isStable("coffee"));
        assertTrue("纯字母同形词不得误杀", SelectorStability.isStable("food"));
        assertFalse("leet 段不得进 *= 候选",
                SelectorStability.stableTokens("c0ffee-userName").contains("c0ffee"));
    }

    /** 「语义前缀 + hash 段」是打包器最常见的命名形态，整串不匹配任何整值模式也必须判随机。 */
    @Test
    public void composedValuesWithHashSegmentsAreVolatile() {
        assertTrue(SelectorStability.isVolatile("userName-deadbeef"));
        assertTrue(SelectorStability.isVolatile("btn-cafebabe"));
        assertTrue(SelectorStability.isVolatile("panel-c0ffee"));
        assertEquals("随机尾段前的语义前缀可用于 ^=", "userName",
                SelectorStability.stablePrefix("userName-deadbeef"));
        assertTrue("序号碎片不得把合法语义 id 误杀（回归）",
                SelectorStability.isStable("WP7UAT2_2"));
    }

    /**
     * 稳定前缀的契约：连续稳定片段覆盖整值时返回空串，而不是把整值吐给调用方再靠护栏丢弃。
     * {@code [attr^="whole"]} 与 {@code [attr="whole"]} 等价，后者由 CodegenScorer 的稳定分支直接生成，
     * 更强也更可读；前缀匹配是给「值含随机尾段」准备的。
     */
    @Test
    public void stablePrefixIsEmptyWhenWholeValueIsStable() {
        assertEquals("", SelectorStability.stablePrefix("userName-input"));
        assertEquals("", SelectorStability.stablePrefix("login-form"));
        assertEquals("只有带随机尾段才有前缀", "mat-input",
                SelectorStability.stablePrefix("mat-input-3"));
        assertEquals("首段随机 ⇒ 无前缀", "", SelectorStability.stablePrefix("a3f9c1d2-userName"));
    }
}

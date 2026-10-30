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
}

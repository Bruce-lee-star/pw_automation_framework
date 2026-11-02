package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link AriaRoles} 契约：只放行能映射到确切 {@code AriaRole} 的角色，未知角色一律"不能定位"。
 *
 * <p>背景（线上问题）：页面侧把 {@code <label>} 推断成 role {@code label}，生成器又把未知角色静默降级成
 * {@code AriaRole.GENERIC}，于是产出 {@code @RoleElement(role = AriaRole.GENERIC, key = "password")}——
 * 能编译，但运行期 {@code getByRole(GENERIC, name)} 定位不到元素。现在这类角色必须回退 {@code @Element(css)}。
 */
public class AriaRolesTest {

    @Test
    public void axRolesMapToPlaywrightConstants() {
        assertEquals("TEXTBOX", AriaRoles.ariaConstant("textbox"));
        assertEquals("RADIO", AriaRoles.ariaConstant("Radio"));
        assertEquals("CHECKBOX", AriaRoles.ariaConstant("checkbox"));
        assertEquals("BUTTON", AriaRoles.ariaConstant("button"));
        assertEquals("LINK", AriaRoles.ariaConstant("link"));
        assertEquals("MENUITEMCHECKBOX", AriaRoles.ariaConstant("menuitem-checkbox"));
    }

    @Test
    public void chromeImageRoleAliasesToImg() {
        assertEquals("AX 的 image 与 Playwright 常量 IMG 是同一角色", "IMG", AriaRoles.ariaConstant("image"));
        assertTrue(AriaRoles.isLocatable("image"));
    }

    @Test
    public void labelRoleHasNoConstantSoItCannotBeLocatable() {
        assertNull("Playwright AriaRole 没有 LABEL —— 必须回退 CSS，不能降级成 GENERIC",
                AriaRoles.ariaConstant("label"));
        assertFalse(AriaRoles.isLocatable("label"));
    }

    @Test
    public void semanticallyEmptyRolesAreNotLocatable() {
        assertEquals("GENERIC", AriaRoles.ariaConstant("generic"));
        assertFalse("generic 定位必然不精确：div/span/label 全落在这里",
                AriaRoles.isLocatable("generic"));
        assertFalse(AriaRoles.isLocatable("none"));
        assertFalse(AriaRoles.isLocatable("presentation"));

        // AX 的纯文本节点：语义上等价 generic，同样不可定位
        assertEquals("GENERIC", AriaRoles.ariaConstant("StaticText"));
        assertFalse(AriaRoles.isLocatable("StaticText"));
        assertFalse(AriaRoles.isLocatable("InlineTextBox"));
        assertFalse(AriaRoles.isLocatable("LabelText"));
    }

    @Test
    public void blankOrUnknownRoleIsNotLocatable() {
        assertNull(AriaRoles.ariaConstant(""));
        assertNull(AriaRoles.ariaConstant("   "));
        assertNull(AriaRoles.ariaConstant(null));
        assertNull(AriaRoles.ariaConstant("not-a-real-role"));
        assertFalse(AriaRoles.isLocatable(null));
    }
}

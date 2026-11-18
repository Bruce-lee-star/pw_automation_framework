package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** PressKey 枚举：大小写不敏感解析 + Playwright 原始键名固定。 */
public class PressKeyTest {

    @Test
    public void parseIsCaseInsensitiveForEnumNames() {
        assertEquals(PressKey.ENTER, PressKey.parse("Enter"));
        assertEquals(PressKey.ENTER, PressKey.parse("enter"));
        assertEquals(PressKey.ENTER, PressKey.parse("ENTER"));
        assertEquals(PressKey.ARROW_DOWN, PressKey.parse("arrowdown"));
        assertEquals(PressKey.ARROW_DOWN, PressKey.parse("Arrow_Down"));
    }

    @Test
    public void parseMatchesPlaywrightKeyNames() {
        assertEquals(PressKey.ENTER, PressKey.parse("Enter"));
        assertEquals(PressKey.DIGIT_1, PressKey.parse("1"));
        assertEquals(PressKey.SLASH, PressKey.parse("/"));
        assertEquals(PressKey.F5, PressKey.parse("F5"));
        assertEquals(PressKey.TAB, PressKey.parse("Tab"));
    }

    @Test
    public void parseRejectsUnknownAndBlank() {
        assertNull(PressKey.parse(null));
        assertNull(PressKey.parse(""));
        assertNull(PressKey.parse("  "));
        assertNull(PressKey.parse("NotAKey"));
        assertNull(PressKey.parse("Enter\"; dropTable"));
    }

    @Test
    public void keyNameIsTheExactPlaywrightValue() {
        assertEquals("Enter", PressKey.ENTER.keyName());
        assertEquals("Control", PressKey.CONTROL.keyName());
        assertEquals("ArrowDown", PressKey.ARROW_DOWN.keyName());
        assertEquals("1", PressKey.DIGIT_1.keyName());
        assertEquals("/", PressKey.SLASH.keyName());
    }
}

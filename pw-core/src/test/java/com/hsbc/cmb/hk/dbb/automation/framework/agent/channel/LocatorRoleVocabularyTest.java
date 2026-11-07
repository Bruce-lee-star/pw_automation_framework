package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** {@link LocatorRoleVocabulary} 的纯字符串规范单测（pw-core 零 Playwright 依赖）。 */
public class LocatorRoleVocabularyTest {

    @Test
    public void axRolesMapToPlaywrightConstants() {
        assertEquals("TEXTBOX", LocatorRoleVocabulary.ariaConstant("textbox"));
        assertEquals("RADIO", LocatorRoleVocabulary.ariaConstant("Radio"));
        assertEquals("BUTTON", LocatorRoleVocabulary.ariaConstant("button"));
        assertEquals("MENUITEMCHECKBOX", LocatorRoleVocabulary.ariaConstant("menuitem-checkbox"));
        assertEquals("MENUITEMRADIO", LocatorRoleVocabulary.ariaConstant("menuitemradio"));
    }

    @Test
    public void aliasesAreResolvedToTheSameConstant() {
        assertEquals("IMG", LocatorRoleVocabulary.ariaConstant("image"));
        assertEquals("IMG", LocatorRoleVocabulary.ariaConstant("IMG"));
        assertTrue(LocatorRoleVocabulary.isLocatable("image"));
        assertEquals("GENERIC", LocatorRoleVocabulary.ariaConstant("StaticText"));
        assertFalse(LocatorRoleVocabulary.isLocatable("StaticText"));
        assertEquals("GENERIC", LocatorRoleVocabulary.ariaConstant("generic-container"));
    }

    @Test
    public void blankOrUnknownRoleFailsClosed() {
        assertNull(LocatorRoleVocabulary.ariaConstant(null));
        assertNull(LocatorRoleVocabulary.ariaConstant(""));
        assertNull(LocatorRoleVocabulary.ariaConstant("   "));
        assertNull(LocatorRoleVocabulary.ariaConstant("not-a-real-role"));
        assertFalse(LocatorRoleVocabulary.isLocatable(null));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LocatorRoleVocabulary.requireAriaConstant("label"));
        assertTrue("异常须带可定位的失败原因: " + e.getMessage(),
                e.getMessage().contains("Unsupported ARIA role"));
        assertTrue("异常须带冻结版本，便于判断是否升级漂移: " + e.getMessage(),
                e.getMessage().contains(LocatorRoleVocabulary.BASELINE_PLAYWRIGHT_VERSION));
    }

    @Test
    public void semanticallyEmptyRolesAreMappedButNotLocatable() {
        assertEquals("GENERIC", LocatorRoleVocabulary.ariaConstant("generic"));
        assertFalse(LocatorRoleVocabulary.isLocatable("generic"));
        assertFalse(LocatorRoleVocabulary.isLocatable("none"));
        assertFalse(LocatorRoleVocabulary.isLocatable("presentation"));
        assertFalse(LocatorRoleVocabulary.isLocatable("InlineTextBox"));
        assertFalse(LocatorRoleVocabulary.isLocatable("ListMarker"));
    }

    @Test
    public void frozenVocabularyIsImmutableCompleteAndOrdered() {
        assertEquals(82, LocatorRoleVocabulary.constants().size());
        assertThrows(UnsupportedOperationException.class,
                () -> LocatorRoleVocabulary.constants().add("MARK"));
        assertTrue(LocatorRoleVocabulary.isConstant("BUTTON"));
        assertFalse(LocatorRoleVocabulary.isConstant("button"));
        assertFalse(LocatorRoleVocabulary.isConstant(null));
        assertFalse("1.62.0 Java 客户端尚无 MARK（引擎 1.62.1 才有）",
                LocatorRoleVocabulary.isConstant("MARK"));

        for (String name : LocatorRoleVocabulary.constants()) {
            assertTrue("常量名必须是大写: " + name, name.matches("[A-Z]+"));
        }
    }

    @Test
    public void everyConstantRoundTripsThroughCanonical() {
        for (String constant : LocatorRoleVocabulary.constants()) {
            String canonicalRole = constant.toLowerCase(Locale.ROOT);
            LocatorSpec spec = LocatorSpec.role(canonicalRole, "X");
            assertEquals("role=" + canonicalRole + ";name=X", spec.canonical());

            LocatorSpec parsed = LocatorSpec.parse(spec.canonical());
            assertEquals("canonical 必须无损往返: " + constant, spec.canonical(), parsed.canonical());
            assertEquals(constant, LocatorRoleVocabulary.requireAriaConstant(parsed.role()));
            assertEquals(canonicalRole, LocatorRoleVocabulary.canonicalRole(parsed.role()));
        }
    }

    @Test
    public void canonicalRoleNormalizesLegacyAxWords() {
        assertEquals("img", LocatorRoleVocabulary.canonicalRole("image"));
        assertEquals("menuitemcheckbox", LocatorRoleVocabulary.canonicalRole("menuitem-checkbox"));
        assertEquals("button", LocatorRoleVocabulary.canonicalRole("BUTTON"));
        assertNull(LocatorRoleVocabulary.canonicalRole("label"));
        assertNotNull(LocatorRoleVocabulary.canonicalRole("textbox"));
    }

    @Test
    public void normalizeIsIdempotent() {
        for (String raw : new String[]{"button", "Menuitem-checkbox", "image", "StaticText", "unknown"}) {
            String once = LocatorRoleVocabulary.normalize(raw);
            assertEquals("规范化必须幂等: " + raw, once, LocatorRoleVocabulary.normalize(once));
        }
    }
}

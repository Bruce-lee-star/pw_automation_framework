package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SelectorAnalyzer} 契约测试：稳定锚点抽取、随机值识别、稳定化建议、锚点交集判定。
 */
public class SelectorAnalyzerTest {

    @Test
    public void stableCssKeepsIdAndAttributesButNotBareTag() {
        SelectorFacts facts = SelectorAnalyzer.analyze("button#submit[data-i18n='button_next']");

        assertEquals(SelectorFacts.Kind.CSS, facts.kind());
        assertEquals(SelectorFacts.Stability.STABLE, facts.stability());
        assertFalse("裸 tag 不足以判定同一元素", facts.anchors().contains(SelectorAnchor.id("button")));
        assertTrue(facts.anchors().toString(), facts.anchors().contains(SelectorAnchor.id("submit")));
        assertTrue(facts.anchors().toString(),
                facts.anchors().contains(SelectorAnchor.attr("data-i18n", SelectorAnchor.OP_EQ, "button_next")));
        assertEquals("", facts.advice());
    }

    @Test
    public void generatedIdIsVolatileAndGetsPrefixAdvice() {
        SelectorFacts facts = SelectorAnalyzer.analyze("#ember1234-input");

        assertEquals(SelectorFacts.Stability.ANCHORLESS, facts.stability());
        assertTrue(facts.warnings().toString(), facts.warnings().toString().contains("ember1234"));
        assertTrue(facts.advice(), facts.advice().contains("[id$='input']"));
    }

    @Test
    public void uuidAndTimestampAreVolatile() {
        assertTrue(SelectorAnalyzer.isVolatileValue("3f2504e0-4f89-11d3-9a0c-0305e82c3301"));
        assertTrue(SelectorAnalyzer.isVolatileValue("1712345678901"));
        assertTrue(SelectorAnalyzer.isVolatileValue("j_idt23"));
        assertTrue(SelectorAnalyzer.isVolatileValue("sc-bdVaJa"));
        assertTrue(SelectorAnalyzer.isVolatileValue("9f8c7b6a5d4e3c2b1a"));
    }

    @Test
    public void stableBusinessValuesAreNotVolatile() {
        assertFalse(SelectorAnalyzer.isVolatileValue("button_next"));
        assertFalse(SelectorAnalyzer.isVolatileValue("userName"));
        assertFalse(SelectorAnalyzer.isVolatileValue("profileswitcher"));
        assertFalse(SelectorAnalyzer.isVolatileValue("topnav.profileswitcher.dropdown"));
    }

    @Test
    public void volatileValueWithStableNeighbourKeepsOtherAnchor() {
        SelectorFacts facts = SelectorAnalyzer.analyze("[data-i18n='button_next']#a1b2c3d4");

        assertEquals(SelectorFacts.Stability.VOLATILE, facts.stability());
        assertEquals(1, facts.anchors().size());
        assertEquals("button_next", facts.anchors().get(0).value());
    }

    @Test
    public void volatilePrefixSuggestionsUseStartsWith() {
        SelectorFacts facts = SelectorAnalyzer.analyze("#profile-1234567890");

        assertTrue(facts.advice(), facts.advice().contains("[id^='profile']"));
    }

    @Test
    public void xpathContainsAndStartsWithAreNormalized() {
        SelectorFacts facts = SelectorAnalyzer.analyze(
                "//div[@class='row']//button[contains(@id,'submit')]");

        assertEquals(SelectorFacts.Kind.XPATH, facts.kind());
        assertTrue(facts.anchors().toString(), facts.anchors().contains(
                SelectorAnchor.attr("id", SelectorAnchor.OP_CONTAINS, "submit")));

        SelectorFacts prefix = SelectorAnalyzer.analyze("//input[starts-with(@id,'user')]");
        assertTrue(prefix.anchors().toString(), prefix.anchors().contains(
                SelectorAnchor.attr("id", SelectorAnchor.OP_PREFIX, "user")));
    }

    @Test
    public void selectorOnlyWithVolatileValuesSuggestsParentScope() {
        SelectorFacts facts = SelectorAnalyzer.analyze("div#app > span.j_idt23");

        assertEquals(SelectorFacts.Stability.ANCHORLESS, facts.stability());
        assertTrue(facts.advice(), facts.advice().contains("父级"));
        assertTrue(facts.advice(), facts.advice().contains("div#app"));
    }

    @Test
    public void intersectsMatchesSameAnchorAcrossOperators() {
        SelectorFacts declaration = SelectorAnalyzer.analyze("[data-i18n='button_next']");
        SelectorFacts prefixSignal = SelectorAnalyzer.analyze("[data-i18n^='button_']");

        assertTrue(SelectorAnalyzer.intersects(prefixSignal, declaration));
        assertFalse(SelectorAnalyzer.intersects(
                SelectorAnalyzer.analyze("[data-i18n='button_logon']"), declaration));
    }

    @Test
    public void blankAndOversizedSelectorsAreRejected() {
        assertRejected("   ");
        assertRejected("a".repeat(SelectorAnalyzer.MAX_LENGTH + 1));
    }

    private static void assertRejected(String selector) {
        try {
            SelectorAnalyzer.analyze(selector);
            fail("应拒绝非法选择器");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("selector"));
        }
    }

    @Test
    public void commaGroupedCssKeepsAllAlternativesThroughFirstSegment() {
        SelectorFacts facts = SelectorAnalyzer.analyze("[id='topnav.x'],.current-profile");

        assertEquals(SelectorFacts.Stability.STABLE, facts.stability());
        assertEquals(List.of(SelectorAnchor.attr("id", SelectorAnchor.OP_EQ, "topnav.x")), facts.anchors());
    }
}

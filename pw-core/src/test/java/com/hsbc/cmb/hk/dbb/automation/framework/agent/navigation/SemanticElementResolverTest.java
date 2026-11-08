package com.hsbc.cmb.hk.dbb.automation.framework.agent.navigation;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SemanticElementResolverTest {

    private final SemanticElementResolver resolver = new SemanticElementResolver();

    @Test
    public void exactAccessibleNameResolvesToRoleLocator() {
        PageObservation observation = obs(el("link", "Payments", null, true, null));

        SemanticMatch match = resolver.resolve(observation, "Payments");

        assertEquals("link", match.element().role());
        assertEquals("Payments", match.label());
        assertTrue(match.score() >= 100);
        assertEquals("role=link;name=Payments", match.locator().canonical());
        assertEquals(1, match.candidates().size());
    }

    @Test
    public void nlsKeyIsPreferredOverLiteralText() {
        ObservedElement keyed = el("link", "付款", null, true, "menu.payments");
        ObservedElement literal = el("heading", "menu.payments", null, true, null);

        SemanticMatch match = resolver.resolve(obs(literal, keyed), "menu.payments");

        assertEquals("menu.payments", match.element().nameKey());
        assertEquals(130 + 6, match.score());
        assertTrue(match.locator().usesNlsKey());
    }

    @Test
    public void preferredRoleBreaksTieBetweenLinkAndHeading() {
        PageObservation observation = obs(
                el("heading", "Payments", null, true, null),
                el("link", "Payments", null, true, null));

        assertThrows(SemanticElementException.class,
                () -> resolver.resolve(observation, "Payments"));

        SemanticMatch match = resolver.resolve(observation, "Payments",
                SemanticElementResolver.PreferredRoles.CLICK);
        assertEquals("link", match.element().role());
    }

    @Test
    public void ambiguousDescriptionsFailClosedAndCarryCandidates() {
        PageObservation observation = obs(
                el("button", "Submit", null, true, null),
                el("button", "Submit", null, true, null));

        SemanticElementException ex = assertThrows(SemanticElementException.class,
                () -> resolver.resolve(observation, "Submit"));

        assertEquals(SemanticElementException.Reason.AMBIGUOUS, ex.reason());
        assertEquals(2, ex.candidates().size());
        assertTrue(ex.getMessage().contains("Submit"));
    }

    @Test
    public void unknownDescriptionFailsWithEmptyCandidateList() {
        SemanticElementException ex = assertThrows(SemanticElementException.class,
                () -> resolver.resolve(obs(el("button", "Save", null, true, null)), "Payments"));

        assertEquals(SemanticElementException.Reason.NOT_FOUND, ex.reason());
        assertTrue(ex.candidates().isEmpty());
    }

    @Test
    public void hiddenAndDisabledElementsAreNeverSelected() {
        PageObservation observation = obs(
                el("link", "Payments", null, false, null),
                el("button", "Payments", null, false, null));

        assertThrows(SemanticElementException.class,
                () -> resolver.resolve(observation, "Payments"));
    }

    @Test
    public void normalizationHandlesCaseAndFullWidthWhitespace() {
        PageObservation observation = obs(el("link", "Payments　Transfer", null, true, null));

        SemanticMatch match = resolver.resolve(observation, "payments transfer");

        assertEquals(100 + 6, match.score());
    }

    @Test
    public void partialAndTokenMatchesAreRankedForArrivalChecks() {
        PageObservation observation = obs(
                el("button", "Make a Payment", null, true, null),
                el("link", "Unrelated", null, true, null));

        List<SemanticMatch> ranked = resolver.rank(observation, "Payment",
                SemanticElementResolver.PreferredRoles.ANY);

        assertEquals(1, ranked.size());
        assertTrue(ranked.get(0).score() >= SemanticElementResolver.MIN_ACCEPTED_SCORE);
        assertNotNull(ranked.get(0).describe());
    }

    @Test
    public void blankDescriptionAndNullObservationAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(obs(), "   "));
        assertThrows(NullPointerException.class,
                () -> resolver.resolve(null, "Payments"));
    }

    @Test
    public void roleHelperReturnsImmutableSet() {
        assertThrows(UnsupportedOperationException.class,
                () -> SemanticElementResolver.roles("link").add("button"));
    }

    private static PageObservation obs(ObservedElement... elements) {
        return new PageObservation("https://example.test/home", "home", Arrays.asList(elements), null);
    }

    /** 单语言/带 NLS key 的观察元素；visible / enabled 由参数控制。 */
    private static ObservedElement el(String role, String name, String text,
                                      boolean visible, String i18nKey) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (i18nKey != null) {
            attributes.put("data-i18n", i18nKey);
        }
        LocatorSpec primary = i18nKey == null
                ? LocatorSpec.role(role, name)
                : LocatorSpec.roleOfKey(role, i18nKey);
        return new ObservedElement(role, name, text, true, visible,
                List.of(primary), attributes, i18nKey);
    }
}

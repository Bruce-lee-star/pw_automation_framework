package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.navigation.SemanticElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.navigation.SemanticElementResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ObservedElement;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservationProvider;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PlaywrightStepPlanActionSinkTest {

    private Page page;
    private Locator locator;
    private AtomicInteger observations;

    @Before
    public void setUp() {
        page = mock(Page.class);
        locator = mock(Locator.class);
        observations = new AtomicInteger();
        when(page.getByRole(any(AriaRole.class), any(Page.GetByRoleOptions.class))).thenReturn(locator);
    }

    @Test
    public void canonicalTargetBypassesObservationForBackwardCompatibility() {
        PlaywrightStepPlanActionSink sink = sink(obs(el("link", "Payments")),
                PlaywrightStepPlanActionSink.denyAll());

        sink.clickOn("role=button;name=Submit");

        assertEquals(0, observations.get());
        verify(page).getByRole(eq(AriaRole.BUTTON), any(Page.GetByRoleOptions.class));
        verify(locator).click();
    }

    @Test
    public void businessDescriptionIsObservedAndResolvedToRoleLocator() {
        PlaywrightStepPlanActionSink sink = sink(obs(el("link", "Payments")),
                PlaywrightStepPlanActionSink.denyAll());

        sink.clickOn("Payments");

        assertEquals(1, observations.get());
        verify(page).getByRole(eq(AriaRole.LINK), any(Page.GetByRoleOptions.class));
        verify(locator).click();
    }

    @Test
    public void everyMenuLevelReobservesTheCurrentPage() {
        PlaywrightStepPlanActionSink sink = sink(obs(el("link", "Payments"), el("link", "Transfers")),
                PlaywrightStepPlanActionSink.denyAll());

        sink.navigateByMenu(Arrays.asList("Payments", "Transfers"));

        assertEquals(2, observations.get());
        verify(locator, times(2)).click();
    }

    @Test
    public void menuDepthIsBounded() {
        PlaywrightStepPlanActionSink sink = sink(obs(el("link", "Payments")),
                PlaywrightStepPlanActionSink.denyAll());
        List<String> tooDeep = Collections.nCopies(
                PlaywrightStepPlanActionSink.MAX_MENU_DEPTH + 1, "Payments");

        assertThrows(IllegalArgumentException.class, () -> sink.navigateByMenu(tooDeep));
        assertEquals(0, observations.get());
    }

    @Test
    public void ambiguousDescriptionFailsWithoutAnyClick() {
        PlaywrightStepPlanActionSink sink = sink(obs(el("button", "Submit"), el("button", "Submit")),
                PlaywrightStepPlanActionSink.denyAll());

        assertThrows(SemanticElementException.class, () -> sink.clickOn("Submit"));

        assertEquals(1, observations.get());
        verify(locator, never()).click();
    }

    @Test
    public void fillWithPrefersTextboxAndUsesInputPreferredRoles() {
        PlaywrightStepPlanActionSink sink = sink(obs(el("heading", "User"), el("textbox", "User")),
                PlaywrightStepPlanActionSink.denyAll());

        sink.fillWith("User", "alice");

        verify(page).getByRole(eq(AriaRole.TEXTBOX), any(Page.GetByRoleOptions.class));
        verify(locator).fill("alice");
    }

    @Test
    public void navigationIsFailClosedUnlessUrlIsAllowListed() {
        PlaywrightStepPlanActionSink sink = sink(obs(), PlaywrightStepPlanActionSink.denyAll());

        assertThrows(IllegalArgumentException.class,
                () -> sink.navigateTo("https://portal.example.com/logon"));
        verify(page, never()).navigate(anyString());
    }

    @Test
    public void allowingHostsAcceptsSubdomainAndRejectsForeignHost() {
        Predicate<String> policy = PlaywrightStepPlanActionSink.allowingHosts("example.com");
        PlaywrightStepPlanActionSink sink = sink(obs(), policy);

        sink.navigateTo("https://portal.example.com/logon");

        verify(page).navigate("https://portal.example.com/logon");
        assertThrows(IllegalArgumentException.class, () -> sink.navigateTo("https://evil.test/"));
        assertThrows(IllegalArgumentException.class, () -> sink.navigateTo("not a url"));
    }

    @Test
    public void expectTextAcceptsVisibleBusinessDescription() {
        PlaywrightStepPlanActionSink sink = sink(obs(el("heading", "Account Overview")),
                PlaywrightStepPlanActionSink.denyAll());

        sink.expectText("Account Overview");

        assertEquals(1, observations.get());
    }

    @Test
    public void expectTextFailsWhenDescriptionIsAbsent() {
        PlaywrightStepPlanActionSink sink = sink(obs(el("heading", "Account Overview")),
                PlaywrightStepPlanActionSink.denyAll());

        assertThrows(AssertionError.class, () -> sink.expectText("Loan Services"));
    }

    @Test
    public void popupSwitchRebindsObservationProviderAndUsesNewPage() {
        Page popup = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(context.pages()).thenReturn(Arrays.asList(page, popup));
        when(popup.getByRole(any(AriaRole.class), any(Page.GetByRoleOptions.class))).thenReturn(locator);

        List<Page> boundPages = new ArrayList<>();
        PageObservationProvider provider = ref -> {
            observations.incrementAndGet();
            return obs(el("link", "Payments"));
        };
        PlaywrightStepPlanActionSink sink = new PlaywrightStepPlanActionSink(page,
                p -> {
                    boundPages.add(p);
                    return provider;
                },
                null, PlaywrightStepPlanActionSink.denyAll(),
                new SemanticElementResolver(), "test-page");

        sink.switchToPopup();
        sink.clickOn("Payments");

        assertTrue("观察源应绑定到 popup 页", boundPages.contains(popup));
        verify(popup).getByRole(eq(AriaRole.LINK), any(Page.GetByRoleOptions.class));
    }

    @Test
    public void contextRebuildIsPickedUpByReevaluatingPageSupplier() {
        Page rebuilt = mock(Page.class);
        Locator rebuiltLocator = mock(Locator.class);
        when(rebuilt.getByRole(any(AriaRole.class), any(Page.GetByRoleOptions.class)))
                .thenReturn(rebuiltLocator);
        AtomicReference<Page> current = new AtomicReference<>(page);
        PageObservationProvider provider = ref -> obs(el("link", "Payments"));
        PlaywrightStepPlanActionSink sink = new PlaywrightStepPlanActionSink(current::get,
                p -> provider, null, PlaywrightStepPlanActionSink.denyAll(),
                new SemanticElementResolver(), "test-page");

        sink.clickOn("Payments");
        current.set(rebuilt);
        sink.clickOn("Payments");

        verify(page, times(1)).getByRole(eq(AriaRole.LINK), any(Page.GetByRoleOptions.class));
        verify(rebuilt, times(1)).getByRole(eq(AriaRole.LINK), any(Page.GetByRoleOptions.class));
        verify(rebuiltLocator).click();
    }

    @Test
    public void urlPolicyIsMandatory() {
        assertThrows(NullPointerException.class,
                () -> new PlaywrightStepPlanActionSink(page, null, null));
    }

    private PlaywrightStepPlanActionSink sink(PageObservation observation, Predicate<String> urlPolicy) {
        PageObservationProvider provider = ref -> {
            observations.incrementAndGet();
            return observation;
        };
        return new PlaywrightStepPlanActionSink(page, p -> provider, null, urlPolicy,
                new SemanticElementResolver(), "test-page");
    }

    private static PageObservation obs(ObservedElement... elements) {
        return new PageObservation("https://portal.example.com/home", "home",
                Arrays.asList(elements), null);
    }

    private static ObservedElement el(String role, String name) {
        return new ObservedElement(role, name, null, true, true,
                List.of(LocatorSpec.role(role, name)), Collections.emptyMap(), null);
    }
}

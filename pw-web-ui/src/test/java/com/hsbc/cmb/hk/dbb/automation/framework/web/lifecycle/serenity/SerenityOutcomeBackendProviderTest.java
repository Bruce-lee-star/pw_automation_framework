package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;

import io.cucumber.core.backend.TestCaseState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SerenityOutcomeBackendProviderTest {

    @Test
    public void afterHookHasMaxOrderAndNoTagFilter() {
        SerenityOutcomeBackendProvider.OutcomeAfterHook hook =
                new SerenityOutcomeBackendProvider.OutcomeAfterHook();
        assertEquals(Integer.MAX_VALUE, hook.getOrder());
        assertNull(hook.getTagExpression());
    }

    @Test
    public void afterHookSkipsWhenCucumberAlreadyFailed() {
        TestCaseState state = mock(TestCaseState.class);
        when(state.isFailed()).thenReturn(true);
        new SerenityOutcomeBackendProvider.OutcomeAfterHook().execute(state); // 不抛
    }

    @Test
    public void afterHookDoesNotThrowWhenNoSerenityContext() {
        // 本线程无 Serenity 总线 / outcome → 校验器安全降级 no-op
        TestCaseState state = mock(TestCaseState.class);
        when(state.isFailed()).thenReturn(false);
        new SerenityOutcomeBackendProvider.OutcomeAfterHook().execute(state); // 不抛
    }

    @Test
    public void backendProviderCreatesBackend() {
        SerenityOutcomeBackendProvider provider = new SerenityOutcomeBackendProvider();
        provider.create(null, null, null);
    }
}

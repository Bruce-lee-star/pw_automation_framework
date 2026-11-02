package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextOutcomeBinding;
import com.microsoft.playwright.BrowserContext;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ScenarioTraceTargetTest {

    @Test
    public void contextBindingWinsOverGlobalActiveOutcomeFallback() {
        BrowserContext context = mock(BrowserContext.class);
        ContextOutcomeBinding.bind(context, "context-case");

        ReportingBridge previous = ReportingBridgeRegistry.get();
        ReportingBridge bridge = mock(ReportingBridge.class);
        when(bridge.traceTargetByOutcomeId("context-case"))
                .thenReturn(Path.of("reports", "context-case", "trace.zip"));
        when(bridge.traceTarget()).thenReturn(Path.of("reports", "latest-case", "trace.zip"));
        ReportingBridgeRegistry.install(bridge);
        try {
            assertEquals(Path.of("reports", "context-case", "trace.zip"),
                    ScenarioTraceRecorder.resolveTraceTarget(context, "fallback.zip"));
        } finally {
            ContextOutcomeBinding.unbind(context);
            ReportingBridgeRegistry.install(previous);
        }
    }

    @Test
    public void missingBoundCaseTargetDoesNotFallBackToAnotherActiveCase() {
        BrowserContext context = mock(BrowserContext.class);
        ContextOutcomeBinding.bind(context, "context-case");

        ReportingBridge previous = ReportingBridgeRegistry.get();
        ReportingBridge bridge = mock(ReportingBridge.class);
        when(bridge.traceTargetByOutcomeId("context-case")).thenReturn(null);
        when(bridge.traceTarget()).thenReturn(Path.of("reports", "latest-case", "trace.zip"));
        ReportingBridgeRegistry.install(bridge);
        try {
            assertEquals(Paths.get("target/reports/traces", "fallback.zip"),
                    ScenarioTraceRecorder.resolveTraceTarget(context, "fallback.zip"));
        } finally {
            ContextOutcomeBinding.unbind(context);
            ReportingBridgeRegistry.install(previous);
        }
    }
}

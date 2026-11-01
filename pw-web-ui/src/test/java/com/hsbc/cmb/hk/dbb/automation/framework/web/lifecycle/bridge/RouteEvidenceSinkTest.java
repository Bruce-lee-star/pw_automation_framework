package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextOutcomeBinding;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RouteEvidenceSinkTest {

    private ReportingBridge previous;
    private TestReportingBridge bridge;

    @Before
    public void setUp() {
        previous = ReportingBridgeRegistry.get();
        bridge = new TestReportingBridge();
        ReportingBridgeRegistry.install(bridge);
        RouteEvidenceSink.clearPendingForTests();
    }

    @After
    public void tearDown() {
        RouteEvidenceSink.clearPendingForTests();
        ReportingBridgeRegistry.install(previous);
    }

    @Test
    public void flushOnlyWritesEvidenceForCurrentOutcome() {
        RouteEvidenceSink sink = new RouteEvidenceSink();

        bridge.outcomeId = "case-a";
        sink.record("CAPTURE", "https://example.test/a?token=secret", "detail-a");
        bridge.outcomeId = "case-b";
        sink.record("CAPTURE", "https://example.test/b", "detail-b");

        sink.flush();

        assertEquals(List.of("[Route CAPTURE] https://example.test/b"), bridge.reportKeys);
        assertEquals(List.of("detail-b"), bridge.reportValues);
        assertEquals(1, RouteEvidenceSink.pendingCount());

        bridge.outcomeId = "case-a";
        sink.flush();

        assertEquals(List.of("[Route CAPTURE] https://example.test/b",
                "[Route CAPTURE] https://example.test/a?***"), bridge.reportKeys);
        assertEquals(List.of("detail-b", "detail-a"), bridge.reportValues);
        assertEquals(0, RouteEvidenceSink.pendingCount());
    }

    @Test
    public void evidenceWithoutOutcomeIsCountedAndNeverQueued() {
        RouteEvidenceSink sink = new RouteEvidenceSink();
        bridge.outcomeId = null;
        long before = RouteEvidenceSink.unattributedCount();

        sink.record("MONITOR", "https://example.test/no-context", "unattributed");

        assertEquals(before + 1, RouteEvidenceSink.unattributedCount());
        assertEquals(0, RouteEvidenceSink.pendingCount());
    }

    @Test
    public void overflowingOneOutcomeDoesNotEvictAnotherOutcomesEvidence() {
        RouteEvidenceSink sink = new RouteEvidenceSink();
        bridge.outcomeId = "noisy-case";
        long droppedBefore = RouteEvidenceSink.droppedCount();
        int perOutcomeLimit = RouteEvidenceSink.maxPendingPerOutcome();
        for (int i = 0; i <= perOutcomeLimit; i++) {
            sink.record("CAPTURE", "https://example.test/noisy/" + i, "noisy-" + i);
        }
        bridge.outcomeId = "quiet-case";
        sink.record("CAPTURE", "https://example.test/quiet", "quiet");

        assertEquals(perOutcomeLimit + 1, RouteEvidenceSink.pendingCount());
        assertEquals(droppedBefore + 1, RouteEvidenceSink.droppedCount());

        sink.flush();

        assertEquals(List.of("[Route CAPTURE] https://example.test/quiet"), bridge.reportKeys);
        assertEquals(List.of("quiet"), bridge.reportValues);
        assertEquals(perOutcomeLimit, RouteEvidenceSink.pendingCount());

        bridge.outcomeId = "noisy-case";
        sink.flush();

        assertEquals(0, RouteEvidenceSink.pendingCount());
        assertEquals(perOutcomeLimit + 1, bridge.reportKeys.size());
    }

    @Test
    public void globalSaturationRebalancesFromAnOverrepresentedOutcome() {
        RouteEvidenceSink sink = new RouteEvidenceSink();
        int globalLimit = RouteEvidenceSink.maxPending();
        int perOutcomeLimit = RouteEvidenceSink.maxPendingPerOutcome();
        long droppedBefore = RouteEvidenceSink.droppedCount();

        if (globalLimit <= 2 || perOutcomeLimit == 1) {
            for (int i = 0; i < globalLimit; i++) {
                bridge.outcomeId = "occupied-" + i;
                sink.record("CAPTURE", "https://example.test/occupied/" + i, "occupied-" + i);
            }
            bridge.outcomeId = "incoming";
            sink.record("CAPTURE", "https://example.test/incoming", "incoming");
            assertEquals(globalLimit, RouteEvidenceSink.pendingCount());
            assertEquals(droppedBefore + 1, RouteEvidenceSink.droppedCount());
            return;
        }

        int donorRecords = Math.min(perOutcomeLimit, globalLimit - 1);
        bridge.outcomeId = "overrepresented";
        for (int i = 0; i < donorRecords; i++) {
            sink.record("CAPTURE", "https://example.test/donor/" + i, "donor-" + i);
        }
        for (int i = donorRecords; i < globalLimit; i++) {
            bridge.outcomeId = "existing-" + i;
            sink.record("CAPTURE", "https://example.test/existing/" + i, "existing-" + i);
        }

        bridge.outcomeId = "incoming";
        sink.record("CAPTURE", "https://example.test/incoming", "incoming");

        assertEquals(globalLimit, RouteEvidenceSink.pendingCount());
        assertEquals(droppedBefore + 1, RouteEvidenceSink.droppedCount());
        sink.flush();
        assertEquals("[Route CAPTURE] https://example.test/incoming",
                bridge.reportKeys.get(bridge.reportKeys.size() - 1));
        assertEquals("incoming", bridge.reportValues.get(bridge.reportValues.size() - 1));
    }

    @Test
    public void explicitContextBindingWinsOverFlushThreadOutcome() {
        RouteEvidenceSink sink = new RouteEvidenceSink();
        BrowserContext context = browserContextStub();
        ContextOutcomeBinding.bind(context, "context-case");
        try {
            bridge.outcomeId = "different-flush-case";
            bridge.stepId = "wrong-step";
            sink.record("CAPTURE", "https://example.test/context", "context-detail", context);

            bridge.outcomeId = "context-case";
            sink.flush();

            assertEquals(List.of("[Route CAPTURE] https://example.test/context"), bridge.reportKeys);
            assertEquals(List.of("context-detail"), bridge.reportValues);
            assertEquals(1, bridge.recordedStepIds.size());
            assertNull(bridge.recordedStepIds.get(0));
            assertEquals(0, RouteEvidenceSink.pendingCount());
        } finally {
            ContextOutcomeBinding.unbind(context);
        }
    }

    private static BrowserContext browserContextStub() {
        return (BrowserContext) Proxy.newProxyInstance(
                BrowserContext.class.getClassLoader(),
                new Class<?>[] {BrowserContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "BrowserContextStub";
                    default -> null;
                });
    }

    private static final class TestReportingBridge implements ReportingBridge {
        private String outcomeId;
        private String stepId;
        private final List<String> reportKeys = new ArrayList<>();
        private final List<Object> reportValues = new ArrayList<>();
        private final List<String> recordedStepIds = new ArrayList<>();

        @Override
        public boolean isReportingActive() {
            return true;
        }

        @Override
        public String currentOutcomeId() {
            return outcomeId;
        }

        @Override
        public String currentStepId() {
            return stepId;
        }

        @Override
        public void recordReportData(String key, Object value) {
            reportKeys.add(key);
            reportValues.add(value);
        }

        @Override
        public void recordApiOperation(String stepId, String line) {
            recordedStepIds.add(stepId);
        }
    }
}

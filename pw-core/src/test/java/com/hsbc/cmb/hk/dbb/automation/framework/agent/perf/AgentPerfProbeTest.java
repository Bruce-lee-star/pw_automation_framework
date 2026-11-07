package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 埋点门面契约（T9）：默认关闭零开销、开启后分桶上报、采样灰度、并发配对、
 * 失败类别归一、样本值对象防御，以及「采集异常绝不外溢」。
 */
public class AgentPerfProbeTest {

    private String previousEnabled;
    private String previousRate;
    private final List<AgentPerfSample> captured = new ArrayList<>();

    @Before
    public void setUp() {
        previousEnabled = System.getProperty(AgentPerfConfig.ENABLED_KEY);
        previousRate = System.getProperty(AgentPerfConfig.SAMPLE_RATE_KEY);
        captured.clear();
        AgentPerfRegistry.install(captured::add);
    }

    @After
    public void tearDown() {
        System.setProperty(AgentPerfConfig.ENABLED_KEY,
                previousEnabled == null ? "false" : previousEnabled);
        System.setProperty(AgentPerfConfig.SAMPLE_RATE_KEY,
                previousRate == null ? "1.0" : previousRate);
        AgentPerfProbe.resetForTest();
        AgentPerfRegistry.reset();
    }

    @Test
    public void disabledProbeTakesNoTimingAndProducesNoSamples() {
        System.setProperty(AgentPerfConfig.ENABLED_KEY, "false");
        AgentPerfProbe.resetForTest();

        assertFalse(AgentPerfProbe.isEnabled());
        assertEquals(AgentPerfProbe.DISABLED, AgentPerfProbe.start());
        assertEquals(0, AgentPerfProbe.enterScope());
        AgentPerfProbe.recordStep(System.nanoTime(), true, null);
        AgentPerfProbe.recordPlan(System.nanoTime(), 10, true, null, 1);
        AgentPerfProbe.exitScope(0);

        assertTrue(captured.isEmpty());
        assertEquals(0, AgentPerfProbe.currentInFlight());
        assertEquals(0, AgentPerfProbe.peakInFlight());
    }

    @Test
    public void enabledProbeReportsStepAndPlanBucketsAndPairsConcurrency() {
        enable(1.0);
        long startedAt = AgentPerfProbe.start();
        assertTrue("开启时必须真实计时", startedAt != AgentPerfProbe.DISABLED);
        int inFlight = AgentPerfProbe.enterScope();
        assertEquals(1, inFlight);

        AgentPerfProbe.recordStep(startedAt, true, null);
        AgentPerfProbe.recordPlan(startedAt, 10, true, null, inFlight);
        AgentPerfProbe.exitScope(inFlight);

        assertEquals(2, captured.size());
        assertEquals(AgentPerfSample.STEP_BUCKET, captured.get(0).bucket());
        assertEquals(1, captured.get(0).stepCount());
        assertEquals("plan-10", captured.get(1).bucket());
        assertEquals(10, captured.get(1).stepCount());
        assertEquals(1, captured.get(1).inFlight());
        assertTrue(captured.stream().allMatch(AgentPerfSample::success));
        assertEquals(1, AgentPerfProbe.peakInFlight());
        assertEquals(0, AgentPerfProbe.currentInFlight());
    }

    @Test
    public void zeroSampleRateDropsEverySample() {
        enable(0.0);
        long startedAt = AgentPerfProbe.start();
        AgentPerfProbe.recordStep(startedAt, false, new TimeoutException("timed out"));
        AgentPerfProbe.recordPlan(startedAt, 100, false, new TimeoutException("timed out"), 1);
        assertTrue(captured.isEmpty());
    }

    @Test
    public void failureKindIsNormalisedWithoutPersistingMessage() {
        enable(1.0);
        long startedAt = AgentPerfProbe.start();
        AgentPerfProbe.recordPlan(startedAt, 100, false, new TimeoutException("secret-token-xyz"), 1);
        AgentPerfProbe.recordPlan(startedAt, 1, false,
                new IllegalStateException("Target page, context or browser has been closed"), 1);
        AgentPerfProbe.recordPlan(startedAt, 5, false, null, 1);

        assertEquals(3, captured.size());
        assertEquals(AgentPerfSample.FailureKind.TIMEOUT, captured.get(0).failureKind());
        assertEquals("plan-100", captured.get(0).bucket());
        assertEquals(AgentPerfSample.FailureKind.BROWSER_CRASH, captured.get(1).failureKind());
        assertEquals(AgentPerfSample.FailureKind.UNKNOWN, captured.get(2).failureKind());
        assertFalse(captured.stream().allMatch(AgentPerfSample::success));
    }

    @Test
    public void successfulSampleCarriesNoFailureKind() {
        enable(1.0);
        AgentPerfProbe.recordStep(AgentPerfProbe.start(), true, null);
        assertNull(captured.get(0).failureKind());
    }

    @Test
    public void planBucketNamesAreStable() {
        assertEquals("plan-1", AgentPerfSample.planBucket(1));
        assertEquals("plan-10", AgentPerfSample.planBucket(10));
        assertEquals("plan-100", AgentPerfSample.planBucket(100));
        assertEquals("plan-1", AgentPerfSample.planBucket(0));
    }

    @Test
    public void sampleRejectsIllegalState() {
        try {
            new AgentPerfSample("step", 1, -1L, true, null, 1, 0L);
            fail("negative duration must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new AgentPerfSample("step", 1, 1L, true, null, 0, 0L);
            fail("inFlight must be >= 1");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new AgentPerfSample(null, 1, 1L, true, null, 1, 0L);
            fail("null bucket must be rejected");
        } catch (NullPointerException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void sinkFailureNeverEscapesToExecutionChain() {
        enable(1.0);
        AgentPerfRegistry.install(sample -> {
            throw new IllegalStateException("sink down");
        });
        AgentPerfProbe.recordStep(AgentPerfProbe.start(), true, null);
        AgentPerfProbe.recordPlan(AgentPerfProbe.start(), 1, true, null, 1);
    }

    private void enable(double rate) {
        System.setProperty(AgentPerfConfig.ENABLED_KEY, "true");
        System.setProperty(AgentPerfConfig.SAMPLE_RATE_KEY, Double.toString(rate));
        AgentPerfProbe.resetForTest();
        assertTrue(AgentPerfProbe.isEnabled());
    }
}

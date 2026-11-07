package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfProbe;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfSample;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T9 执行链接线验收：默认关闭零回归，开启后真实产出 1 / 10 / 100 步的
 * 单步与端到端样本，异常路径同样记录失败样本，且并发计数严格配对。
 *
 * <p>使用无浏览器 FakeHarness，故本机产出的是<b>框架开销基线</b>（不含浏览器
 * 冷启动与网络往返）；真浏览器基线由 CI 以 {@code -Dagent.perf.enabled=true}
 * 跑真实 harness 后由 pw-reports 落盘，见设计稿 §8.1.1。</p>
 */
public class AgentPerfWiringTest {

    private static final String URL = "file:///fixture.html";

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
    public void collectionOffKeepsExecutionSemanticsAndProducesNoSamples() {
        System.setProperty(AgentPerfConfig.ENABLED_KEY, "false");
        AgentPerfProbe.resetForTest();
        FakeHarness harness = new FakeHarness();

        List<CommandResult> results = new StepPlanRunner(harness).execute(plan(10), URL);

        assertEquals(10, results.size());
        assertEquals(1, harness.acquired);
        assertEquals(1, harness.released);
        assertTrue("默认关时不得产生任何基线样本", captured.isEmpty());
        assertEquals(0, AgentPerfProbe.peakInFlight());
    }

    @Test
    public void enabledCollectionReportsStepAndPlanBucketsFor1_10_100() {
        enable(1.0);
        for (int steps : new int[]{1, 10, 100}) {
            List<CommandResult> results = new StepPlanRunner(new FakeHarness())
                    .execute(plan(steps), URL);
            assertEquals(steps, results.size());
        }

        Map<String, Long> byBucket = captured.stream()
                .collect(Collectors.groupingBy(AgentPerfSample::bucket, Collectors.counting()));
        assertEquals("单步样本应覆盖 1+10+100 步", Long.valueOf(111), byBucket.get("step"));
        assertEquals(Long.valueOf(1), byBucket.get("plan-1"));
        assertEquals(Long.valueOf(1), byBucket.get("plan-10"));
        assertEquals(Long.valueOf(1), byBucket.get("plan-100"));
        assertEquals(114, captured.size());
        assertTrue("样本须全部成功", captured.stream().allMatch(AgentPerfSample::success));
        assertEquals("作用域退出后并发计数必须归零", 0, AgentPerfProbe.currentInFlight());
        assertTrue(AgentPerfProbe.peakInFlight() >= 1);
    }

    @Test
    public void zeroSampleRateKeepsExecutionButDropsAllSamples() {
        enable(0.0);
        List<CommandResult> results = new StepPlanRunner(new FakeHarness()).execute(plan(3), URL);
        assertEquals(3, results.size());
        assertTrue(captured.isEmpty());
    }

    @Test
    public void failurePathRecordsFailedPlanSampleAndStillReleasesScope() {
        enable(1.0);
        FakeHarness harness = new FakeHarness() {
            @Override
            public BrowserCommandChannel acquire(String targetUrl) {
                super.acquire(targetUrl);
                throw new IllegalStateException("boom");
            }
        };

        try {
            new StepPlanRunner(harness).execute(plan(1), URL);
            fail("执行体异常须上抛");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("boom"));
        }

        assertEquals("异常路径只产生整计划样本（失败发生在 acquire，尚无单步）",
                1, captured.size());
        AgentPerfSample sample = captured.get(0);
        assertEquals("plan-1", sample.bucket());
        assertFalse(sample.success());
        assertEquals(AgentPerfSample.FailureKind.UNKNOWN, sample.failureKind());
        assertEquals(0, AgentPerfProbe.currentInFlight());
    }

    private void enable(double rate) {
        System.setProperty(AgentPerfConfig.ENABLED_KEY, "true");
        System.setProperty(AgentPerfConfig.SAMPLE_RATE_KEY, Double.toString(rate));
        AgentPerfProbe.resetForTest();
        assertTrue(AgentPerfProbe.isEnabled());
    }

    private static StepPlan plan(int steps) {
        Command navigate = new Command(Command.ActionType.NAVIGATE, null,
                Map.of("url", URL), List.of());
        ParamScope scope = new ParamScope(Map.of("url", ParamConstraint.allowedValues(URL)));
        // planDigest() 会逐步读取 stepScopes，须与 steps 等长（否则 AIOOBE）
        return new StepPlan(1, "plan-t9-" + steps,
                Collections.nCopies(steps, navigate), scope,
                Collections.nCopies(steps, ParamScope.EMPTY));
    }

    /** 无浏览器假靶场：仅计数，便于在任何环境产出框架开销基线。 */
    private static class FakeHarness implements IsolationHarness {
        int acquired;
        int released;

        @Override
        public BrowserCommandChannel acquire(String targetUrl) {
            acquired++;
            Snapshot snapshot = new Snapshot(URL, List.of());
            return new BrowserCommandChannel() {
                @Override
                public Snapshot snapshot() {
                    return snapshot;
                }

                @Override
                public CommandResult execute(Command command) {
                    return CommandResult.ok(snapshot);
                }
            };
        }

        @Override
        public void release(BrowserCommandChannel channel) {
            released++;
        }
    }
}

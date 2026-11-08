package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetDraft;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.InMemoryAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentConcurrencyControl;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentMonitorControl;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRouteControl;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContexts;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentSessionControl;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyPartitionKey;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T5 验收：执行链与业务链在<b>同一进程内</b>共享同一份运行时状态（消除孤岛），且默认零回归。
 *
 * <p>三组证据：① 执行链经组合根进入作用域时，业务链 {@link ConcurrencyGate} 的同一把闸门被
 * 真实占用 / 释放（用 {@code stats()} 观测，不是另起一把锁）；② 业务链 RouteLifecycle
 * 注册实例收到执行链的场景归属；③ 默认构造全 no-op，既有执行语义逐字不变。</p>
 */
public class AgentRuntimeWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    private static final String SESSION = "sit_t5user";
    private static final String GATE_ENABLED_KEY =
            WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getKey();
    private static final String GATE_DEFAULT =
            WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getDefaultValue();
    private String previousGateSetting;

    @Before
    public void captureGateSetting() {
        previousGateSetting = System.getProperty(GATE_ENABLED_KEY);
    }

    @After
    public void cleanup() {
        AgentRuntimeContexts.reset();
        restoreGateSetting();
        ConcurrencyGate.releaseAllForCurrentThread();
    }

    /**
     * 显式复位为「原值或默认值」，<b>绝不用 clearProperty</b>：Serenity 的
     * ConfigResolver 是启动期系统属性快照 + 静态缓存，clear 不会回落到文件/默认值，
     * 会把 "true" 残留给同 JVM 后续测试（既有踩坑）。
     */
    private void restoreGateSetting() {
        System.setProperty(GATE_ENABLED_KEY,
                previousGateSetting == null ? GATE_DEFAULT : previousGateSetting);
    }

    // ---------------- ① 并发语义一致 ----------------

    @Test
    public void executionChainUsesTheSameConcurrencyGateAsBusinessChain() {
        System.setProperty(GATE_ENABLED_KEY, "true");
        ConcurrencyGate.releaseAllForCurrentThread();
        int baseline = ConcurrencyGate.stats().activeGates;
        long enterBefore = ConcurrencyGate.stats().enterCount;

        AgentRuntimeContext ctx = new WebAgentRuntimeContextProvider(() -> null).contribute();
        ConcurrencyPartitionKey key = ConcurrencyPartitionKey.of(Map.of("sessionkey", SESSION));

        ctx.runInScope(AgentRuntimeScope.of("plan-1", "file:///x", SESSION), () -> {
            assertEquals("执行链占用的必须是业务链同一把闸门",
                    baseline + 1, ConcurrencyGate.stats().activeGates);
            // 业务链同 key 重入：不得自锁（同线程重入为 no-op）
            ConcurrencyGate.acquire(key);
            return null;
        });

        assertTrue("进入计数须反映执行链真实取用",
                ConcurrencyGate.stats().enterCount > enterBefore);
        assertEquals("退出作用域后许可必须归还，不泄漏",
                baseline, ConcurrencyGate.stats().activeGates);
    }

    @Test
    public void withoutSessionKeyConcurrencyAdapterIsNoOp() {
        System.setProperty(GATE_ENABLED_KEY, "true");
        ConcurrencyGate.releaseAllForCurrentThread();
        long enterBefore = ConcurrencyGate.stats().enterCount;

        new WebAgentRuntimeContextProvider(() -> null).contribute()
                .runInScope(AgentRuntimeScope.of("file:///x"), () -> "ok");

        assertEquals("无登录身份时不得占用业务链闸门（零回归）",
                enterBefore, ConcurrencyGate.stats().enterCount);
    }

    // ---------------- ② 路由 / 监控语义一致 ----------------

    @Test
    public void executionChainDrivesTheSameRouteLifecycleInstance() {
        RecordingRouteLifecycle fake = new RecordingRouteLifecycle();
        AgentRuntimeContext ctx = new WebAgentRuntimeContextProvider(() -> fake).contribute();

        ctx.runInScope(AgentRuntimeScope.of("plan-42", "file:///x", null), () -> null);

        assertEquals("执行链须把计划标识写入业务链同一 RouteLifecycle",
                List.of("plan-42"), fake.scenarios);
        assertEquals("退出须成对清除，净状态为零", 1, fake.cleared);
        assertEquals("Monitor 角色须共享同一注册实例的失败采集上下文", 1, fake.resolveCalls);
    }

    @Test
    public void adaptersAreNoOpWithoutRouteModuleAndWithoutSessionKey() {
        AgentRuntimeContext ctx = new WebAgentRuntimeContextProvider(() -> null).contribute();

        assertEquals("ok", ctx.runInScope(AgentRuntimeScope.of("file:///x"), () -> "ok"));
    }

    // ---------------- ③ 默认零回归 + 接线后仍闭环 ----------------

    @Test
    public void defaultRunnerRemainsNoOpAndPreservesExecutionSemantics() {
        FakeHarness harness = new FakeHarness(satisfyingSnapshot());
        StepPlanRunner runner = new StepPlanRunner(harness);

        List<CommandResult> results = runner.execute(plan(), "file:///fixture.html");

        assertEquals(1, results.size());
        assertEquals(1, harness.acquired);
        assertEquals(1, harness.released);
    }

    @Test
    public void wiredRunnerStillCompletesVerifyWriteBackClose() throws Exception {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        AssetId id = repo.save(new AssetDraft(AssetType.STEP_PLAN, "REQ-T5",
                MAPPER.writeValueAsString(new VerifiableStepPlan(plan(), "file:///fixture.html")),
                Provenance.manual("p")));

        List<String> events = new ArrayList<>();
        AgentRuntimeContext runtime = AgentRuntimeContext.of(
                recordingRoute(events, "route"), recordingSession(events, "session"),
                recordingConcurrency(events, "concurrency"), recordingMonitor(events, "monitor"));
        FakeHarness harness = new FakeHarness(satisfyingSnapshot());

        VerificationReport report = new AssetVerificationWiring(repo, harness, runtime)
                .verify(id, new VerificationScope("ignored", 1));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertEquals("组合根须在执行前后完整驱动四槽",
                List.of("open:concurrency", "open:session", "open:route", "open:monitor",
                        "close:monitor", "close:route", "close:session", "close:concurrency"), events);
        assertEquals(1, harness.acquired);
        assertEquals(1, harness.released);
        assertEquals(report, repo.get(id).report());
    }

    @Test
    public void runtimeEnterFailureRejectsExecutionBeforeChannelAcquire() {
        FakeHarness harness = new FakeHarness(satisfyingSnapshot());
        AgentRuntimeContext runtime = AgentRuntimeContext.of(
                AgentRouteControl.NO_OP, AgentSessionControl.NO_OP,
                new AgentConcurrencyControl() {
                    @Override
                    public void onScopeOpen(AgentRuntimeScope scope) {
                        throw new IllegalStateException("gate unavailable");
                    }
                }, AgentMonitorControl.NO_OP);

        try {
            new StepPlanRunner(harness, null, runtime).execute(plan(), "file:///fixture.html");
            fail("角色进入失败必须拒绝执行");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("gate unavailable"));
        }
        assertEquals("进入失败时不得取用通道", 0, harness.acquired);
    }

    @Test
    public void bodyFailureStillReleasesAllSlots() {
        FakeHarness harness = new FakeHarness(satisfyingSnapshot()) {
            @Override
            public BrowserCommandChannel acquire(String targetUrl) {
                super.acquire(targetUrl);
                throw new IllegalStateException("acquire failed");
            }
        };
        List<String> events = new ArrayList<>();
        AgentRuntimeContext runtime = AgentRuntimeContext.of(
                recordingRoute(events, "route"), recordingSession(events, "session"),
                recordingConcurrency(events, "concurrency"), recordingMonitor(events, "monitor"));

        try {
            new StepPlanRunner(harness, null, runtime).execute(plan(), "file:///fixture.html");
            fail("执行体异常须上抛");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("acquire failed"));
        }
        assertEquals(4, events.stream().filter(e -> e.startsWith("open:")).count());
        assertEquals("异常路径仍须逆序退出全部槽位", 4,
                events.stream().filter(e -> e.startsWith("close:")).count());
    }

    @Test
    public void spiContributionIsDiscoverableAtProductionRoot() {
        AgentRuntimeContexts.reset();
        AgentRuntimeContext ctx = AgentRuntimeContexts.current();
        assertNotNull(ctx);
        assertFalse("生产 classpath 须发现 WebAgentRuntimeContextProvider（Route 槽位）",
                ctx.route() == AgentRouteControl.NO_OP);
        assertFalse(ctx.session() == AgentSessionControl.NO_OP);
        assertFalse(ctx.concurrency() == AgentConcurrencyControl.NO_OP);
        assertFalse(ctx.monitor() == AgentMonitorControl.NO_OP);
    }

    // ---------------- fixtures ----------------

    private static StepPlan plan() {
        Command navigate = new Command(Command.ActionType.NAVIGATE, null,
                Map.of("url", "file:///fixture.html"), List.of());
        // 治理预检（ParamScopePolicy）+ 通道门禁（ParamScopeGate）：NAVIGATE 的 url
        // 必须显式声明取值域，否则在 harness.acquire 之前即抛 ParamScopeViolationException。
        ParamScope scope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues("file:///fixture.html")));
        return new StepPlan(1, "plan-t5", List.of(navigate), scope,
                List.of(ParamScope.EMPTY));
    }

    private static Snapshot satisfyingSnapshot() {
        return new Snapshot("file:///fixture.html", List.of());
    }

    private static AgentRouteControl recordingRoute(List<String> events, String name) {
        return new AgentRouteControl() {
            @Override public void onScopeOpen(AgentRuntimeScope s) { events.add("open:" + name); }
            @Override public void onScopeClose(AgentRuntimeScope s, Throwable f) { events.add("close:" + name); }
        };
    }

    private static AgentSessionControl recordingSession(List<String> events, String name) {
        return new AgentSessionControl() {
            @Override public void onScopeOpen(AgentRuntimeScope s) { events.add("open:" + name); }
            @Override public void onScopeClose(AgentRuntimeScope s, Throwable f) { events.add("close:" + name); }
        };
    }

    private static AgentConcurrencyControl recordingConcurrency(List<String> events, String name) {
        return new AgentConcurrencyControl() {
            @Override public void onScopeOpen(AgentRuntimeScope s) { events.add("open:" + name); }
            @Override public void onScopeClose(AgentRuntimeScope s, Throwable f) { events.add("close:" + name); }
        };
    }

    private static AgentMonitorControl recordingMonitor(List<String> events, String name) {
        return new AgentMonitorControl() {
            @Override public void onScopeOpen(AgentRuntimeScope s) { events.add("open:" + name); }
            @Override public void onScopeClose(AgentRuntimeScope s, Throwable f) { events.add("close:" + name); }
        };
    }

    /** 假靶场：仅计数，无浏览器依赖。 */
    private static class FakeHarness implements IsolationHarness {
        int acquired;
        int released;
        private final Snapshot snapshot;

        FakeHarness(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public BrowserCommandChannel acquire(String targetUrl) {
            acquired++;
            return new BrowserCommandChannel() {
                @Override public Snapshot snapshot() { return snapshot; }
                @Override public CommandResult execute(Command command) { return CommandResult.ok(snapshot); }
            };
        }

        @Override
        public void release(BrowserCommandChannel channel) {
            released++;
        }
    }

    /** RouteLifecycle 记录替身：验证执行链是否驱动了同一个注册实例。 */
    private static final class RecordingRouteLifecycle implements RouteLifecycle {
        final List<String> scenarios = new ArrayList<>();
        int cleared;
        int resolveCalls;

        @Override public void resetCaptureCurrent() { }
        @Override public void stopCapture() { }
        @Override public CaptureContext getCurrentCapture() { return null; }
        @Override public void setMonitorScenario(String name) { scenarios.add(name); }
        @Override public void clearMonitorScenario() { cleared++; }
        @Override public void clearMonitorFeature() { }
        @Override public void resetAll() { }
        @Override public void clearContext(Object ctx) { }
        @Override public void clearAll() { }
        @Override public void clearDispatchedRoutes() { }
        @Override public void stopContextEngine(Object ctx) { }
        @Override public void stopAllContextEngines() { }
        @Override public void shutdownRouteEngine() { }
        @Override public String sanitizeUrl(String url) { return url; }
        @Override public CaptureContext resolveFailureCapture() { resolveCalls++; return null; }
    }
}

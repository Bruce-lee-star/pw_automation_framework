package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.DslAcceptanceRunner;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotBounds;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotEnvelope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>§10.3 指定的 P0 专用 JUnit4 acceptance test</b>（{@code -Dtest=AgentDriverDslAcceptanceTest}）。
 *
 * <p>浏览器无关：用脚本化假通道 + 假 harness，在隔离靶场语义下验证受限 DSL 的<b>五类门禁</b>
 * （§9 P0 第 7 项 / §10.4 红线矩阵）：① 正向路径 ② 策略拒绝 ③ 超时/资源上界 ④ 非幂等操作不重试且失败即停止
 * ⑤ 敏感 canary 不泄漏。所有断言走真实生产组件（{@link StepPlan#parse} / 参数范围门禁 /
 * {@link DslAcceptanceRunner} / {@link SnapshotVerifier} / {@link AriaSnapshotParser}），不 mock 掉被验行为。</p>
 */
public class AgentDriverDslAcceptanceTest {

    private static final String PAGE = "file:///fixture.html";
    private static final String USER = "role=textbox;name=Username";
    private static final String SUBMIT = "role=button;name=Submit";
    private static final String CANARY = "CANARY_SECRET_9f3b21";

    /** 合法 DSL：NAVIGATE → FILL → CLICK，参数均声明在 scope 内（治理预检可通过）。 */
    private static String authorizedDsl() {
        return "{"
                + "\"schemaVersion\":1,\"planId\":\"plan-dsl-acceptance\","
                + "\"scope\":{\"url\":{\"allow\":[\"" + PAGE + "\"]},"
                + "           \"value\":{\"allow\":[\"alice\"]}},"
                + "\"steps\":["
                + " {\"action\":\"NAVIGATE\",\"params\":{\"url\":\"" + PAGE + "\"}},"
                + " {\"action\":\"FILL\",\"target\":\"" + USER + "\",\"params\":{\"value\":\"alice\"}},"
                + " {\"action\":\"CLICK\",\"target\":\"" + SUBMIT + "\"}"
                + "]}";
    }

    private static Snapshot completeSnapshot() {
        return new Snapshot(PAGE, List.of(
                new Snapshot.ElementState(USER, "textbox", "Username", true, true, ""),
                new Snapshot.ElementState(SUBMIT, "button", "Submit", true, true, "")),
                SnapshotEnvelope.complete("ctx-a", "page-a", "main", 1L, 2, 64));
    }

    /** 脚本化假通道：按步骤序号决定返回 ok/fail，记录每个动作的执行次数。 */
    private static final class ScriptedChannel implements BrowserCommandChannel {
        final List<Command> executed = new ArrayList<>();
        final int failAt; // 1-based，-1 = 不失败
        final String failMessage;
        final Snapshot snapshot;

        ScriptedChannel(int failAt, String failMessage, Snapshot snapshot) {
            this.failAt = failAt;
            this.failMessage = failMessage;
            this.snapshot = snapshot;
        }

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public CommandResult execute(Command command) {
            executed.add(command);
            if (executed.size() == failAt) {
                return CommandResult.fail(failMessage);
            }
            return CommandResult.ok(snapshot);
        }

        long count(Command.ActionType action) {
            return executed.stream().filter(c -> c.action() == action).count();
        }
    }

    // ① 正向路径 ----------------------------------------------------------

    @Test
    public void authorizedDslExecutesAllStepsAndPassesAssertions() {
        ScriptedChannel channel = new ScriptedChannel(-1, null, completeSnapshot());
        DslAcceptanceRunner.Outcome outcome = DslAcceptanceRunner.run(StepPlan.parse(authorizedDsl()), channel);

        assertFalse(outcome.stopped());
        assertEquals(3, outcome.executedSteps());
        assertEquals(1, channel.count(Command.ActionType.NAVIGATE));
        assertEquals(1, channel.count(Command.ActionType.FILL));
        assertEquals(1, channel.count(Command.ActionType.CLICK));
        SnapshotVerificationResult r = SnapshotVerifier.verify(
                outcome.results().get(2).snapshot(),
                List.of(SnapshotExpectation.forRef(SUBMIT).exists(true).enabled(true).build()));
        assertTrue("正向路径断言须通过", r.allPassed());
    }

    // ② 策略拒绝 ----------------------------------------------------------

    @Test
    public void unknownFieldAndUnknownActionAreRejectedAtParse() {
        expectIllegal(authorizedDsl().replace("\"steps\":[", "\"evil\":1,\"steps\":["));
        expectIllegal(authorizedDsl().replace("\"action\":\"CLICK\"", "\"action\":\"EXEC\""));
    }

    @Test
    public void outOfScopeValueIsRejectedBeforeAnySideEffect() {
        String outOfScope = authorizedDsl().replace("\"value\":\"alice\"", "\"value\":\"bob\"");
        ScriptedChannel channel = new ScriptedChannel(-1, null, completeSnapshot());
        try {
            DslAcceptanceRunner.run(StepPlan.parse(outOfScope), channel);
            fail("expected ParamScopeViolationException for out-of-scope value");
        } catch (ParamScopeViolationException expected) {
            // 门禁是逐步的：越界步之前的已授权步骤（NAVIGATE）合法执行，
            // 但越界步本身与其后的副作用动作一律不得执行。
            assertEquals(1, channel.executed.size());
            assertEquals(Command.ActionType.NAVIGATE, channel.executed.get(0).action());
            assertEquals("越界的 FILL 不得执行", 0, channel.count(Command.ActionType.FILL));
            assertEquals("越界步之后的 CLICK 不得执行", 0, channel.count(Command.ActionType.CLICK));
        }
    }

    @Test
    public void nonSandboxTargetIsRejectedBeforeBrowserStarts() {
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "");
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            // 无需真实浏览器：拒绝发生在 Playwright.create() 之前
            harness.acquire("https://prod.example.com/login");
            fail("expected TestBedTargetRejectedException");
        } catch (TestBedTargetRejectedException expected) {
            assertTrue(expected.getMessage().contains("pw.agent.testbed.allowedHosts"));
        } finally {
            harness.closeAll();
            System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "");
        }
    }

    @Test
    public void unsupportedSnapshotFailsClosedAtVerification() {
        Snapshot s = new Snapshot(PAGE, List.of(),
                SnapshotEnvelope.unsupported("c", "p", "main", 1L, "ARIA_COLLECTION_FAILED"));
        SnapshotVerificationResult r = SnapshotVerifier.verify(s,
                List.of(SnapshotExpectation.forRef(SUBMIT).exists(false).build()));
        assertFalse("采集不支持时不得把「未观测」当作「不存在」", r.allPassed());
        assertTrue(r.failures().get(0).getMessage().contains("absence not proven"));
    }

    // ③ 超时 / 资源上界 ----------------------------------------------------

    @Test
    public void snapshotDeadlineTruncatesAndFailsClosed() {
        AtomicInteger calls = new AtomicInteger();
        LongSupplier clock = () -> calls.getAndIncrement() == 0 ? 0L : 2_000_000L;
        AriaSnapshotParser.Result parsed = AriaSnapshotParser.parse(
                "- button \"Submit\"\n- textbox \"Username\"\n", PAGE,
                "ctx-a", "page-a", "main", 1L,
                new SnapshotBounds(32, 1000, 100_000, 1), clock);
        assertTrue(parsed.truncated());
        assertEquals("TIMEOUT(1ms)", parsed.truncationReason());
        assertFalse(parsed.snapshot().isComplete());

        SnapshotVerificationResult r = SnapshotVerifier.verify(parsed.snapshot(),
                List.of(SnapshotExpectation.forRef(SUBMIT).exists(true).build()));
        assertFalse("超时截断的快照不得让断言伪造通过", r.allPassed());
        assertTrue(r.failures().get(0).getMessage().contains("TIMEOUT"));
    }

    // ④ 非幂等操作不重试 / 失败即停止 --------------------------------------

    @Test
    public void failedSideEffectIsNotRetriedAndSubsequentActionsAreSkipped() {
        // FILL（第 2 步，副作用）失败 → 不得重试该步，也不得执行后续 CLICK
        ScriptedChannel channel = new ScriptedChannel(2, "FILL failed", completeSnapshot());
        DslAcceptanceRunner.Outcome outcome = DslAcceptanceRunner.run(StepPlan.parse(authorizedDsl()), channel);

        assertTrue(outcome.stopped());
        assertEquals(2, outcome.executedSteps());
        assertEquals("非幂等写操作只执行一次，绝不重试", 1, channel.count(Command.ActionType.FILL));
        assertEquals("失败后不得继续执行后续副作用动作", 0, channel.count(Command.ActionType.CLICK));
        assertEquals("FILL failed", outcome.diagnostics());
    }

    // ⑤ 敏感 canary 不泄漏 --------------------------------------------------

    @Test
    public void sensitiveCanaryNeverLeaksIntoDiagnostics() {
        String leaky = "FILL failed: password=" + CANARY + " Authorization: Bearer " + CANARY;
        ScriptedChannel channel = new ScriptedChannel(2, leaky, completeSnapshot());
        DslAcceptanceRunner.Outcome outcome = DslAcceptanceRunner.run(StepPlan.parse(authorizedDsl()), channel);

        assertTrue(outcome.stopped());
        assertNotNull(outcome.diagnostics());
        assertFalse("canary 不得出现在验收诊断中", outcome.diagnostics().contains(CANARY));
        assertFalse("canary 不得出现在 CommandResult.message 中",
                outcome.results().get(1).message().contains(CANARY));
        assertTrue("诊断须保留可审计的非敏感上下文",
                outcome.diagnostics().contains("FILL failed") || outcome.diagnostics().contains("password"));
    }

    private static void expectIllegal(String json) {
        try {
            StepPlan.parse(json);
            fail("expected IllegalArgumentException for: " + json);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }
}

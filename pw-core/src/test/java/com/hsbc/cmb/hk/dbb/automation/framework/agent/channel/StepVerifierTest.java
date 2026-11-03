package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StepVerifierTest {

    private static final String USER = "role=textbox;name=username";

    private static StepPlan fillPlan(String value, ParamScope scope) {
        Command fill = new Command(Command.ActionType.FILL, LocatorSpec.parse(USER), Map.of("value", value));
        return new StepPlan(1, "p1", List.of(fill), scope, List.of());
    }

    private static ParamScope allowAlice() {
        return new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice")));
    }

    private static Snapshot snapWith(String text) {
        Snapshot.ElementState el = new Snapshot.ElementState(USER, "textbox", "username", true, true, text);
        return new Snapshot("page", List.of(el));
    }

    private static List<List<SnapshotExpectation>> expectText(String text) {
        return List.of(List.of(
                SnapshotExpectation.forRef(USER).text(text).role("textbox").build()));
    }

    /** 授权执行 + 期望匹配 → 整体通过。 */
    @Test
    public void authorizedExecutionWithMatchingExpectationPasses() {
        StepPlan plan = fillPlan("alice", allowAlice());
        BrowserCommandChannel channel = new FakeChannel(snapWith("alice"));
        StepVerificationResult r = StepVerifier.verify(plan, channel, expectText("alice"));
        assertTrue(r.allPassed());
        assertEquals(1, r.steps());
        assertEquals(1, r.passedSteps());
        assertEquals(1, r.checkedExpectations());
        assertEquals(1, r.passedExpectations());
        assertTrue(r.allFailures().isEmpty());
    }

    /** 任一步参数越界 → 门禁 fail-fast 传播，不进入断言。 */
    @Test
    public void gateViolationPropagatesAndSkipsAssertion() {
        StepPlan plan = fillPlan("bob", allowAlice()); // bob 不在 allow 白名单
        BrowserCommandChannel channel = new FakeChannel(snapWith("alice"));
        assertThrows(ParamScopeViolationException.class,
                () -> StepVerifier.verify(plan, channel, expectText("alice")));
    }

    /** 期望与快照不符 → 整体失败，且失败明细带 expected/actual。 */
    @Test
    public void expectationMismatchFails() {
        StepPlan plan = fillPlan("alice", allowAlice());
        BrowserCommandChannel channel = new FakeChannel(snapWith("other"));
        StepVerificationResult r = StepVerifier.verify(plan, channel, expectText("alice"));
        assertFalse(r.allPassed());
        assertEquals(0, r.passedExpectations());
        assertEquals(1, r.allFailures().size());
        assertEquals("alice", r.allFailures().get(0).getExpected());
        assertEquals("other", r.allFailures().get(0).getActual());
    }

    /** 某步无期望 → 该步视为「无断言」，整体仍通过。 */
    @Test
    public void missingExpectationListForStepIsTriviallyPassing() {
        StepPlan plan = fillPlan("alice", allowAlice());
        BrowserCommandChannel channel = new FakeChannel(snapWith("alice"));
        StepVerificationResult r = StepVerifier.verify(plan, channel, List.of()); // 空伴随列表
        assertTrue(r.allPassed());
        assertEquals(1, r.steps());
        assertEquals(1, r.passedSteps());
        assertEquals(0, r.checkedExpectations());
    }

    /** 命令执行失败（无快照）→ 该步验证失败，带原因。 */
    @Test
    public void failedCommandWithoutSnapshotFailsVerification() {
        StepPlan plan = fillPlan("alice", allowAlice());
        BrowserCommandChannel channel = new FakeChannel(null, true); // CommandResult.fail → 无快照
        StepVerificationResult r = StepVerifier.verify(plan, channel, expectText("alice"));
        assertFalse(r.allPassed());
        assertEquals(1, r.checkedExpectations());
        assertEquals(0, r.passedExpectations());
        assertTrue(r.allFailures().get(0).getMessage().contains("no snapshot"));
    }

    /** 守卫：null 入参。 */
    @Test
    public void nullArgsRejected() {
        BrowserCommandChannel channel = new FakeChannel(snapWith("alice"));
        assertThrows(NullPointerException.class,
                () -> StepVerifier.verify(null, channel, expectText("alice")));
        assertThrows(NullPointerException.class,
                () -> StepVerifier.verify(fillPlan("alice", allowAlice()), null, expectText("alice")));
        assertThrows(NullPointerException.class,
                () -> StepVerifier.verify(fillPlan("alice", allowAlice()), channel, null));
    }

    /** 内存 fake 通道：execute 返回带快照/失败的结果，snapshot() 提供采集快照。 */
    private static final class FakeChannel implements BrowserCommandChannel {
        private final Snapshot snapshot;
        private final boolean failResult;

        FakeChannel(Snapshot snapshot) { this(snapshot, false); }
        FakeChannel(Snapshot snapshot, boolean failResult) {
            this.snapshot = snapshot;
            this.failResult = failResult;
        }

        @Override
        public Snapshot snapshot() { return snapshot; }

        @Override
        public CommandResult execute(Command command) {
            return failResult ? CommandResult.fail("boom") : CommandResult.ok(snapshot);
        }
    }
}

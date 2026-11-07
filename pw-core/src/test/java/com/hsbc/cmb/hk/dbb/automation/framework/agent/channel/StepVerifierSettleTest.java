package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * D3 稳定语义（I-03）门禁：验证 StepVerifier 在逐行走联动断言前，按配置决定是否先 settle + 重新取快照。
 *
 * <p>核心验收：① 窗口关闭（{@code <=0}）时直接用执行时快照，与改动前行为逐字一致；
 * ② 窗口开启时先 settle 再重新采集稳定态快照后断言；③ settle / 重新取快照失败一律回退执行时快照，
 * 辅助动作绝不拖垮断言。覆盖目标 = 闭环「同元素快速重复断言抖动」红线。</p>
 */
public class StepVerifierSettleTest {

    private static final String USER = "role=textbox;name=username";
    private static final String KEY = "agent.step.verify.settle.window.ms";

    @Before
    @After
    public void resetSettleConfig() {
        System.clearProperty(KEY);
    }

    /** 内存 stub 通道：区分「执行时快照」与「settle 后重新采集的稳定态快照」，并统计 settle/snapshot 调用。 */
    private static final class StubChannel implements BrowserCommandChannel {
        private final Snapshot execSnapshot;
        private final Snapshot stableSnapshot;
        private final boolean settleThrows;
        int settleCalls = 0;
        int snapshotCalls = 0;

        StubChannel(Snapshot execSnapshot, Snapshot stableSnapshot, boolean settleThrows) {
            this.execSnapshot = execSnapshot;
            this.stableSnapshot = stableSnapshot;
            this.settleThrows = settleThrows;
        }

        @Override
        public Snapshot snapshot() {
            snapshotCalls++;
            return stableSnapshot;
        }

        @Override
        public CommandResult execute(Command command) {
            return CommandResult.ok(execSnapshot);
        }

        @Override
        public void settle(long windowMs) {
            settleCalls++;
            if (settleThrows) {
                throw new RuntimeException("settle boom");
            }
        }
    }

    private static Snapshot snapWith(String role, String text) {
        Snapshot.ElementState el = new Snapshot.ElementState(USER, role, "username", true, true, text);
        return new Snapshot("page", List.of(el));
    }

    private static StepPlan fillPlan(String value) {
        Command fill = new Command(Command.ActionType.FILL, LocatorSpec.parse(USER), Map.of("value", value));
        return new StepPlan(1, "p1", List.of(fill),
                new ParamScope(Map.of("value", ParamConstraint.allowedValues(value))), List.of());
    }

    private static List<List<SnapshotExpectation>> expectRoleText(String role, String text) {
        return List.of(List.of(SnapshotExpectation.forRef(USER).role(role).text(text).build()));
    }

    /** 窗口关闭：直接复用执行时快照，不调 settle、不重新取快照，与改动前行为逐字一致。 */
    @Test
    public void windowClosedUsesExecutionTimeSnapshotVerbatim() {
        System.setProperty(KEY, "0");
        Snapshot exec = snapWith("textbox", "alice");
        StubChannel ch = new StubChannel(exec, snapWith("link", "alice"), false);
        StepVerificationResult r = StepVerifier.verify(fillPlan("alice"), ch, expectRoleText("textbox", "alice"));
        assertEquals(0, ch.settleCalls);
        assertEquals(0, ch.snapshotCalls);
        assertTrue(r.allPassed()); // 用执行时快照（role=textbox 匹配）
    }

    /** 窗口开启（默认 500ms）：先 settle 再重新采集快照后断言，验证用的是稳定态快照而非执行时快照。 */
    @Test
    public void windowOpenReSnapshotsAfterSettle() {
        // 不设属性 → 走默认 500ms（开启）
        Snapshot exec = snapWith("textbox", "alice");
        StubChannel ch = new StubChannel(exec, snapWith("link", "alice"), false);
        StepVerificationResult r = StepVerifier.verify(fillPlan("alice"), ch, expectRoleText("textbox", "alice"));
        assertEquals(1, ch.settleCalls);
        assertEquals(1, ch.snapshotCalls);
        assertFalse(r.allPassed()); // 用稳定态快照（role=link 不匹配期望 textbox）
        assertEquals(1, r.allFailures().size());
        AssertionFailure f = r.allFailures().get(0);
        assertEquals("textbox", f.getExpected());
        assertEquals("link", f.getActual());
    }

    /** settle 抛异常：回退执行时快照，未重新取快照，断言仍正常完成（辅助动作绝不拖垮断言）。 */
    @Test
    public void settleFailureFallsBackToExecutionTimeSnapshot() {
        Snapshot exec = snapWith("textbox", "alice");
        StubChannel ch = new StubChannel(exec, snapWith("link", "alice"), true);
        StepVerificationResult r = StepVerifier.verify(fillPlan("alice"), ch, expectRoleText("textbox", "alice"));
        assertEquals(1, ch.settleCalls);
        assertEquals(0, ch.snapshotCalls); // 异常后未重新取快照
        assertTrue(r.allPassed()); // 回退执行时快照（role=textbox 匹配）
    }
}

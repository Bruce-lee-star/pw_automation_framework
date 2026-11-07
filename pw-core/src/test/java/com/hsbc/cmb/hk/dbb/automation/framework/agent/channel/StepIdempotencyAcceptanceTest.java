package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** T7 验收：同计划重跑跳过 N-1 步、仅跑变更步；默认 none() 不跳过；失败输出 diff。 */
public class StepIdempotencyAcceptanceTest {

    private static final LocatorSpec A = LocatorSpec.parse("role=button;name=A");
    private static final LocatorSpec B = LocatorSpec.parse("role=button;name=B");
    private static final LocatorSpec C = LocatorSpec.parse("role=button;name=C");

    private static final Snapshot SAMPLE_SNAPSHOT = new Snapshot("page", List.of(
            new Snapshot.ElementState("role=button;name=A", "button", "A", true, true, "")));

    /** 记录真实执行的命令（用于断言「跳过 / 重跑」步数）。 */
    private static final class RecordingChannel implements BrowserCommandChannel {
        final List<Command> executed = new ArrayList<>();
        @Override public Snapshot snapshot() { return null; }
        @Override public CommandResult execute(Command command) {
            executed.add(command);
            return CommandResult.ok(SAMPLE_SNAPSHOT);
        }
    }

    private static StepPlan plan(List<Command> cmds) {
        List<ParamScope> scopes = new ArrayList<>();
        for (int i = 0; i < cmds.size(); i++) {
            scopes.add(ParamScope.EMPTY);
        }
        return new StepPlan(1, "p", cmds, ParamScope.EMPTY, scopes);
    }

    @Test
    public void identicalRerunSkipsAllSteps() {
        InMemoryStepIdempotency idem = new InMemoryStepIdempotency();
        RecordingChannel ch = new RecordingChannel();
        StepPlan p = plan(List.of(Command.click(A), Command.click(B), Command.click(C)));
        StepDriver.execute(p, ch, idem);
        assertEquals(3, ch.executed.size());
        ch.executed.clear();
        // 同计划重跑：全部命中缓存，0 次通道调用
        StepDriver.execute(p, ch, idem);
        assertEquals("同计划重跑应全部跳过", 0, ch.executed.size());
    }

    @Test
    public void changedStepRerunExecutesOnlyTheChangedStep() {
        InMemoryStepIdempotency idem = new InMemoryStepIdempotency();
        RecordingChannel ch = new RecordingChannel();
        StepPlan p = plan(List.of(Command.click(A), Command.click(B), Command.click(C)));
        StepDriver.execute(p, ch, idem); // 记录全部
        ch.executed.clear();
        // 仅第 1 步（索引 1）target 由 B 变为 C → 仅该步重跑，其余 N-1 跳过
        StepPlan pPrime = plan(List.of(Command.click(A), Command.click(C), Command.click(C)));
        StepDriver.execute(pPrime, ch, idem);
        assertEquals("仅变更步应被执行", 1, ch.executed.size());
        assertEquals("role=button;name=C", ch.executed.get(0).targetRef());
    }

    @Test
    public void defaultNoneDoesNotSkipOnRerun() {
        RecordingChannel ch = new RecordingChannel();
        StepPlan p = plan(List.of(Command.click(A), Command.click(B)));
        StepDriver.execute(p, ch); // 等价 none()
        assertEquals(2, ch.executed.size());
        ch.executed.clear();
        StepDriver.execute(p, ch); // 无幂等 → 仍然全部执行
        assertEquals(2, ch.executed.size());
    }

    @Test
    public void verifyPathSkipsAndStillPasses() {
        InMemoryStepIdempotency idem = new InMemoryStepIdempotency();
        RecordingChannel ch = new RecordingChannel();
        StepPlan p = plan(List.of(Command.click(A), Command.click(B)));
        StepVerifier.verify(p, ch, List.of(List.of(), List.of()), idem);
        assertEquals(2, ch.executed.size());
        ch.executed.clear();
        StepVerificationResult rerun = StepVerifier.verify(p, ch, List.of(List.of(), List.of()), idem);
        assertEquals(0, ch.executed.size());
        assertTrue(rerun.allPassed());
        assertEquals(2, rerun.passedSteps());
    }

    @Test
    public void diffShowsExpectedVsActualOnFailure() {
        BrowserCommandChannel failing = new BrowserCommandChannel() {
            @Override public Snapshot snapshot() { return null; }
            @Override public CommandResult execute(Command command) {
                return CommandResult.fail("boom");
            }
        };
        StepPlan p = plan(List.of(Command.click(A)));
        StepVerificationResult r = StepVerifier.verify(p, failing, List.of(List.of()), StepIdempotency.none());
        assertFalse(r.allPassed());
        assertTrue("diff 应暴露失败明细", r.diff().contains("FAIL"));
    }
}

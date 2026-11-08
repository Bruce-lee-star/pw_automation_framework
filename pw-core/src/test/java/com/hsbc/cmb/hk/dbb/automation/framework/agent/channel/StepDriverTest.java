package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StepDriverTest {

    private static final LocatorSpec USER = LocatorSpec.parse("role=textbox;name=username");
    private static final LocatorSpec SUBMIT = LocatorSpec.parse("role=button;name=Submit");

    /** 最小通道：记录被真正执行的命令，返回成功结果（snapshot 留空）。 */
    private static final class RecordingChannel implements BrowserCommandChannel {
        final List<Command> executed = new ArrayList<>();

        @Override
        public Snapshot snapshot() {
            return null;
        }

        @Override
        public CommandResult execute(Command command) {
            executed.add(command);
            return CommandResult.ok(null);
        }
    }

    @Test
    public void executesAllStepsWithResolvedScope() {
        StepPlan plan = new StepPlan(1, "p", List.of(
                Command.fill(USER, "alice"),
                Command.click(SUBMIT)),
                new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice", "bob"))),
                List.of(ParamScope.EMPTY, ParamScope.EMPTY));
        RecordingChannel ch = new RecordingChannel();
        List<CommandResult> results = StepDriver.execute(plan, ch);
        assertEquals(2, results.size());
        assertTrue(results.get(0).success());
        assertEquals(2, ch.executed.size());
    }

    @Test
    public void stepScopeOverridesPlanScopeAtBoundary() {
        // plan 级只允许 alice，但 step 级允许 carol → 该步应被放行（边界用 resolvedScope）
        StepPlan plan = new StepPlan(1, "p", List.of(Command.fill(USER, "carol")),
                new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice"))),
                List.of(new ParamScope(Map.of("value", ParamConstraint.allowedValues("carol")))));
        RecordingChannel ch = new RecordingChannel();
        StepDriver.execute(plan, ch);
        assertEquals(1, ch.executed.size());
    }

    @Test
    public void gateViolationFailsFastAndDoesNotExecute() {
        StepPlan plan = new StepPlan(1, "p", List.of(
                Command.fill(USER, "carol"),
                Command.click(SUBMIT)),
                new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice"))),
                List.of(ParamScope.EMPTY, ParamScope.EMPTY));
        RecordingChannel ch = new RecordingChannel();
        try {
            StepDriver.execute(plan, ch);
            fail("expected ParamScopeViolationException");
        } catch (ParamScopeViolationException expected) {
            // fail-fast：第一步越界即停，第二步不应执行
        }
        assertEquals(0, ch.executed.size());
    }

    @Test
    public void governancePreFlightRejectsUndeclaredSideEffect() {
        // FILL.value 未声明 → 治理预检失败，尚未执行任何步
        StepPlan plan = new StepPlan(1, "p", List.of(Command.fill(USER, "alice")),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        RecordingChannel ch = new RecordingChannel();
        try {
            StepDriver.execute(plan, ch);
            fail("expected ParamScopeViolationException");
        } catch (ParamScopeViolationException expected) {
            // 治理预检（副作用动作须声明 scope）
        }
        assertEquals(0, ch.executed.size());
    }

    @Test
    public void nullArgsRejected() {
        assertThrowsNpe(() -> StepDriver.execute(null, new RecordingChannel()));
        assertThrowsNpe(() -> StepDriver.execute(new StepPlan(1, "p", List.of(Command.click(SUBMIT)),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY)), null));
    }

    private static void assertThrowsNpe(ThrowingRunnable r) {
        org.junit.Assert.assertThrows(NullPointerException.class, r::run);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}

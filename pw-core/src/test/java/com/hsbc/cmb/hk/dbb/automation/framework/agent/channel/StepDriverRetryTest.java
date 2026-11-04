package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * StepDriver 幂等重试接线（v3.10）：<b>只对幂等动作应用有限重试</b>，写操作一次失败即停。
 * 与 DslAcceptanceRunner 的「失败即停止」语义相互独立——后者是验收编排，本类保留「收集全部结果」。
 */
public class StepDriverRetryTest {

    private static final LocatorSpec SUBMIT = LocatorSpec.parse("role=button;name=Submit");

    /** 假通道：对某 idempotent 动作在第 succeedAtCall 次调用时成功，其余调用恒失败。 */
    private static final class ScriptedChannel implements BrowserCommandChannel {
        int calls = 0;
        final Command.ActionType retried;
        final int succeedAtCall;

        ScriptedChannel(Command.ActionType retried, int succeedAtCall) {
            this.retried = retried;
            this.succeedAtCall = succeedAtCall;
        }

        @Override
        public Snapshot snapshot() {
            return null;
        }

        @Override
        public CommandResult execute(Command c) {
            calls++;
            if (c.action() == retried && calls >= succeedAtCall) {
                return CommandResult.ok(null);
            }
            return CommandResult.fail("attempt-" + calls);
        }
    }

    @Test
    public void idempotentActionRetriesUpToMaxAndSucceeds() {
        // WAIT：幂等且 maxRetries=1 → 失败1次后重试1次（共 2 次调用）成功
        ScriptedChannel ch = new ScriptedChannel(Command.ActionType.WAIT, 2);
        StepPlan plan = new StepPlan(1, "p",
                List.of(new Command(Command.ActionType.WAIT, null, Map.of())),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        List<CommandResult> results = StepDriver.execute(plan, ch);
        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals(2, ch.calls);
    }

    @Test
    public void idempotentActionExhaustsRetriesAndFails() {
        // WAIT：恒失败，maxRetries=1 → 初始1次 + 重试1次 = 2 次调用，仍失败
        ScriptedChannel ch = new ScriptedChannel(Command.ActionType.WAIT, 99);
        StepPlan plan = new StepPlan(1, "p",
                List.of(new Command(Command.ActionType.WAIT, null, Map.of())),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        List<CommandResult> results = StepDriver.execute(plan, ch);
        assertFalse(results.get(0).success());
        assertEquals(2, ch.calls);
    }

    @Test
    public void nonIdempotentActionIsNotRetried() {
        // CLICK：idempotent=false、maxRetries=0 → 失败即停，仅 1 次调用
        ScriptedChannel ch = new ScriptedChannel(Command.ActionType.CLICK, 99);
        StepPlan plan = new StepPlan(1, "p",
                List.of(Command.click(SUBMIT)),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));
        List<CommandResult> results = StepDriver.execute(plan, ch);
        assertFalse(results.get(0).success());
        assertEquals(1, ch.calls);
    }
}

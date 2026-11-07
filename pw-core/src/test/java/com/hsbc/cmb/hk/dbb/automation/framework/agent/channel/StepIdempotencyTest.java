package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** T7：InMemoryStepIdempotency 命令内容指纹记忆逻辑。 */
public class StepIdempotencyTest {

    private static final LocatorSpec A = LocatorSpec.parse("role=button;name=A");
    private static final LocatorSpec B = LocatorSpec.parse("role=button;name=B");

    private static StepPlan planOf(Command... cmds) {
        List<Command> list = new ArrayList<>(Arrays.asList(cmds));
        List<ParamScope> scopes = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            scopes.add(ParamScope.EMPTY);
        }
        return new StepPlan(1, "p", list, ParamScope.EMPTY, scopes);
    }

    @Test
    public void cachedPassReturnsNullBeforeRecord() {
        InMemoryStepIdempotency idem = new InMemoryStepIdempotency();
        StepPlan plan = planOf(Command.click(A));
        assertNull(idem.cachedPass(plan, 0, plan.steps().get(0), plan.resolvedScope(0)));
    }

    @Test
    public void recordsPassAndSkipsIdenticalCommand() {
        InMemoryStepIdempotency idem = new InMemoryStepIdempotency();
        StepPlan plan = planOf(Command.click(A));
        Command cmd = plan.steps().get(0);
        idem.recordPass(plan, 0, cmd, plan.resolvedScope(0), "digest-xyz");
        CommandResult cached = idem.cachedPass(plan, 0, cmd, plan.resolvedScope(0));
        assertNotNull(cached);
        assertTrue(cached.success());
        assertEquals("digest-xyz", cached.snapshotDigest());
    }

    @Test
    public void changedTargetForcesReexecution() {
        InMemoryStepIdempotency idem = new InMemoryStepIdempotency();
        StepPlan plan = planOf(Command.click(A));
        idem.recordPass(plan, 0, plan.steps().get(0), plan.resolvedScope(0), "d1");
        // 同计划但命令换了 target → 命令指纹变化 → 不能跳过
        StepPlan changed = planOf(Command.click(B));
        assertNull(idem.cachedPass(changed, 0, changed.steps().get(0), changed.resolvedScope(0)));
    }

    @Test
    public void changedScopeForcesReexecution() {
        InMemoryStepIdempotency idem = new InMemoryStepIdempotency();
        ParamScope s1 = new ParamScope(Map.of("value", ParamConstraint.allowedValues("alice")));
        ParamScope s2 = new ParamScope(Map.of("value", ParamConstraint.allowedValues("bob")));
        StepPlan plan = new StepPlan(1, "p", List.of(Command.fill(A, "alice")), s1, List.of(s1));
        idem.recordPass(plan, 0, plan.steps().get(0), plan.resolvedScope(0), "d");
        // 同命令但 plan 级 scope 变了 → resolvedScope 不同 → 命令指纹变化 → 不能跳过
        StepPlan plan2 = new StepPlan(1, "p", List.of(Command.fill(A, "alice")), s2, List.of(s2));
        assertNull(idem.cachedPass(plan2, 0, plan2.steps().get(0), plan2.resolvedScope(0)));
    }
}

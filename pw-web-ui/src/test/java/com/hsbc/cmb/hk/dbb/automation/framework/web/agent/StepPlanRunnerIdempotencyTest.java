package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.InMemoryStepIdempotency;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepIdempotency;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** T7：StepPlanRunner 接入幂等上下文（端到端，无浏览器）。 */
public class StepPlanRunnerIdempotencyTest {

    private static final LocatorSpec A = LocatorSpec.parse("role=button;name=A");
    private static final LocatorSpec B = LocatorSpec.parse("role=button;name=B");
    private static final LocatorSpec C = LocatorSpec.parse("role=button;name=C");

    private static final Snapshot SNAP = new Snapshot("page", List.of(
            new Snapshot.ElementState("role=button;name=A", "button", "A", true, true, "")));

    private static final class RecordingChannel implements BrowserCommandChannel {
        final List<Command> executed = new ArrayList<>();
        @Override public Snapshot snapshot() { return null; }
        @Override public CommandResult execute(Command command) {
            executed.add(command);
            return CommandResult.ok(SNAP);
        }
    }

    private static final class FakeHarness implements IsolationHarness {
        final RecordingChannel channel = new RecordingChannel();
        @Override public BrowserCommandChannel acquire(String targetUrl) { return channel; }
        @Override public void release(BrowserCommandChannel c) { }
    }

    private static StepPlan plan(List<Command> cmds) {
        List<ParamScope> scopes = new ArrayList<>();
        for (int i = 0; i < cmds.size(); i++) {
            scopes.add(ParamScope.EMPTY);
        }
        return new StepPlan(1, "p", cmds, ParamScope.EMPTY, scopes);
    }

    @Test
    public void runnerWithIdempotencySkipsRerun() {
        FakeHarness harness = new FakeHarness();
        StepPlanRunner runner = new StepPlanRunner(harness, new InMemoryStepIdempotency());
        StepPlan p = plan(List.of(Command.click(A), Command.click(B), Command.click(C)));
        runner.run(p, List.of(List.of(), List.of(), List.of()), "url");
        assertEquals(3, harness.channel.executed.size());
        harness.channel.executed.clear();
        StepVerificationResult rerun = runner.run(p, List.of(List.of(), List.of(), List.of()), "url");
        assertEquals(0, harness.channel.executed.size());
        assertTrue(rerun.allPassed());
    }

    @Test
    public void runnerWithoutIdempotencyRerunsAll() {
        FakeHarness harness = new FakeHarness();
        StepPlanRunner runner = new StepPlanRunner(harness); // 默认 none()
        StepPlan p = plan(List.of(Command.click(A), Command.click(B)));
        runner.run(p, List.of(List.of(), List.of()), "url");
        assertEquals(2, harness.channel.executed.size());
        harness.channel.executed.clear();
        runner.run(p, List.of(List.of(), List.of()), "url");
        assertEquals("未配置幂等应每次都执行", 2, harness.channel.executed.size());
    }
}

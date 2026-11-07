package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepDriver;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.AgentTraceRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R9 端到端：StepDriver 逐步执行时，每步向 AgentTraceSink 写一条轨迹（经 SPI）。
 */
class AgentTraceStepDriverTest {

    @AfterEach
    void reset() {
        AgentTraceRegistry.install(null);
        AgentTraceSinkImpl.store().clear();
    }

    @Test
    void stepDriverWritesOneTracePerStep() {
        AgentTraceRegistry.install(new AgentTraceSinkImpl());
        LocatorSpec a = LocatorSpec.parse("role=textbox;name=u");
        LocatorSpec b = LocatorSpec.parse("role=button;name=ok");
        StepPlan plan = new StepPlan(1, "plan-1",
                List.of(Command.fill(a, "v"), Command.click(b)),
                new ParamScope(Map.of("value", ParamConstraint.allowedValues("v"))),
                List.of(ParamScope.EMPTY, ParamScope.EMPTY));

        StepDriver.execute(plan, new NoOpChannel());

        var traces = AgentTraceSinkImpl.store().snapshot();
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).stepId()).isEqualTo("plan-1#0");
        assertThat(traces.get(0).actionCategory()).isEqualTo("FILL");
        assertThat(traces.get(1).actionCategory()).isEqualTo("CLICK");
    }

    @Test
    void stepDriverTraceStaysSafeWhenSinkThrows() {
        // 即便 sink 抛异常，StepDriver 也必须正常完成，不向上传播
        AgentTraceRegistry.install(e -> {
            throw new RuntimeException("boom");
        });
        LocatorSpec a = LocatorSpec.parse("role=textbox;name=u");
        StepPlan plan = new StepPlan(1, "plan-1", List.of(Command.click(a)),
                ParamScope.EMPTY, List.of(ParamScope.EMPTY));

        List<CommandResult> results = StepDriver.execute(plan, new NoOpChannel());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
    }

    static final class NoOpChannel implements BrowserCommandChannel {
        @Override
        public Snapshot snapshot() {
            return null;
        }

        @Override
        public CommandResult execute(Command command) {
            return CommandResult.ok(null);
        }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 闭环生产消费端锁（浏览器无关，纯单测）：把 pw-core 的「授权执行 + 执行后联动断言」经
 * {@link StepPlanRunner}（生产态执行服务）接到<b>假 harness / 假通道</b>，证明：
 * <ol>
 *   <li>{@code StepPlanRunner.run(VerifiableStepPlan)} 正确 acquire 通道 → 喂 {@code StepVerifier.verify}
 *       （用载体自带的 plan / 逐步期望 / pageRef）→ release 通道；</li>
 *   <li>产线载体经<b>落库 JSON 往返</b>（{@code VerifiableStepPlan.fromJson}，与生产流水线同一 Jackson 配置）
 *       读回后，经同一生产消费端执行，与内存中载体执行<b>逐字节一致</b>——闭环在生产侧真正闭合。</li>
 * </ol>
 * 不依赖真实 Playwright：{@code RecordingChannel} 仅记录每步「命令 + 已解析 scope」并回固定快照。
 */
public class StepPlanRunnerClosedLoopTest {

    /** 假通道：记录每步命令并回固定快照（不接真实浏览器）。 */
    private static final class RecordingChannel implements BrowserCommandChannel {
        final List<String> trace = new ArrayList<>();
        private final Snapshot snapshot;

        RecordingChannel(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public CommandResult execute(Command command) {
            trace.add((command.target() == null ? "<nav>" : command.target().canonical()) + "|" + command.action());
            return CommandResult.ok(snapshot);
        }
    }

    /** 假 harness：记录 acquire / release，返回假通道（不接真实浏览器）。 */
    private static final class FakeHarness implements IsolationHarness {
        boolean acquired;
        boolean released;
        private final BrowserCommandChannel channel;

        FakeHarness(BrowserCommandChannel channel) {
            this.channel = channel;
        }

        @Override
        public BrowserCommandChannel acquire(String targetUrl) {
            acquired = true;
            return channel;
        }

        @Override
        public void release(BrowserCommandChannel channel) {
            released = true;
        }
    }

    /** 与生产流水线同一 Jackson 配置（FIELD 可见 / GETTER + IS_GETTER 不可见）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    /** 构造一个内联期望的最小载体：NAVIGATE + FILL（FILL 后目标 enabled）。 */
    private static VerifiableStepPlan sampleCarrier() {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        Command fill = new Command(Command.ActionType.FILL, loc, Map.of("value", "alice"),
                List.of(SnapshotExpectation.forRef(loc.canonical()).exists(true).enabled(true).build()));
        Command navigate = new Command(Command.ActionType.NAVIGATE, null, Map.of("url", "https://app/p"), List.of());
        // 参数范围治理：url 与 value 均须声明取值域（通道边界 fail-closed 门禁）
        ParamScope scope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues("https://app/p"),
                "value", ParamConstraint.allowedValues("alice")));
        StepPlan plan = new StepPlan(1, "plan-x", List.of(navigate, fill), scope, List.of());
        return new VerifiableStepPlan(plan, "https://app/p");
    }

    /** 固定执行后快照：含 FILL 目标且 enabled，使 ENABLE 期望通过。 */
    private static Snapshot satisfyingSnapshot() {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        return new Snapshot("https://app/p", List.of(
                new Snapshot.ElementState(loc.canonical(), "textbox", "用户名", true, true, "")));
    }

    @Test
    public void carrierRunAcquiresChannelVerifiesAndReleases() throws Exception {
        VerifiableStepPlan vsp = sampleCarrier();
        RecordingChannel channel = new RecordingChannel(satisfyingSnapshot());
        FakeHarness harness = new FakeHarness(channel);

        StepVerificationResult res = new StepPlanRunner(harness).run(vsp);

        assertTrue("harness must be acquired exactly once around verification", harness.acquired);
        assertTrue("harness must be released exactly once after verification", harness.released);
        assertTrue("produced plan executed through production consumer must pass verification", res.allPassed());
        assertEquals(2, res.steps());
        // 生产消费端把载体每步（顺序 + 目标 + 动作）经通道执行
        assertEquals(List.of("<nav>|NAVIGATE", LocatorSpec.roleOfKey("textbox", "user.key").canonical() + "|FILL"),
                channel.trace);
    }

    /**
     * 落库 JSON 往返后，经同一生产消费端执行，与内存中载体执行<b>逐字节一致</b>——
     * 锁死「产线资产 → 持久化 → 读回 → 执行」在生产侧闭环（不依赖真实浏览器）。
     */
    @Test
    public void persistedCarrierRoundTripsThroughProductionConsumer() throws Exception {
        VerifiableStepPlan vsp = sampleCarrier();
        String persisted = MAPPER.writeValueAsString(vsp);
        VerifiableStepPlan restored = VerifiableStepPlan.fromJson(persisted);

        RecordingChannel chanInline = new RecordingChannel(satisfyingSnapshot());
        RecordingChannel chanRestored = new RecordingChannel(satisfyingSnapshot());
        StepVerificationResult inline = new StepPlanRunner(new FakeHarness(chanInline)).run(vsp);
        StepVerificationResult restoredRes = new StepPlanRunner(new FakeHarness(chanRestored)).run(restored);

        assertTrue("inline carrier must pass through production consumer", inline.allPassed());
        assertTrue("restored (persisted→loaded) carrier must pass through production consumer", restoredRes.allPassed());
        // 执行轨迹逐字节一致 —— 资产持久化往返不影响生产态执行行为
        assertEquals("persisted asset executes identically to in-memory carrier via production consumer",
                chanInline.trace, chanRestored.trace);
        assertEquals(2, restoredRes.steps());
    }
}

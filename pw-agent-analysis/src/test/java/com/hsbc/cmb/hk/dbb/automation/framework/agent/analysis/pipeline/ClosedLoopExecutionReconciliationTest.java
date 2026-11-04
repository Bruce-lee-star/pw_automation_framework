package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.BehaviorModel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventory;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraphStepPlans;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeGate;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 闭环引擎<b>执行期</b>对账（设计 §4.6 路径 A 验证 / 路径 B 生产）。
 *
 * <p>{@code ClosedLoopPlanIsomorphismTest} 只证明两条构建路径产出的 {@link VerifiableStepPlan}
 * 在<b>结构</b>上同构；本测试进一步证明它们经<b>真实</b> {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepDriver}
 * （参数范围门禁执行）+ {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerifier}
 * （绝对态字段断言）跑出<b>完全一致的执行轨迹与验证结论</b>——即闭环在「行为」层面真正闭合，
 * 执行层对 {@code planId} 前缀（{@code plan-} / {@code linkage-}）无任何隐藏分支。</p>
 *
 * <p>浏览器无关：用 {@link RecordingChannel} 假通道记录每步「命令 + 已解析 scope」并返回固定快照，
 * 既避免拉入 Playwright，又能在断言里直接比对逐字节执行轨迹。</p>
 */
public class ClosedLoopExecutionReconciliationTest {

    private static final String PAGE = "https://app/p";
    private static final String REQ = "REQ-EXEC";

    /**
     * 与生产流水线 {@code AssetPipelineOrchestrator} 完全一致的序列化配置（FIELD 可见、GETTER 不可见），
     * 用于模拟 {@code STEP_PLAN} 资产落库 JSON → 反序列化回来这一持久化往返。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);

    @Test
    public void isomorphicPlansDriveIdenticalExecutionAndVerification() {
        LinkageGraph graph = buildGraph();
        NlsCatalog nls = new StubNls();

        VerifiableStepPlan producerVsp = new StepPlanProducer(nls).produce(buildBundle(graph));
        VerifiableStepPlan verifyVsp = LinkageGraphStepPlans.toVerifiableStepPlan(graph, PAGE, nls);

        Snapshot snapshot = satisfyingSnapshot(producerVsp);

        ExecResult prod = run(producerVsp, snapshot);
        ExecResult ver = run(verifyVsp, snapshot);

        // ① 执行轨迹逐字节一致（action|targetRef|排序params|已解析scope）—— 无隐藏 planId 分支
        assertEquals("execution trace must be identical across closed-loop paths",
                prod.trace, ver.trace);
        // ② 验证结论一致，且均在相同执行后快照下通过 —— 闭环真正闭合
        assertVerificationEqual(prod.result, ver.result);
        assertTrue("both closed-loop paths must pass verification against the same post-state snapshot",
                prod.result.allPassed() && ver.result.allPassed());
    }

    @Test
    public void nullLocatorSkipYieldsIdenticalExecutionTrace() {
        ElementRef username = new ElementRef("username",
                List.of(LocatorSpec.roleOfKey("textbox", "user.key")), "textbox");
        // 无定位符元素：确定性校验已 ERROR，两条路径都跳过该规则 → 仍只有 NAVIGATE + FILL 两步
        ElementRef orphan = new ElementRef("orphan", List.of(), "textbox");

        LinkageGraph.LinkageRule validRule = new LinkageGraph.LinkageRule(
                username, Command.ActionType.FILL, "alice", List.of());
        LinkageGraph.LinkageRule nullLocRule = new LinkageGraph.LinkageRule(
                orphan, Command.ActionType.CLICK, "", List.of());
        LinkageGraph graph = new LinkageGraph(REQ, List.of(validRule, nullLocRule));

        NlsCatalog nls = new StubNls();
        VerifiableStepPlan producerVsp = new StepPlanProducer(nls).produce(buildBundle(graph));
        VerifiableStepPlan verifyVsp = LinkageGraphStepPlans.toVerifiableStepPlan(graph, PAGE, nls);

        Snapshot snapshot = satisfyingSnapshot(producerVsp);
        ExecResult prod = run(producerVsp, snapshot);
        ExecResult ver = run(verifyVsp, snapshot);

        assertEquals(2, prod.trace.size());
        assertEquals("skipped null-locator rule yields identical execution trace",
                prod.trace, ver.trace);
        assertVerificationEqual(prod.result, ver.result);
    }

    /**
     * 持久化边界对账：产线 {@link StepPlanProducer} 产出的 {@link VerifiableStepPlan} 经<b>生产序列化配置</b>
     * 落库为 {@code STEP_PLAN} 资产 JSON，再反序列化回来（即生产态消费端加载落库资产），喂给真实
     * {@link StepVerifier} 跑出的执行轨迹与验证侧逐字节一致且验证结论一致。
     *
     * <p>价值：把闭环对账锁死到<b>持久化往返</b>边界——若资产序列化丢失任何治理字段（定位符 / scope /
     * 内联期望），此处会立即暴露为轨迹或验证结论漂移，而非依赖真浏览器 E2E 才偶然发现。</p>
     */
    @Test
    public void persistedProductionPlanBehavesIdenticallyToVerificationPath() throws Exception {
        LinkageGraph graph = buildGraph();
        NlsCatalog nls = new StubNls();

        // 生产侧：流水线产出的计划（被序列化为 STEP_PLAN 资产落库）
        VerifiableStepPlan producerVsp = new StepPlanProducer(nls).produce(buildBundle(graph));
        String persisted = MAPPER.writeValueAsString(producerVsp);
        // 生产态消费端：从落库资产读回（同一 VerifiableStepPlan 契约；与生产流水线同一 Jackson 配置）
        VerifiableStepPlan restored = VerifiableStepPlan.fromJson(persisted);

        // 验证侧：同源资产经统一闭环入口派生
        VerifiableStepPlan verifyVsp = LinkageGraphStepPlans.toVerifiableStepPlan(graph, PAGE, nls);

        Snapshot snapshot = satisfyingSnapshot(restored);
        ExecResult prod = run(restored, snapshot);
        ExecResult ver = run(verifyVsp, snapshot);

        assertEquals("restored persisted production plan drives identical execution to verification path",
                prod.trace, ver.trace);
        assertVerificationEqual(prod.result, ver.result);
        assertTrue("both paths pass against the same post-state snapshot",
                prod.result.allPassed() && ver.result.allPassed());
    }

    private ExecResult run(VerifiableStepPlan vsp, Snapshot snapshot) {
        RecordingChannel ch = new RecordingChannel(snapshot);
        StepVerificationResult result = StepVerifier.verify(vsp, ch);
        return new ExecResult(ch.trace, result);
    }

    /** 执行结果聚合：逐字节轨迹 + 验证结论。 */
    private static final class ExecResult {
        final List<String> trace;
        final StepVerificationResult result;
        ExecResult(List<String> trace, StepVerificationResult result) {
            this.trace = trace;
            this.result = result;
        }
    }

    /** 假通道：记录每步（命令 + 已解析 scope），返回固定快照；浏览器无关。 */
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
            return CommandResult.ok(snapshot);
        }

        @Override
        public CommandResult execute(Command command, ParamScope scope) {
            // 与默认方法一致：先过参数范围门禁（fail-closed），再执行
            ParamScopeGate.check(scope, command);
            trace.add(command.action()
                    + "|" + command.targetRef()
                    + "|" + new TreeMap<>(command.params())
                    + "|" + (scope == null ? "" : scope.canonical()));
            return execute(command);
        }
    }

    private static void assertVerificationEqual(StepVerificationResult a, StepVerificationResult b) {
        assertEquals("allPassed", a.allPassed(), b.allPassed());
        assertEquals("steps", a.steps(), b.steps());
        assertEquals("passedSteps", a.passedSteps(), b.passedSteps());
        assertEquals("checkedExpectations", a.checkedExpectations(), b.checkedExpectations());
        assertEquals("passedExpectations", a.passedExpectations(), b.passedExpectations());
        assertEquals("failure count", a.allFailures().size(), b.allFailures().size());
    }

    /** 同一资产：username(FILL) → submit(CLICK，触发 submit ENABLE)。 */
    private static LinkageGraph buildGraph() {
        ElementRef username = new ElementRef("username",
                List.of(LocatorSpec.roleOfKey("textbox", "user.key")), "textbox");
        ElementRef submit = new ElementRef("submit",
                List.of(LocatorSpec.roleOfKey("button", "submit.key")), "button");
        LinkageGraph.ExpectedChange enableSubmit = new LinkageGraph.ExpectedChange(
                submit, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        LinkageGraph.LinkageRule fillRule = new LinkageGraph.LinkageRule(
                username, Command.ActionType.FILL, "alice", List.of());
        LinkageGraph.LinkageRule clickRule = new LinkageGraph.LinkageRule(
                submit, Command.ActionType.CLICK, "", List.of(enableSubmit));
        return new LinkageGraph(REQ, List.of(fillRule, clickRule));
    }

    /**
     * 固定执行后快照：从计划的<b>已本地化</b>命令 target 派生每个元素 ref（与内联期望 ref 精确一致），
     * 且均 enabled —— 使 ENABLE 期望通过、存在性检查通过。命令无 target（NAVIGATE）不产生元素。
     */
    private static Snapshot satisfyingSnapshot(VerifiableStepPlan vsp) {
        List<Snapshot.ElementState> elements = new ArrayList<>();
        for (Command step : vsp.plan().steps()) {
            if (step.target() != null) {
                elements.add(new Snapshot.ElementState(
                        step.target().canonical(), "role", "name", true, true, ""));
            }
        }
        return new Snapshot("page-ref", elements);
    }

    private AssetDraftBundle buildBundle(LinkageGraph graph) {
        return new AssetDraftBundle(
                new RequirementContext(List.of(), "test"),
                new ElementInventory(List.of(
                        new ElementInventoryEntry(new ElementRef("username",
                                List.of(LocatorSpec.roleOfKey("textbox", "user.key")), "textbox"), "用户名", REQ),
                        new ElementInventoryEntry(new ElementRef("submit",
                                List.of(LocatorSpec.roleOfKey("button", "submit.key")), "button"), "提交", REQ))),
                new BehaviorModel(List.of()),
                graph,
                Provenance.manual(PAGE),
                PAGE);
    }

    /** 最小 NLS 桩：把既定 key 解析为中文可见文本（非模板）。 */
    private static final class StubNls implements NlsCatalog {
        @Override public String language() { return "zh"; }
        @Override public boolean hasKey(String key) { return true; }
        @Override public String value(String key) { return visibleText(key); }
        @Override public String visibleText(String key) {
            return switch (key) {
                case "user.key" -> "用户名";
                case "submit.key" -> "提交";
                default -> key;
            };
        }
        @Override public String keyOf(String visibleName) { return null; }
        @Override public java.util.Set<String> keys() { return java.util.Set.of("user.key", "submit.key"); }
    }
}

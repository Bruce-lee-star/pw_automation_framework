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
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 闭环引擎两条构建路径的同构性对账（设计 §4.6 路径 A 验证 / 路径 B 生产）。
 *
 * <p>同一份 {@link LinkageGraph} 经 {@code LinkageGraphStepPlans}（core 验证路径）与
 * {@link StepPlanProducer}（analysis 生产路径）派生出的 {@link VerifiableStepPlan}，必须在
 * <b>治理相关内容</b>上完全同构：</p>
 * <ul>
 *   <li>步骤数 / 顺序 / 每步 action / 本地化后的 targetRef / 排序后 params；</li>
 *   <li>每步 step 级 scope（均经 {@code ParamScope.forAssetWriteValue}）；</li>
 *   <li>plan 级 scope（仅导航 url）；</li>
 *   <li>每步内联期望的 ref（均经共享 localize）；</li>
 *   <li>空触发定位符规则两边一致跳过。</li>
 * </ul>
 *
 * <p>唯一的<b>有意</b>语义差异是写入值来源：生产路径优先采用需求源测试数据覆盖
 * （{@code resolvedValue}），验证路径采用资产内嵌 {@code triggerValue()}。本对账在「无需求覆盖」
 * 下执行，因此两路值源一致，得以证明内容同构、{@code planDigest} 各内容分量一致。</p>
 *
 * <p>注：名义 {@code planId} 前缀（{@code plan-} / {@code linkage-}）刻意不同，用于可观测性区分
 * 两路径，不属于治理内容，故对账排除；二者均嵌入同一 {@code requirementId}。</p>
 */
public class ClosedLoopPlanIsomorphismTest {

    private static final String PAGE = "https://app/p";
    private static final String REQ = "REQ-ISO";

    @Test
    public void productionAndVerificationPathsProduceIsomorphicPlanForSameAsset() {
        ElementRef username = new ElementRef("username",
                List.of(LocatorSpec.roleOfKey("textbox", "user.key")), "textbox");
        ElementRef submit = new ElementRef("submit",
                List.of(LocatorSpec.roleOfKey("button", "submit.key")), "button");

        LinkageGraph.ExpectedChange enableSubmit = new LinkageGraph.ExpectedChange(
                submit, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        // 规则顺序：FILL（无期望）→ CLICK（带期望），验证步骤顺序与期望内联一致
        LinkageGraph.LinkageRule fillRule = new LinkageGraph.LinkageRule(
                username, Command.ActionType.FILL, "alice", List.of());
        LinkageGraph.LinkageRule clickRule = new LinkageGraph.LinkageRule(
                submit, Command.ActionType.CLICK, "", List.of(enableSubmit));
        LinkageGraph graph = new LinkageGraph(REQ, List.of(fillRule, clickRule));

        NlsCatalog nls = new StubNls();

        // 产线路径：无需求覆盖 → 值源与验证路径一致
        VerifiableStepPlan producerVsp = new StepPlanProducer(nls).produce(buildBundle(graph));
        // 验证路径
        VerifiableStepPlan verifyVsp = LinkageGraphStepPlans.toVerifiableStepPlan(graph, PAGE, nls);

        assertIsomorphic(producerVsp.plan(), verifyVsp.plan());
    }

    /**
     * 指纹级对账：同一资产经两路派生的同构计划，其 {@link StepPlan#planDigest()} 必须<b>逐字节相等</b>。
     * 这是闭环对账的最终护栏——把「结构同构」收敛为「内容指纹一致」，使两条闭环路径可直接比对指纹
     * 而无需关心名义 {@code planId} 前缀（{@code plan-} / {@code linkage-}）。{@code planDigest}
     * 已排除 {@code planId}（运行时标识 / 可观测性前缀，非被批准内容）。
     */
    @Test
    public void planDigestIsByteIdenticalAcrossClosedLoopPaths() {
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
        LinkageGraph graph = new LinkageGraph(REQ, List.of(fillRule, clickRule));

        NlsCatalog nls = new StubNls();
        // 无需求覆盖 → 两路值源一致、内容完全同构
        VerifiableStepPlan producerVsp = new StepPlanProducer(nls).produce(buildBundle(graph));
        VerifiableStepPlan verifyVsp = LinkageGraphStepPlans.toVerifiableStepPlan(graph, PAGE, nls);

        assertEquals("planDigest must be byte-identical across closed-loop paths",
                producerVsp.plan().planDigest(), verifyVsp.plan().planDigest());
        // 名义 planId 前缀仍刻意不同（可观测性），但不进入指纹
        assertTrue(producerVsp.plan().planId().startsWith("plan-"));
        assertTrue(verifyVsp.plan().planId().startsWith("linkage-"));
    }

    @Test
    public void nullPrimaryLocatorRuleIsSkippedByBothPaths() {
        ElementRef username = new ElementRef("username",
                List.of(LocatorSpec.roleOfKey("textbox", "user.key")), "textbox");
        // 无定位符的元素：确定性校验应已 ERROR，两条路径都应跳过该规则
        ElementRef orphan = new ElementRef("orphan", List.of(), "textbox");

        LinkageGraph.LinkageRule validRule = new LinkageGraph.LinkageRule(
                username, Command.ActionType.FILL, "alice", List.of());
        LinkageGraph.LinkageRule nullLocRule = new LinkageGraph.LinkageRule(
                orphan, Command.ActionType.CLICK, "", List.of());
        LinkageGraph graph = new LinkageGraph(REQ, List.of(validRule, nullLocRule));

        NlsCatalog nls = new StubNls();
        VerifiableStepPlan producerVsp = new StepPlanProducer(nls).produce(buildBundle(graph));
        VerifiableStepPlan verifyVsp = LinkageGraphStepPlans.toVerifiableStepPlan(graph, PAGE, nls);

        // NAVIGATE + 唯一有效规则 = 2 步；无定位符规则两边一致跳过
        assertEquals(2, producerVsp.plan().steps().size());
        assertEquals(2, verifyVsp.plan().steps().size());
        assertEquals(Command.ActionType.NAVIGATE, producerVsp.plan().steps().get(0).action());
        assertEquals(Command.ActionType.NAVIGATE, verifyVsp.plan().steps().get(0).action());
        assertEquals(Command.ActionType.FILL, producerVsp.plan().steps().get(1).action());
        assertEquals(Command.ActionType.FILL, verifyVsp.plan().steps().get(1).action());
    }

    /**
     * 唯一有意差异刻画：当产线路径带需求测试数据覆盖（username=bob）而验证路径无覆盖概念
     * （始终用资产内嵌 triggerValue=alice）时，两路治理内容<b>仅在写入值及其 step 级 scope 上不同</b>，
     * 其余分量（步骤顺序 / action / 本地化定位符 / plan 级 scope / 内联期望 ref / 非写入步）完全一致。
     * 本用例把「唯一差异」从口头约定固化为可执行的契约断言，防未来意外漂移。
     */
    @Test
    public void valueOverrideIsTheOnlyDivergenceBetweenProductionAndVerificationPaths() {
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
        LinkageGraph graph = new LinkageGraph(REQ, List.of(fillRule, clickRule));

        NlsCatalog nls = new StubNls();
        // 产线路径带需求覆盖：username=bob（优先于图谱内嵌的 alice）
        RequirementContext req = new RequirementContext(List.of(), "test", Map.of("username", "bob"));
        VerifiableStepPlan producerVsp = new StepPlanProducer(nls).produce(buildBundle(graph, req), req);
        // 验证路径无覆盖概念：始终用资产内嵌 triggerValue()=alice
        VerifiableStepPlan verifyVsp = LinkageGraphStepPlans.toVerifiableStepPlan(graph, PAGE, nls);

        // —— 除写入值外，两路治理内容逐分量一致 ——
        assertEquals(3, producerVsp.plan().steps().size());
        assertEquals(3, verifyVsp.plan().steps().size());
        for (int i = 0; i < 3; i++) {
            Command cp = producerVsp.plan().steps().get(i);
            Command cv = verifyVsp.plan().steps().get(i);
            assertEquals("step " + i + " action", cp.action(), cv.action());
            assertEquals("step " + i + " targetRef", cp.targetRef(), cv.targetRef());
            assertEquals("step " + i + " expectations", expectationRefs(cp), expectationRefs(cv));
        }
        assertEquals("plan scope", producerVsp.plan().scope().canonical(), verifyVsp.plan().scope().canonical());

        // FILL 步（index 1）：唯一差异点 —— 写入值
        assertEquals("bob", producerVsp.plan().steps().get(1).params().get("value"));
        assertEquals("alice", verifyVsp.plan().steps().get(1).params().get("value"));
        // 对应的 step 级 scope 也仅在该值上不同
        assertTrue(producerVsp.plan().resolvedScope(1).constraintFor("value").get().allows("bob"));
        assertTrue(verifyVsp.plan().resolvedScope(1).constraintFor("value").get().allows("alice"));
        // CLICK 步（index 2）：无论值源如何都无任何差异（值源不影响 CLICK）
        assertEquals(new TreeMap<>(producerVsp.plan().steps().get(2).params()),
                new TreeMap<>(verifyVsp.plan().steps().get(2).params()));
        assertEquals(producerVsp.plan().scopeForStep(2).canonical(), verifyVsp.plan().scopeForStep(2).canonical());
    }

    private AssetDraftBundle buildBundle(LinkageGraph graph) {
        return buildBundle(graph, new RequirementContext(List.of(), "test"));
    }

    private AssetDraftBundle buildBundle(LinkageGraph graph, RequirementContext req) {
        return new AssetDraftBundle(
                req,
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

    private static void assertIsomorphic(StepPlan a, StepPlan b) {
        assertEquals("step count", a.steps().size(), b.steps().size());
        for (int i = 0; i < a.steps().size(); i++) {
            Command ca = a.steps().get(i);
            Command cb = b.steps().get(i);
            assertEquals("step " + i + " action", ca.action(), cb.action());
            assertEquals("step " + i + " targetRef", ca.targetRef(), cb.targetRef());
            assertEquals("step " + i + " params", new TreeMap<>(ca.params()), new TreeMap<>(cb.params()));
            assertEquals("step " + i + " stepScope", a.scopeForStep(i).canonical(), b.scopeForStep(i).canonical());
            assertEquals("step " + i + " expectations", expectationRefs(ca), expectationRefs(cb));
        }
        assertEquals("plan scope", a.scope().canonical(), b.scope().canonical());
        // 名义 planId 仅前缀不同（可观测性），但都嵌入同一 requirementId
        assertTrue(a.planId().contains(REQ));
        assertTrue(b.planId().contains(REQ));
    }

    private static List<String> expectationRefs(Command c) {
        return c.expectations().stream().map(SnapshotExpectation::ref).collect(Collectors.toList());
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

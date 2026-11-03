package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.BehaviorModel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventory;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * StepPlanProducer：把联动图谱转成「可执行 + 可验证」步骤计划（浏览器无关，零 LLM）。
 * 覆盖 NAVIGATE/触发步骤生成、ParamScope 治理域、ExpectedChange→SnapshotExpectation、
 * 无效定位符跳过、以及治理预检 validatePlan 通过。
 */
public class StepPlanProducerTest {

    private static final String PAGE = "https://app/p";

    @Test
    public void producesNavigatePlusFillWithGovernanceScopeAndExpectation() {
        ElementRef username = new ElementRef("username",
                List.of(LocatorSpec.role("textbox", "Username")), "textbox");
        ElementRef submit = new ElementRef("submit",
                List.of(LocatorSpec.role("button", "Submit")), "button");

        LinkageGraph.ExpectedChange enableSubmit = new LinkageGraph.ExpectedChange(
                submit, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        LinkageGraph.LinkageRule rule = new LinkageGraph.LinkageRule(
                username, Command.ActionType.FILL, "alice", List.of(enableSubmit));
        LinkageGraph linkage = new LinkageGraph("REQ-1", List.of(rule));

        AssetDraftBundle bundle = new AssetDraftBundle(
                new RequirementContext(List.of(), "test"),
                new ElementInventory(List.of(
                        new ElementInventoryEntry(username, "用户名", "REQ-1"),
                        new ElementInventoryEntry(submit, "提交", "REQ-1"))),
                new BehaviorModel(List.of()),
                linkage,
                Provenance.manual(PAGE),
                PAGE);

        VerifiableStepPlan vsp = new StepPlanProducer().produce(bundle);

        assertEquals(PAGE, vsp.pageRef());
        assertEquals(2, vsp.plan().steps().size());
        assertSame(Command.ActionType.NAVIGATE, vsp.plan().steps().get(0).action());
        assertSame(Command.ActionType.FILL, vsp.plan().steps().get(1).action());
        assertEquals("alice", vsp.plan().steps().get(1).params().get("value"));

        // ParamScope 治理域：url + value 均声明
        assertTrue(vsp.plan().scope().constraintFor("url").isPresent());
        assertTrue(vsp.plan().scope().constraintFor("url").get().allows(PAGE));
        assertTrue(vsp.plan().scope().constraintFor("value").isPresent());
        assertTrue(vsp.plan().scope().constraintFor("value").get().allows("alice"));

        // 逐步期望：NAVIGATE 步无断言；FILL 步断言 submit 启用
        assertTrue(vsp.expectationsPerStep().get(0).isEmpty());
        assertEquals(1, vsp.expectationsPerStep().get(1).size());
        var exp = vsp.expectationsPerStep().get(1).get(0);
        assertEquals("role=button;name=Submit", exp.ref());
        assertEquals(Boolean.TRUE, exp.enabled());

        // 分析侧治理预检（浏览器无关）通过：副作用参数均已声明
        ParamScopePolicy.validatePlan(vsp.plan());
    }

    @Test
    public void prefersRequirementTestDataOverTriggerValue() {
        ElementRef username = new ElementRef("username",
                List.of(LocatorSpec.role("textbox", "Username")), "textbox");
        ElementRef submit = new ElementRef("submit",
                List.of(LocatorSpec.role("button", "Submit")), "button");

        LinkageGraph.ExpectedChange enableSubmit = new LinkageGraph.ExpectedChange(
                submit, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        // 图谱内嵌值 alice；需求源声明 username=bob，应优先采用 b=bob
        LinkageGraph.LinkageRule rule = new LinkageGraph.LinkageRule(
                username, Command.ActionType.FILL, "alice", List.of(enableSubmit));
        LinkageGraph linkage = new LinkageGraph("REQ-3", List.of(rule));

        RequirementContext req = new RequirementContext(List.of(), "test", Map.of("username", "bob"));
        AssetDraftBundle bundle = new AssetDraftBundle(
                req,
                new ElementInventory(List.of(
                        new ElementInventoryEntry(username, "用户名", "REQ-3"),
                        new ElementInventoryEntry(submit, "提交", "REQ-3"))),
                new BehaviorModel(List.of()),
                linkage,
                Provenance.manual(PAGE),
                PAGE);

        VerifiableStepPlan vsp = new StepPlanProducer().produce(bundle, req);

        // 需求源测试数据优先：FILL 用 bob 而非图谱内嵌的 alice
        assertEquals("bob", vsp.plan().steps().get(1).params().get("value"));
        assertTrue(vsp.plan().scope().constraintFor("value").get().allows("bob"));
        assertFalse(vsp.plan().scope().constraintFor("value").get().allows("alice"));

        // 治理预检仍通过（bob 已声明）
        ParamScopePolicy.validatePlan(vsp.plan());
    }

    @Test
    public void skipsRuleWithoutRoleFirstLocator() {
        ElementRef noLocator = new ElementRef("orphan", List.of(), "textbox");
        LinkageGraph.LinkageRule rule = new LinkageGraph.LinkageRule(
                noLocator, Command.ActionType.CLICK, "", List.of());
        LinkageGraph linkage = new LinkageGraph("REQ-2", List.of(rule));

        AssetDraftBundle bundle = new AssetDraftBundle(
                new RequirementContext(List.of(), "test"),
                new ElementInventory(List.of(new ElementInventoryEntry(noLocator, "孤儿", "REQ-2"))),
                new BehaviorModel(List.of()),
                linkage,
                Provenance.manual(PAGE),
                PAGE);

        VerifiableStepPlan vsp = new StepPlanProducer().produce(bundle);

        // 仅 NAVIGATE 步（无定位符的规则被跳过）
        assertEquals(1, vsp.plan().steps().size());
        assertFalse(vsp.plan().scope().constraintFor("value").isPresent());
    }
}

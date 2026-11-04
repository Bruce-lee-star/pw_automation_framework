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
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
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

        // ParamScope 治理域：url 在 plan 级声明；value 在 FILL 步的 resolvedScope 精确授权
        assertTrue(vsp.plan().scope().constraintFor("url").isPresent());
        assertTrue(vsp.plan().scope().constraintFor("url").get().allows(PAGE));
        ParamScope fillScope = vsp.plan().resolvedScope(1);
        assertTrue(fillScope.constraintFor("value").isPresent());
        assertTrue(fillScope.constraintFor("value").get().allows("alice"));

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
        ParamScope fillScope = vsp.plan().resolvedScope(1);
        assertTrue(fillScope.constraintFor("value").get().allows("bob"));
        assertFalse(fillScope.constraintFor("value").get().allows("alice"));

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

    @Test
    public void nlsKeyTriggerAndTargetAreLocalizedConsistentlyWithVerificationPath() {
        // 触发与期望目标均用 NLS key 定位（多语言首选形态）
        ElementRef username = new ElementRef("username",
                List.of(LocatorSpec.roleOfKey("textbox", "user.key")), "textbox");
        ElementRef submit = new ElementRef("submit",
                List.of(LocatorSpec.roleOfKey("button", "submit.key")), "button");

        LinkageGraph.ExpectedChange enableSubmit = new LinkageGraph.ExpectedChange(
                submit, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        LinkageGraph.LinkageRule rule = new LinkageGraph.LinkageRule(
                username, Command.ActionType.FILL, "alice", List.of(enableSubmit));
        LinkageGraph linkage = new LinkageGraph("REQ-4", List.of(rule));

        NlsCatalog nls = new StubNls();
        AssetDraftBundle bundle = new AssetDraftBundle(
                new RequirementContext(List.of(), "test"),
                new ElementInventory(List.of(
                        new ElementInventoryEntry(username, "用户名", "REQ-4"),
                        new ElementInventoryEntry(submit, "提交", "REQ-4"))),
                new BehaviorModel(List.of()),
                linkage,
                Provenance.manual(PAGE),
                PAGE);

        // 产线路径（StepPlanProducer）
        VerifiableStepPlan vsp = new StepPlanProducer(nls).produce(bundle);
        String producerTriggerRef = vsp.plan().steps().get(1).targetRef();
        String producerTargetRef = vsp.expectationsPerStep().get(1).get(0).ref();

        // 验证路径（LinkageGraphStepPlans）：同一图谱 + 同一 nls 必须产出同一 localized 定位符
        VerifiableStepPlan verifyVsp = LinkageGraphStepPlans.toVerifiableStepPlan(linkage, PAGE, nls);
        String verifyTriggerRef = verifyVsp.plan().steps().get(1).targetRef();

        // 未本地化前应为 key 形态；本地化后应解析为当前语言文案
        assertEquals("role=textbox;name=用户名", producerTriggerRef);
        assertEquals("role=button;name=提交", producerTargetRef);
        // 两条闭环路径对同一条规则产出完全同构的定位符（planDigest 指纹可比对）
        assertEquals(verifyTriggerRef, producerTriggerRef);

        // 治理预检仍通过（本地化不影响范围授权）
        ParamScopePolicy.validatePlan(vsp.plan());
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

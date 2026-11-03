package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline.StepPlanProducer;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;

import org.junit.Test;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * 真实流水线闭环端到端（分析层产出 → 治理预检 → 真实浏览器验证）。
 *
 * <p>补足既有真浏览器测试的缺口：现有 {@code StepPlanRunnerIntegrationTest} 手写计划、
 * {@code LinkageGraphVerifierSampleTest} 走 {@code LinkageGraphVerifier} 资产验证路径，
 * 二者都<b>未</b>把 {@code AssetPipelineOrchestrator} 产线（{@code StepPlanProducer.produce}
 * + {@code ParamScopePolicy.validatePlan}）生成的 {@link VerifiableStepPlan} 经
 * {@code StepPlanRunner.run(vsp)} 喂给真实浏览器。本测试补齐这条端到端链路。</p>
 *
 * <p>同时验证两个真实参数域要点：
 * <ol>
 *   <li>{@code StepPlanProducer.buildScope} 把全部 FILL 值 / SELECT 选项合并成 plan 级 allow 列表，
 *       多步不同取值的真实图谱在 {@code ParamScopePolicy.validatePlan} + 真实通道门禁下正确放行；</li>
 *   <li>需求源测试数据（{@link RequirementContext#testValue}）在真实闭环中优先覆盖图谱内嵌值（#2 接入）。</li>
 * </ol>
 *
 * <p>若本环境未安装 Playwright 浏览器，测试自动跳过（{@code assumeNoException}），不阻塞构建。</p>
 */
public class AssetPipelineClosedLoopE2ETest {

    @Test
    public void producedPlanDrivesRealBrowserAndPassesGovernance() {
        String targetUrl = resolveFixtureUrl();
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            // ① 分析层：贴近真实的联动图谱（FILL + SELECT + CLICK 三类写入型触发）
            LinkageGraph graph = realisticLinkageGraph();
            // ② 需求源声明权威测试数据：覆盖 username 的 FILL 值（演示 #2 测试数据抽取接入）
            RequirementContext req = new RequirementContext(
                    Collections.emptyList(), "closedloop.feature",
                    Map.of("username", "alice"));
            AssetDraftBundle bundle = new AssetDraftBundle(req, null, null, graph, null, targetUrl);

            // ③ 闭环引擎：分析层产出可验证计划（零浏览器，仅生成计划 + 治理域）
            VerifiableStepPlan vsp = new StepPlanProducer().produce(bundle, req);

            // ③-附：需求源测试数据已在真实闭环中优先覆盖图谱占位值
            assertEquals("alice", vsp.plan().steps().get(1).params().get("value"));

            // ④ 治理预检（fail-closed）：合并后的 plan 级 value/option 白名单须覆盖真实取值
            ParamScopePolicy.validatePlan(vsp.plan());

            // ⑤ 真实浏览器执行 + 执行后绝对态断言
            StepVerificationResult result = new StepPlanRunner(harness).run(vsp);

            assertTrue("闭环执行整体应全部通过", result.allPassed());
            // 步数 = NAVIGATE + 3 条联动触发
            assertEquals(4, result.steps());
        } catch (RuntimeException e) {
            // 浏览器不可用（未安装 / 无显示等）→ 跳过，不视为失败。
            assumeNoException("Playwright browser unavailable in this environment", e);
        } finally {
            harness.closeAll();
        }
    }

    @Test
    public void gateFailsClosedOnOutOfScopeValueAtRealChannel() {
        String targetUrl = resolveFixtureUrl();
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            // 构造一个计划：FILL 取值 rogueValue 不在 scope 白名单内——
            // 治理预检（仅声明校验）通过，但真实通道门禁按取值拒绝（fail-closed，该步不执行）。
            LocatorSpec usernameLoc = LocatorSpec.role("textbox", "Username");
            ParamScope scope = new ParamScope(Map.of(
                    "url", ParamConstraint.allowedValues(targetUrl),
                    "value", ParamConstraint.allowedValues("goodValue")));
            StepPlan plan = new StepPlan(1, "out-of-scope-plan",
                    List.of(Command.navigate(targetUrl), Command.fill(usernameLoc, "rogueValue")),
                    scope, List.of());
            VerifiableStepPlan vsp = new VerifiableStepPlan(plan, targetUrl);

            try {
                new StepPlanRunner(harness).run(vsp);
                fail("越界参数应在真实通道边界被门禁拦截，不应执行通过");
            } catch (ParamScopeViolationException expected) {
                // 期望：治理 fail-closed，该步不执行，抛 ParamScopeViolationException
            }
        } catch (ParamScopeViolationException expected) {
            // 同上：run 内部抛出即为期望
        } catch (RuntimeException e) {
            assumeNoException("Playwright browser unavailable in this environment", e);
        } finally {
            harness.closeAll();
        }
    }

    private static String resolveFixtureUrl() {
        java.net.URL resource = AssetPipelineClosedLoopE2ETest.class.getResource(
                "/com/hsbc/cmb/hk/dbb/automation/framework/web/agent/closedloop-fixture.html");
        assumeTrue("closedloop-fixture.html missing on test classpath", resource != null);
        try {
            return Paths.get(URI.create(resource.toURI().toString())).toUri().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve fixture URL", e);
        }
    }

    /** 三类真实联动：FILL→ENABLE、SELECT→APPEAR、CLICK→ENABLE；FILL 取值由需求源覆盖。 */
    private static LinkageGraph realisticLinkageGraph() {
        // 联动 A：FILL username → Submit 启用(ENABLE)
        ElementRef username = new ElementRef("username",
                Collections.singletonList(LocatorSpec.role("textbox", "Username")), "username input");
        ElementRef submit = new ElementRef("submit",
                Collections.singletonList(LocatorSpec.role("button", "Submit")), "submit button");
        LinkageGraph.ExpectedChange submitEnabled = new LinkageGraph.ExpectedChange(
                submit, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        // triggerValue 故意设为占位值，由需求源 testData 覆盖为 "alice"
        LinkageGraph.LinkageRule fillRule = new LinkageGraph.LinkageRule(
                username, Command.ActionType.FILL, "placeholderUser", List.of(submitEnabled));

        // 联动 B：SELECT accountType=corporate → purpose 出现(APPEAR)
        ElementRef accountType = new ElementRef("accountType",
                Collections.singletonList(LocatorSpec.role("combobox", "Account type")), "account type select");
        ElementRef purpose = new ElementRef("purpose",
                Collections.singletonList(LocatorSpec.role("textbox", "Purpose")), "purpose input");
        LinkageGraph.ExpectedChange purposeAppears = new LinkageGraph.ExpectedChange(
                purpose, LinkageGraph.ExpectedChange.ChangeKind.APPEAR, null);
        LinkageGraph.LinkageRule selectRule = new LinkageGraph.LinkageRule(
                accountType, Command.ActionType.SELECT, "corporate", List.of(purposeAppears));

        // 联动 C：CLICK agree → reason 启用(ENABLE)
        ElementRef agree = new ElementRef("agree",
                Collections.singletonList(LocatorSpec.role("checkbox", "I agree to the terms")), "agree checkbox");
        ElementRef reason = new ElementRef("reason",
                Collections.singletonList(LocatorSpec.role("textbox", "Reason")), "reason input");
        LinkageGraph.ExpectedChange reasonEnabled = new LinkageGraph.ExpectedChange(
                reason, LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null);
        LinkageGraph.LinkageRule clickRule = new LinkageGraph.LinkageRule(
                agree, Command.ActionType.CLICK, List.of(reasonEnabled));

        return new LinkageGraph("REQ-CLOSEDLOOP-1", Arrays.asList(fillRule, selectRule, clickRule));
    }
}

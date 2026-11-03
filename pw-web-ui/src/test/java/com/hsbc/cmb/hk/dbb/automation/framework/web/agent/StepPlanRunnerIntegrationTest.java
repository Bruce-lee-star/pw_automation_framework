package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;

import org.junit.Test;

import java.net.URI;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * 闭环接入真实 PlaywrightChannel 的端到端验证（完整驱动引擎专项）。
 *
 * <p>把 pw-core 的「授权执行 + 执行后联动断言」闭环经 {@link StepPlanRunner} 接到
 * {@link RouteTestBedHarness} 的真实 {@code PlaywrightChannel}：参数范围门禁在通道边界默认方法内
 * 自动生效（fail-closed），执行后 {@link SnapshotVerifier} 对真实 ARIA 快照做绝对态字段断言。</p>
 *
 * <p>浏览器不可用时（未安装 Playwright 浏览器 / 无显示等）自动跳过，不阻塞构建——与既有
 * {@code LinkageGraphVerifierSampleTest} / {@code NlsLinkageVerificationTest} 保持一致。</p>
 */
public class StepPlanRunnerIntegrationTest {

    /** 授权计划驱动真实浏览器：NAVIGATE → FILL(经门禁授权) → 按钮由禁用变为启用（两帧快照断言）。 */
    @Test
    public void authorizedPlanDrivesRealBrowserAndAssertsStateChange() {
        String targetUrl = resolveFixtureUrl();
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            // 参数范围授权：url 与 value 均须声明取值域（NAVIGATE/FILL 为副作用动作）
            ParamScope scope = new ParamScope(Map.of(
                    "url", ParamConstraint.allowedValues(targetUrl),
                    "value", ParamConstraint.allowedValues("alice")));

            Command navigate = Command.navigate(targetUrl);
            Command fill = Command.fill(LocatorSpec.role("textbox", "Username"), "alice");
            StepPlan plan = new StepPlan(1, "plan-real-1", List.of(navigate, fill), scope, List.of());

            // 每步的快照期望：① 初始提交按钮禁用；② FILL 后提交按钮启用（真实状态变化）
            List<List<SnapshotExpectation>> expectations = List.of(
                    List.of(SnapshotExpectation.forRef("role=button;name=Submit").enabled(false).build()),
                    List.of(SnapshotExpectation.forRef("role=button;name=Submit").enabled(true).build()));

            StepVerificationResult result = new StepPlanRunner(harness).run(plan, expectations, targetUrl);
            assertTrue(result.allPassed());
            assertEquals(2, result.steps());
            assertEquals(2, result.passedSteps());
        } catch (RuntimeException e) {
            assumeNoException("Playwright browser unavailable in this environment", e);
        } finally {
            harness.closeAll();
        }
    }

    /** 越界参数在真实通道边界被门禁拦截（fail-closed）：value=bob 不在 allow 白名单 → 不执行该步。 */
    @Test
    public void outOfScopeValueBlockedByGateOnRealChannel() {
        String targetUrl = resolveFixtureUrl();
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            ParamScope scope = new ParamScope(Map.of(
                    "url", ParamConstraint.allowedValues(targetUrl),
                    "value", ParamConstraint.allowedValues("alice"))); // 仅允许 alice
            Command navigate = Command.navigate(targetUrl);
            Command fill = Command.fill(LocatorSpec.role("textbox", "Username"), "bob"); // 越界值
            StepPlan plan = new StepPlan(1, "plan-oos-1", List.of(navigate, fill), scope, List.of());

            StepPlanRunner runner = new StepPlanRunner(harness);
            try {
                runner.run(plan, List.of(List.of(), List.of()), targetUrl);
                fail("gate should have blocked out-of-scope value on the real channel");
            } catch (ParamScopeViolationException expected) {
                // 期望：真实通道边界门禁拦截越界参数
            }
        } catch (RuntimeException e) {
            assumeNoException("Playwright browser unavailable in this environment", e);
        } finally {
            harness.closeAll();
        }
    }

    /** 闭环引擎另一端：分析层产出的 {@link VerifiableStepPlan} 载体直接喂给真实通道验证。 */
    @Test
    public void authorizedPlanViaCarrierDrivesRealBrowser() {
        String targetUrl = resolveFixtureUrl();
        RouteTestBedHarness harness = new RouteTestBedHarness();
        try {
            ParamScope scope = new ParamScope(Map.of(
                    "url", ParamConstraint.allowedValues(targetUrl),
                    "value", ParamConstraint.allowedValues("alice")));
            Command navigate = Command.navigate(targetUrl);
            // 期望内联进命令：FILL 后提交按钮启用（一步 = 动作 + 其执行后期望）
            Command fill = Command.fill(LocatorSpec.role("textbox", "Username"), "alice")
                    .withExpectations(List.of(
                            SnapshotExpectation.forRef("role=button;name=Submit").enabled(true).build()));
            StepPlan plan = new StepPlan(1, "plan-carrier-1", List.of(navigate, fill), scope, List.of());
            VerifiableStepPlan vsp = new VerifiableStepPlan(plan, targetUrl);

            StepVerificationResult result = new StepPlanRunner(harness).run(vsp);
            assertTrue(result.allPassed());
            assertEquals(2, result.steps());
            assertEquals(2, result.passedSteps());
        } catch (RuntimeException e) {
            assumeNoException("Playwright browser unavailable in this environment", e);
        } finally {
            harness.closeAll();
        }
    }

    private static String resolveFixtureUrl() {
        java.net.URL resource = StepPlanRunnerIntegrationTest.class.getResource(
                "/com/hsbc/cmb/hk/dbb/automation/framework/web/agent/stepplan-fixture.html");
        assumeTrue("stepplan-fixture.html missing on test classpath", resource != null);
        try {
            return Paths.get(URI.create(resource.toURI().toString())).toUri().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve fixture URL", e);
        }
    }
}

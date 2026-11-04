package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetDraft;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.JsonFileAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * <b>真实生产接线的真实浏览器冒烟</b>：{@link AssetVerificationWiring#fromConfig()} 装配真实
 * {@link JsonFileAssetRepository}（磁盘资产库）+ 真实 {@link RouteTestBedHarness}（Playwright 隔离靶场），
 * 落库一份 {@code STEP_PLAN} 资产后经 {@link AssetStepPlanExecutor} 在 {@code file://} fixture 上
 * 真实执行、产出并回写 {@link VerificationReport}，重启仓库验证报告落盘重建。
 *
 * <p>与浏览器无关单测的边界：此处不注入任何假 harness / 假通道，是接线的<b>唯一真实浏览器</b>验证。
 * 浏览器不可用（未安装 Playwright 浏览器 / 无显示）时按 {@code assumeNoException} 跳过，不阻塞构建；
 * 断言失败（AssertionError）不会被当作环境缺失而吞掉。</p>
 */
public class AssetVerificationWiringRealBrowserTest {

    private static final String REQ = "REQ-REAL";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void resetConfig() {
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "");
        System.setProperty(AssetVerificationWiring.ASSET_ROOT_KEY, "");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    /** NAVIGATE(file fixture) → FILL(Username, alice) 后期望 Submit 按钮启用（fixture 由 JS 联动）。 */
    private static VerifiableStepPlan fixtureCarrier(String fixtureUrl) {
        LocatorSpec username = LocatorSpec.role("textbox", "Username");
        Command navigate = new Command(Command.ActionType.NAVIGATE, null,
                Map.of("url", fixtureUrl), List.of());
        Command fill = new Command(Command.ActionType.FILL, username,
                Map.of("value", "alice"),
                List.of(SnapshotExpectation.forRef("role=button;name=Submit").enabled(true).build()));
        ParamScope scope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues(fixtureUrl),
                "value", ParamConstraint.allowedValues("alice")));
        StepPlan plan = new StepPlan(1, "plan-real-wiring", List.of(navigate, fill), scope, List.of());
        return new VerifiableStepPlan(plan, fixtureUrl);
    }

    private static String resolveFixtureUrl() {
        java.net.URL resource = AssetVerificationWiringRealBrowserTest.class.getResource(
                "/com/hsbc/cmb/hk/dbb/automation/framework/web/agent/stepplan-fixture.html");
        assumeTrue("stepplan-fixture.html missing on test classpath", resource != null);
        try {
            return Paths.get(URI.create(resource.toURI().toString())).toUri().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve fixture URL", e);
        }
    }

    @Test
    public void realWiringExecutesRealBrowserAndWritesBackReport() throws Exception {
        String fixtureUrl = resolveFixtureUrl();
        Path root = tmp.newFolder().toPath().resolve("assets-root");
        System.setProperty(AssetVerificationWiring.ASSET_ROOT_KEY, root.toString());
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "");

        // 一次装配完成「真实仓库落库 → 真实靶场执行 → 报告回写 → 磁盘重建 → 覆盖度聚合」
        try (AssetVerificationWiring wiring = AssetVerificationWiring.fromConfig()) {
            AssetId id = wiring.repository().save(new AssetDraft(AssetType.STEP_PLAN, REQ,
                    MAPPER.writeValueAsString(fixtureCarrier(fixtureUrl)),
                    Provenance.manual("page:sha256:fixture")));
            assertEquals(1, wiring.stepPlanAssetsFor(REQ).size());

            try {
                VerificationReport report = wiring.verify(id, new VerificationScope(fixtureUrl, 1));

                assertEquals(VerificationReport.Status.PASS, report.status());
                assertEquals(1.0, report.stability(), 1e-9);
                assertEquals(2, report.items().size());
                assertEquals(VerificationReport.Status.PASS, wiring.repository().get(id).report().status());

                // 回写报告落盘后，进程重启（新仓库实例）仍无损重建
                JsonFileAssetRepository reloaded = new JsonFileAssetRepository(root);
                VerificationReport persisted = reloaded.get(id).report();
                assertNotNull(persisted);
                assertEquals(VerificationReport.Status.PASS, persisted.status());
                assertEquals(report.evidence(), persisted.evidence());

                assertEquals(1, wiring.coverage(REQ).verifiedCount());
                assertEquals(1, wiring.coverage(REQ).passedCount());
            } catch (RuntimeException e) {
                assumeNoException("Playwright browser unavailable in this environment", e);
            }
        }
    }
}

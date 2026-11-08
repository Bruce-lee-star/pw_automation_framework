package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetDraft;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.InMemoryAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.JsonFileAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.RequirementCoverage;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 真实生产接线 {@link AssetVerificationWiring} 的单测（浏览器无关）：
 * <ol>
 *   <li>{@code fromConfig} 装配的是真实文件仓库 + 真实 Playwright 靶场；</li>
 *   <li>显式装配经假 harness 跑通「发现资产 → 执行 → 回写 → 覆盖度」全链路；</li>
 *   <li><b>真实 harness 下非靶场资产 pageRef 被 fail-closed 拒绝</b>——异常发生在
 *       {@code Playwright.create()} 之前，且报告不回写（护栏未被接线层绕过）。</li>
 * </ol>
 */
public class AssetVerificationWiringTest {

    private static final String REQ = "REQ-WIRING";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void resetConfig() {
        // ConfigReader 边界：clearProperty 不保证回落，显式置回默认空串
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "");
        System.setProperty(AssetVerificationWiring.ASSET_ROOT_KEY, "");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    /** 假通道：回固定快照。 */
    private static final class FixedChannel implements BrowserCommandChannel {
        private final Snapshot snapshot;
        FixedChannel(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public CommandResult execute(Command command) { return CommandResult.ok(snapshot); }
    }

    /** 假 harness：记录目标 URL / acquire / release，不启动浏览器。 */
    private static final class FakeHarness implements IsolationHarness {
        final Snapshot snapshot;
        String acquiredUrl;
        int acquired;
        int released;
        FakeHarness(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override
        public BrowserCommandChannel acquire(String targetUrl) {
            acquiredUrl = targetUrl;
            acquired++;
            return new FixedChannel(snapshot);
        }
        @Override
        public void release(BrowserCommandChannel channel) { released++; }
    }

    private static VerifiableStepPlan carrier(String pageRef) {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        Command fill = new Command(Command.ActionType.FILL, loc, Map.of("value", "alice"),
                List.of(SnapshotExpectation.forRef(loc.canonical()).exists(true).enabled(true).build()));
        Command navigate = new Command(Command.ActionType.NAVIGATE, null, Map.of("url", pageRef), List.of());
        ParamScope scope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues(pageRef),
                "value", ParamConstraint.allowedValues("alice")));
        return new VerifiableStepPlan(new StepPlan(1, "plan-w", List.of(navigate, fill), scope, List.of()), pageRef);
    }

    private static Snapshot satisfyingSnapshot() {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        return new Snapshot("fixture", List.of(
                new Snapshot.ElementState(loc.canonical(), "textbox", "用户名", true, true, "")));
    }

    private static VerifiableStepPlan singleNavigateCarrier(String pageRef) {
        Command navigate = new Command(Command.ActionType.NAVIGATE, null, Map.of("url", pageRef), List.of());
        ParamScope scope = new ParamScope(Map.of("url", ParamConstraint.allowedValues(pageRef)));
        return new VerifiableStepPlan(new StepPlan(1, "plan-nav", List.of(navigate), scope, List.of()), pageRef);
    }

    private static AssetId saveStepPlan(InMemoryAssetRepository repo, VerifiableStepPlan vsp) throws Exception {
        return repo.save(new AssetDraft(AssetType.STEP_PLAN, REQ,
                MAPPER.writeValueAsString(vsp), Provenance.manual("page:sha256:abc")));
    }

    @Test
    public void fromConfigAssemblesRealRepositoryAndHarness() throws Exception {
        Path root = tmp.newFolder().toPath().resolve("assets-root");
        System.setProperty(AssetVerificationWiring.ASSET_ROOT_KEY, root.toString());

        AssetVerificationWiring wiring = AssetVerificationWiring.fromConfig();
        try {
            assertTrue("repository must be the real file-backed one",
                    JsonFileAssetRepository.class.isInstance(wiring.repository()));
            assertTrue("harness must be the real Playwright test bed",
                    RouteTestBedHarness.class.isInstance(wiring.harness()));
            assertNotNull(wiring.executor());
            assertTrue("file repository root must be created on assembly",
                    java.nio.file.Files.isDirectory(root.resolve("assets")));
            assertEquals(0, wiring.stepPlanAssetsFor(REQ).size());
            assertEquals(0, wiring.coverage(REQ).assetCount());
        } finally {
            wiring.close();
        }
        // close 幂等（teardown 兜底重复调用不抛）
        wiring.close();
    }

    @Test
    public void explicitWiringRunsDiscoverVerifyWriteBackAndCoverage() throws Exception {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        String pageRef = "file:///fixture.html";
        AssetId id = saveStepPlan(repo, carrier(pageRef));
        FakeHarness harness = new FakeHarness(satisfyingSnapshot());

        AssetVerificationWiring wiring = new AssetVerificationWiring(repo, harness);
        try {
            List<?> found = wiring.stepPlanAssetsFor(REQ);
            assertEquals(1, found.size());

            VerificationReport report = wiring.verify(id, new VerificationScope("ignored-by-design", 2));

            assertEquals(VerificationReport.Status.PASS, report.status());
            assertEquals(1.0, report.stability(), 1e-9);
            assertEquals(2, harness.acquired);
            assertEquals(2, harness.released);
            assertEquals("执行目标须取资产 pageRef，而非 scope.targetUrl", pageRef, harness.acquiredUrl);
            assertEquals(VerificationReport.Status.PASS, repo.get(id).report().status());

            RequirementCoverage coverage = wiring.coverage(REQ);
            assertEquals(1, coverage.assetCount());
            assertEquals(1, coverage.verifiedCount());
            assertEquals(1, coverage.passedCount());
        } finally {
            wiring.close();
        }
    }

    /**
     * 真实靶场接线不得绕过 fail-closed 沙箱：产线资产若以生产主机为 pageRef，
     * {@code RouteTestBedHarness.acquire} 在启动浏览器前即拒绝；报告不得回写。
     * 该用例不需要真实浏览器（拒绝发生在 {@code Playwright.create()} 之前）。
     */
    @Test
    public void nonSandboxAssetPageRefIsRejectedAndReportNotWritten() throws Exception {
        System.setProperty(RouteTestBedHarness.ALLOWED_HOSTS_KEY, "");
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        String productionUrl = "https://prod.example.com/login";
        AssetId id = saveStepPlan(repo, singleNavigateCarrier(productionUrl));

        AssetVerificationWiring wiring = new AssetVerificationWiring(repo, new RouteTestBedHarness());
        try {
            wiring.verify(id, new VerificationScope("ignored", 1));
            fail("expected TestBedTargetRejectedException for non-sandbox asset pageRef");
        } catch (TestBedTargetRejectedException expected) {
            assertTrue(expected.getMessage().contains("pw.agent.testbed.allowedHosts"));
        } finally {
            wiring.close();
        }
        assertNull("fail-closed: rejected verification must not write back a report",
                repo.get(id).report());
        assertEquals(0, wiring.coverage(REQ).verifiedCount());
    }
}

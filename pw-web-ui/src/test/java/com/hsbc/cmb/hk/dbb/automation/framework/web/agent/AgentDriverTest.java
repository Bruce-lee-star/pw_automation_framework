package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetDraft;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetState;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.JsonFileAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StepPlanApprovalBinding;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
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
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T10 验收：{@link AgentDriver} 是唯一执行入口、三类入口共享一套编排模板，且既有
 * 兼容委托与关键语义（T8 审批前置、repeat 每轮一作用域、pageRef 优先、幂等 seam）零回归。
 *
 * <p>全程浏览器无关（假 harness / 假通道 / 假仓库）。</p>
 */
public class AgentDriverTest {

    private static final String URL = "file:///fixture.html";
    private static final String SETTLE_KEY = "agent.step.verify.settle.window.ms";
    private static final AssetId ID = new AssetId("asset:step_plan:1", 1);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String previousSettle;
    private String previousRoot;

    @Before
    public void setUp() {
        previousSettle = System.getProperty(SETTLE_KEY);
        previousRoot = System.getProperty(AgentDriver.ASSET_ROOT_KEY);
        System.setProperty(SETTLE_KEY, "0");
    }

    @After
    public void tearDown() {
        // 显式复位（不 clearProperty）：ConfigResolver 是启动期快照，clear 不保证回落
        System.setProperty(SETTLE_KEY, previousSettle == null ? "500" : previousSettle);
        System.setProperty(AgentDriver.ASSET_ROOT_KEY, previousRoot == null ? "" : previousRoot);
    }

    @Test
    public void allPlanEntryShapesShareOneChannelTemplate() {
        CountingHarness harness = new CountingHarness();
        AgentDriver driver = new AgentDriver(harness);
        StepPlan plan = plan();

        driver.run(plan, List.of(List.of()), URL);
        driver.run(new VerifiableStepPlan(plan, URL));
        driver.execute(plan, URL);

        assertEquals("三类计划入口各开一次通道", 3, harness.acquired);
        assertEquals("三类计划入口各释放一次通道", 3, harness.released);
    }

    @Test
    public void legacyRunnerDelegatesToSameTemplate() {
        CountingHarness harness = new CountingHarness();
        StepPlanRunner runner = new StepPlanRunner(harness);

        runner.execute(plan(), URL);
        runner.run(new VerifiableStepPlan(plan(), URL));

        assertEquals(2, harness.acquired);
        assertEquals(2, harness.released);
    }

    @Test
    public void assetEntryRejectsTamperedApprovedPlanBeforeAcquire() throws Exception {
        String approvedContent = content("alice");
        String approved = StepPlanApprovalBinding.digestOf(approvedContent);
        String tamperedContent = content("mallory");
        String current = StepPlanApprovalBinding.digestOf(tamperedContent);
        assertNotNull(approved);
        assertTrue(!approved.equals(current));

        FixedRepository repo = new FixedRepository(asset(approved, tamperedContent));
        CountingHarness harness = new CountingHarness();
        AgentDriver driver = new AgentDriver(harness, null, AgentRuntimeContext.empty(), repo);

        try {
            driver.verify(ID, new VerificationScope("https://app/p", 1));
            fail("已审批内容被篡改必须拒绝执行");
        } catch (IllegalStateException expected) {
            assertTrue("含已审批摘要：" + expected.getMessage(),
                    expected.getMessage().contains(approved));
            assertTrue("含当前摘要：" + expected.getMessage(),
                    expected.getMessage().contains(current));
        }
        assertEquals("T8 前置校验必须发生在任何 acquire 之前", 0, harness.acquired);
        assertTrue("拒绝时不得回写报告", repo.recorded == null);
    }

    @Test
    public void assetEntryWritesBackReportAndKeepsOneScopePerRepeat() throws Exception {
        String json = content("alice");
        FixedRepository repo = new FixedRepository(asset(null, json));
        CountingHarness harness = new CountingHarness();
        AgentDriver driver = new AgentDriver(harness, null, AgentRuntimeContext.empty(), repo);

        VerificationReport report = driver.verify(ID, new VerificationScope("ignored-by-design", 3));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertEquals(1.0, report.stability(), 1e-9);
        assertEquals("repeat 每轮各开一次作用域/通道，不得提到循环外", 3, harness.acquired);
        assertEquals(3, harness.released);
        assertEquals("执行目标须取资产 pageRef", URL, harness.acquiredUrl);
        assertEquals(report, repo.recorded);
    }

    @Test
    public void wiringAndItsExecutorShareFacadeSemantics() throws Exception {
        FixedRepository repo = new FixedRepository(asset(null, content("alice")));
        CountingHarness harness = new CountingHarness();
        AssetVerificationWiring wiring = new AssetVerificationWiring(repo, harness);

        wiring.verify(ID, new VerificationScope("ignored", 2));
        wiring.executor().run(ID, new VerificationScope("ignored", 1));

        assertEquals(3, harness.acquired);
        assertEquals(3, harness.released);
        assertSame("wiring 与其 executor 必须挂在同一个门面上", harness, wiring.driver().harness());
        assertNotNull(wiring.executor());
    }

    @Test
    public void assetEntryWithoutRepositoryFailsFast() {
        CountingHarness harness = new CountingHarness();
        try {
            new AgentDriver(harness).verify(ID, new VerificationScope(URL, 1));
            fail("未配置资产库时必须 fail-fast");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("AssetRepository"));
        }
        assertEquals(0, harness.acquired);
    }

    @Test
    public void facadeExposesThreeEntryKinds() throws Exception {
        assertNotNull(AgentDriver.class.getMethod("run", StepPlan.class, List.class, String.class));
        assertNotNull(AgentDriver.class.getMethod("run", VerifiableStepPlan.class));
        assertNotNull(AgentDriver.class.getMethod("execute", StepPlan.class, String.class));
        assertNotNull(AgentDriver.class.getMethod("verify", AssetId.class, VerificationScope.class));
    }

    @Test
    public void closeIsIdempotent() {
        AgentDriver driver = new AgentDriver(new CountingHarness());
        driver.close();
        driver.close();
    }

    @Test
    public void fromConfigIsSingleCompositionRoot() throws Exception {
        Path root = tmp.newFolder().toPath().resolve("assets-root");
        System.setProperty(AgentDriver.ASSET_ROOT_KEY, root.toString());

        AgentDriver driver = AgentDriver.fromConfig();
        try {
            assertTrue(driver.repository() instanceof JsonFileAssetRepository);
            assertTrue(driver.harness() instanceof RouteTestBedHarness);
            assertNotNull(driver.runtime());
        } finally {
            driver.close();
        }
    }

    private static StepPlan plan() {
        Command navigate = new Command(Command.ActionType.NAVIGATE, null,
                Map.of("url", URL), List.of());
        ParamScope scope = new ParamScope(Map.of("url", ParamConstraint.allowedValues(URL)));
        return new StepPlan(1, "plan-t10", List.of(navigate), scope, List.of(ParamScope.EMPTY));
    }

    /** 合法且摘要可计算的 STEP_PLAN 载体；value 变化即「计划被篡改」。 */
    private static String content(String value) throws Exception {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        Command fill = new Command(Command.ActionType.FILL, loc, Map.of("value", value),
                List.of(SnapshotExpectation.forRef(loc.canonical()).exists(true).enabled(true).build()));
        Command navigate = new Command(Command.ActionType.NAVIGATE, null,
                Map.of("url", URL), List.of());
        ParamScope scope = new ParamScope(Map.of(
                "url", ParamConstraint.allowedValues(URL),
                "value", ParamConstraint.allowedValues(value)));
        StepPlan plan = new StepPlan(1, "plan-t10-asset", List.of(navigate, fill), scope,
                List.of(ParamScope.EMPTY, ParamScope.EMPTY));
        return MAPPER.writeValueAsString(new VerifiableStepPlan(plan, URL));
    }

    private static StoredAsset asset(String approvedDigest, String contentJson) {
        AssetState state = approvedDigest == null ? AssetState.DRAFT : AssetState.APPROVED;
        return new StoredAsset(ID, AssetType.STEP_PLAN, "REQ-1", state,
                Provenance.manual("p"), contentJson, null, 1L, 2L, approvedDigest);
    }

    /** 满足 FILL 目标 exists+enabled 期望的固定快照通道。 */
    private static final class FixedChannel implements BrowserCommandChannel {
        private final Snapshot snapshot = new Snapshot(URL, List.of(
                new Snapshot.ElementState(LocatorSpec.roleOfKey("textbox", "user.key").canonical(),
                        "textbox", "用户名", true, true, "")));

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public CommandResult execute(Command command) {
            return CommandResult.ok(snapshot);
        }
    }

    private static final class CountingHarness implements IsolationHarness {
        String acquiredUrl;
        int acquired;
        int released;

        @Override
        public BrowserCommandChannel acquire(String targetUrl) {
            acquiredUrl = targetUrl;
            acquired++;
            return new FixedChannel();
        }

        @Override
        public void release(BrowserCommandChannel channel) {
            released++;
        }
    }

    /** 可控资产仓库：直接返回预设 StoredAsset，记录是否发生回写。 */
    private static final class FixedRepository implements AssetRepository {
        private final StoredAsset asset;
        VerificationReport recorded;

        FixedRepository(StoredAsset asset) {
            this.asset = asset;
        }

        @Override
        public StoredAsset get(AssetId id) {
            return asset;
        }

        @Override
        public StoredAsset recordVerification(AssetId id, VerificationReport report) {
            this.recorded = report;
            return asset;
        }

        @Override
        public AssetId save(AssetDraft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredAsset submit(AssetId id, String actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredAsset approve(AssetId id, String approver) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredAsset activate(AssetId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredAsset deprecate(AssetId id, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredAsset> queryByRequirement(String requirementId) {
            throw new UnsupportedOperationException();
        }
    }
}

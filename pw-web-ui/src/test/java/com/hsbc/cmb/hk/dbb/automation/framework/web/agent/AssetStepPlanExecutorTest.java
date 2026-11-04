package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetDraft;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.InMemoryAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
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

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link AssetStepPlanExecutor} 的浏览器无关单测：加载落库 {@code STEP_PLAN} 资产 → 经注入的
 * {@link IsolationHarness}（假 harness / 假通道）→ {@link StepPlanRunner} 跑 → {@link VerificationReport}
 * 回写资产库。不依赖真实 Playwright，锁死「加载落库资产 + 真实 harness + StepPlanRunner + 回写报告」这一
 * 此前只在测试里隐式存在的生产链路。真实 harness / 资产库装配留 pw-web-ui 生产接线。
 */
public class AssetStepPlanExecutorTest {

    /** 与生产流水线同一 Jackson 配置（FIELD 可见 / GETTER + IS_GETTER 不可见）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);

    /** 假通道：回固定快照（不接真实浏览器）。 */
    private static final class FixedChannel implements BrowserCommandChannel {
        private final Snapshot snapshot;
        FixedChannel(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public CommandResult execute(Command command) { return CommandResult.ok(snapshot); }
    }

    /** 脚本化假 harness：按第几次 acquire（即第几次跑）回放不同快照，用于稳定性/flaky 场景。 */
    private static final class ScriptedHarness implements IsolationHarness {
        private final Function<Integer, Snapshot> snapshotForRun;
        private final AtomicInteger run = new AtomicInteger(0);
        boolean released;
        ScriptedHarness(Function<Integer, Snapshot> snapshotForRun) { this.snapshotForRun = snapshotForRun; }
        @Override
        public BrowserCommandChannel acquire(String targetUrl) {
            final int r = run.incrementAndGet();
            final Snapshot snap = snapshotForRun.apply(r);
            return new FixedChannel(snap);
        }
        @Override
        public void release(BrowserCommandChannel channel) { released = true; }
    }

    /** 内联期望的最小载体：NAVIGATE + FILL（FILL 后目标 enabled）。 */
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

    /** 满足期望的快照：含 FILL 目标且 enabled。 */
    private static Snapshot satisfyingSnapshot() {
        LocatorSpec loc = LocatorSpec.roleOfKey("textbox", "user.key");
        return new Snapshot("https://app/p", List.of(
                new Snapshot.ElementState(loc.canonical(), "textbox", "用户名", true, true, "")));
    }

    /** 不满足期望的快照：缺失 FILL 目标（exists 期望失败）。 */
    private static Snapshot failingSnapshot() {
        return new Snapshot("https://app/p", List.of());
    }

    /** 把载体序列化为 STEP_PLAN 资产内容（与生产流水线同一 Jackson 配置）。 */
    private static AssetId saveStepPlanAsset(AssetRepository repo) throws Exception {
        String contentJson = MAPPER.writeValueAsString(sampleCarrier());
        return repo.save(new AssetDraft(AssetType.STEP_PLAN, "REQ-X",
                contentJson, Provenance.manual("page:sha256:abc")));
    }

    @Test
    public void loadsAssetRunsAndWritesBackReport() throws Exception {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        AssetId id = saveStepPlanAsset(repo);
        ScriptedHarness harness = new ScriptedHarness(r -> satisfyingSnapshot());

        VerificationReport report = new AssetStepPlanExecutor(repo, harness).run(id, new VerificationScope("https://app/p", 1));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertEquals(1.0, report.stability(), 1e-9);
        assertEquals(2, report.items().size());
        assertTrue("harness must be released after run", harness.released);
        // 回写：仓库中资产携带同一份报告
        StoredAsset after = repo.get(id);
        assertNotNull("report must be written back to asset repository", after.report());
        assertSame("executor must return the written-back report", report, after.report());
        assertEquals(VerificationReport.Status.PASS, after.report().status());
    }

    @Test
    public void failingExpectationWritesBackFail() throws Exception {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        AssetId id = saveStepPlanAsset(repo);
        ScriptedHarness harness = new ScriptedHarness(r -> failingSnapshot());

        VerificationReport report = new AssetStepPlanExecutor(repo, harness).run(id, new VerificationScope("https://app/p", 1));

        assertEquals(VerificationReport.Status.FAIL, report.status());
        assertEquals(0.0, report.stability(), 1e-9);
        StoredAsset after = repo.get(id);
        assertNotNull(after.report());
        assertEquals(VerificationReport.Status.FAIL, after.report().status());
        // 失败细节应体现 FILL 步骤未通过
        assertTrue(after.report().items().stream()
                .anyMatch(it -> it.ruleId().contains("FILL") && !it.passed()));
    }

    @Test
    public void flakyRunComputesStabilityAndWritesBackFlaky() throws Exception {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        AssetId id = saveStepPlanAsset(repo);
        // 第 1、3 次跑满足，第 2、4 次失败 -> 命中率 0.5 -> FLAKY
        ScriptedHarness harness = new ScriptedHarness(r -> (r % 2 == 1) ? satisfyingSnapshot() : failingSnapshot());

        VerificationReport report = new AssetStepPlanExecutor(repo, harness).run(id, new VerificationScope("https://app/p", 4));

        assertEquals(VerificationReport.Status.FLAKY, report.status());
        assertEquals(0.5, report.stability(), 1e-9);
        assertEquals(VerificationReport.Status.FLAKY, repo.get(id).report().status());
    }

    @Test
    public void rejectsNonStepPlanAsset() throws Exception {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        // 落一个 LINKAGE_GRAPH 资产（非 STEP_PLAN）
        AssetId id = repo.save(new AssetDraft(AssetType.LINKAGE_GRAPH, "REQ-Y", "{}", Provenance.manual("p")));
        ScriptedHarness harness = new ScriptedHarness(r -> satisfyingSnapshot());

        try {
            new AssetStepPlanExecutor(repo, harness).run(id, new VerificationScope("https://app/p", 1));
            fail("expected IllegalArgumentException for non-STEP_PLAN asset");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("STEP_PLAN"));
        }
    }

    @Test
    public void rejectsUnknownAsset() {
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        ScriptedHarness harness = new ScriptedHarness(r -> satisfyingSnapshot());

        try {
            new AssetStepPlanExecutor(repo, harness).run(new AssetId("asset:step_plan:999"), new VerificationScope("https://app/p", 1));
            fail("expected IllegalArgumentException for unknown asset");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unknown asset"));
        }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.BehaviorModel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventory;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventoryEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.PageUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline.AssetPipelineOrchestrator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline.PipelineResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.StaticRequirementSource;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.DeterministicAssetValidator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetState;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.InMemoryAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.JsonFileAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.RequirementCoverage;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationCoverageService;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Snapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.ManualPageObservationProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>闭环端到端资产流</b>（浏览器无关，纯单测）：把历次落地的独立能力串成一条真实链路——
 * <ol>
 *   <li>产线 {@link AssetPipelineOrchestrator} 真实产出四类 DRAFT 资产，其中 {@code STEP_PLAN}
 *       由 {@code StepPlanProducer} 从联动图谱派生并落库；</li>
 *   <li>资产落盘后<b>进程重启</b>（新仓库实例）读回 {@code STEP_PLAN}，
 *       {@link VerifiableStepPlan#fromJson} 以生产流水线同一配置还原载体；</li>
 *   <li>{@link AssetStepPlanExecutor} 经注入的假 {@link IsolationHarness} → {@link StepPlanRunner}
 *       → 统一入口 {@code StepVerifier.verify} 执行，产出 {@link VerificationReport} 并回写资产库；</li>
 *   <li>报告再次落盘/重启无损重建，最终由 {@link VerificationCoverageService} 聚合成
 *       {@link RequirementCoverage}。</li>
 * </ol>
 *
 * <p>唯一受控点：注入一个带单条 ENABLE 联动规则的 {@link PageUnderstandingEngine} 桩（规则引擎
 * 因单帧观测只能产空图谱），使产线 {@code STEP_PLAN} 带有<b>真实动作 + 执行后断言</b>；
 * 其余编排 / 派生 / 落库 / 读回 / 执行 / 回写 / 聚合全部走生产代码。不依赖真实 Playwright。</p>
 */
public class ClosedLoopAssetFlowTest {

    private static final String PAGE = "p-transfer";
    private static final String REQ = "REQ-101@1.0";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- 产线装配（真实生产代码，仅理解引擎用受控桩） ----

    private static AssetPipelineOrchestrator orchestrator(AssetRepository repo) {
        RequirementEntry req = new RequirementEntry(new RequirementId("REQ-101"), "Transfer amount",
                List.of("fill username then submit"), List.of(), "transfer.feature");
        RequirementContext ctx = new RequirementContext(List.of(req), "transfer.feature");
        PageObservation obs = new PageObservation("https://x/transfer", PAGE, List.of(), "manual");
        PageUnderstandingEngine engine = (requirement, observation) -> bundle();
        return new AssetPipelineOrchestrator(
                new StaticRequirementSource(ctx),
                new ManualPageObservationProvider(obs),
                engine,
                new DeterministicAssetValidator(),
                repo);
    }

    /** 一条联动：FILL(username, "alice") → CLICK(submit) 后 submit 变 ENABLE。与既有对账测试同构。 */
    private static AssetDraftBundle bundle() {
        ElementRef username = new ElementRef("username",
                List.of(LocatorSpec.roleOfKey("textbox", "user.key")), "textbox");
        ElementRef submit = new ElementRef("submit",
                List.of(LocatorSpec.roleOfKey("button", "submit.key")), "button");
        LinkageGraph graph = new LinkageGraph(REQ, List.of(
                new LinkageGraph.LinkageRule(username, Command.ActionType.FILL, "alice", List.of()),
                new LinkageGraph.LinkageRule(submit, Command.ActionType.CLICK, "", List.of(
                        new LinkageGraph.ExpectedChange(submit,
                                LinkageGraph.ExpectedChange.ChangeKind.ENABLE, null)))));
        return new AssetDraftBundle(
                new RequirementContext(List.of(), "transfer.feature"),
                new ElementInventory(List.of(
                        new ElementInventoryEntry(username, "用户名", REQ),
                        new ElementInventoryEntry(submit, "提交", REQ))),
                new BehaviorModel(List.of()),
                graph,
                Provenance.manual(PAGE),
                PAGE);
    }

    // ---- 假通道 / 假 harness（浏览器无关） ----

    private static final class FixedChannel implements BrowserCommandChannel {
        private final Snapshot snapshot;
        FixedChannel(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public CommandResult execute(Command command) { return CommandResult.ok(snapshot); }
    }

    /** 按第几次 acquire 回放不同快照，可记录目标 URL 与 acquire/release 次数。 */
    private static final class ScriptedHarness implements IsolationHarness {
        private final Function<Integer, Snapshot> snapshotForRun;
        private final AtomicInteger run = new AtomicInteger(0);
        String acquiredUrl;
        int acquired;
        int released;
        ScriptedHarness(Function<Integer, Snapshot> snapshotForRun) { this.snapshotForRun = snapshotForRun; }
        @Override
        public BrowserCommandChannel acquire(String targetUrl) {
            acquiredUrl = targetUrl;
            acquired++;
            return new FixedChannel(snapshotForRun.apply(run.incrementAndGet()));
        }
        @Override
        public void release(BrowserCommandChannel channel) { released++; }
    }

    /** 从读回载体的命令 target 派生「全部可见且启用」快照，使 ENABLE 期望通过。 */
    private static Snapshot satisfyingSnapshot(VerifiableStepPlan vsp) {
        List<Snapshot.ElementState> elements = new ArrayList<>();
        for (Command step : vsp.plan().steps()) {
            if (step.target() != null) {
                elements.add(new Snapshot.ElementState(
                        step.target().canonical(), "role", "name", true, true, ""));
            }
        }
        return new Snapshot(PAGE, elements);
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(PAGE, List.of());
    }

    private static StoredAsset stepPlanAsset(AssetRepository repo) {
        return repo.queryByRequirement(REQ).stream()
                .filter(a -> a.type() == AssetType.STEP_PLAN)
                .findFirst().orElseThrow();
    }

    // ---- 端到端用例 ----

    @Test
    public void producedAssetSurvivesRestartThenExecutesWritesBackAndAggregates() throws Exception {
        Path root = tmp.newFolder().toPath();
        // ① 产线真实产出并落盘
        PipelineResult produced = orchestrator(new JsonFileAssetRepository(root))
                .run("transfer.feature", PAGE);
        assertEquals(4, produced.savedIds().size());
        assertEquals(3, produced.plan().plan().steps().size());

        // ② 进程重启：从磁盘读回落库资产（而非复用内存里的对象）
        JsonFileAssetRepository repo = new JsonFileAssetRepository(root);
        StoredAsset asset = stepPlanAsset(repo);
        assertEquals(AssetState.DRAFT, asset.state());
        assertNull("asset not yet verified", asset.report());

        // ③ 落库 JSON 可被生产配置无损读回
        VerifiableStepPlan restored = VerifiableStepPlan.fromJson(asset.contentJson());
        assertEquals(PAGE, restored.pageRef());
        assertEquals(3, restored.plan().steps().size());

        // ④ 生产执行组件：假 harness → StepPlanRunner → StepVerifier → 回写报告
        ScriptedHarness harness = new ScriptedHarness(r -> satisfyingSnapshot(restored));
        VerificationReport report = new AssetStepPlanExecutor(repo, harness)
                .run(asset.id(), new VerificationScope("https://x/transfer", 2));

        assertEquals(VerificationReport.Status.PASS, report.status());
        assertEquals(1.0, report.stability(), 1e-9);
        assertEquals(3, report.items().size());
        assertEquals(2, harness.acquired);
        assertEquals(2, harness.released);
        assertEquals("执行目标须取自资产自带 pageRef，而非外部 scope.targetUrl", PAGE, harness.acquiredUrl);
        assertEquals(VerificationReport.Status.PASS, repo.get(asset.id()).report().status());

        // ⑤ 回写报告落盘后再次重启无损重建
        JsonFileAssetRepository reloaded = new JsonFileAssetRepository(root);
        VerificationReport persisted = reloaded.get(asset.id()).report();
        assertNotNull(persisted);
        assertEquals(VerificationReport.Status.PASS, persisted.status());
        assertEquals(1.0, persisted.stability(), 1e-9);
        assertEquals(3, persisted.items().size());
        assertEquals(report.evidence(), persisted.evidence());

        // ⑥ 覆盖度聚合消费回写报告：4 个资产中 1 个已验证且 PASS
        RequirementCoverage coverage = new VerificationCoverageService(reloaded).forRequirement(REQ);
        assertEquals(4, coverage.assetCount());
        assertEquals(1, coverage.verifiedCount());
        assertEquals(1, coverage.passedCount());
        assertEquals(0, coverage.failedCount());
        assertEquals(1.0, coverage.avgStability(), 1e-9);
    }

    @Test
    public void flakyExecutionPropagatesThroughReportToCoverage() {
        // 内存仓库跑同一条产线链路，验证「稳定性结论 → 回写 → 覆盖度聚合」的传导
        InMemoryAssetRepository repo = new InMemoryAssetRepository();
        orchestrator(repo).run("transfer.feature", PAGE);
        StoredAsset asset = stepPlanAsset(repo);
        VerifiableStepPlan restored = VerifiableStepPlan.fromJson(asset.contentJson());

        // 第 1、3 次跑满足期望，第 2、4 次不满足 → 命中率 0.5 → FLAKY
        ScriptedHarness harness = new ScriptedHarness(
                r -> (r % 2 != 0) ? satisfyingSnapshot(restored) : emptySnapshot());
        VerificationReport report = new AssetStepPlanExecutor(repo, harness)
                .run(asset.id(), new VerificationScope(PAGE, 4));

        assertEquals(VerificationReport.Status.FLAKY, report.status());
        assertEquals(0.5, report.stability(), 1e-9);
        assertTrue("失败步须出现在报告明细中",
                report.items().stream().anyMatch(it -> !it.passed()));
        assertEquals(VerificationReport.Status.FLAKY, repo.get(asset.id()).report().status());

        RequirementCoverage coverage = new VerificationCoverageService(repo).forRequirement(REQ);
        assertEquals(4, coverage.assetCount());
        assertEquals(1, coverage.verifiedCount());
        assertEquals(1, coverage.flakyCount());
        assertEquals(0, coverage.passedCount());
        assertEquals(0.5, coverage.avgStability(), 1e-9);
    }
}

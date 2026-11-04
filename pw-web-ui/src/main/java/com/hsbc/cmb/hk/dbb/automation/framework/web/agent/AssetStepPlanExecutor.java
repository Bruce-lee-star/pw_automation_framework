package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;

import java.util.ArrayList;
import java.util.List;

/**
 * 加载落库 {@code STEP_PLAN} 资产并经真实隔离靶场执行、把验证报告回写资产库的<b>生产执行组件</b>
 * （闭合「加载落库资产 + 真实 harness + StepPlanRunner + 回写 VerificationReport」这一此前只在测试里
 * 隐式存在的缺口，设计 §4.6 量化验收闭环）。
 *
 * <p>编排：{@link AssetRepository#get} 取出 {@code STEP_PLAN} 落库资产 →
 * {@link VerifiableStepPlan#fromJson} 以生产流水线同一 Jackson 配置读回载体（闭环在生产侧闭合）
 * → 注入的 {@link IsolationHarness}（pw-web-ui 生产态为 {@code RouteTestBedHarness} 的 Playwright 真实通道）
 * 经 {@link StepPlanRunner} 跑统一入口 {@code StepVerifier.verify}（与 {@code LinkageGraphVerifier} 验证侧
 * 同一闭环）→ 跨 {@link VerificationScope#repeat()} 次统计稳定性 → 产出 {@link VerificationReport}
 * → {@link AssetRepository#recordVerification} 回写资产本体（置信度 / 稳定性 / 逐条结论）。</p>
 *
 * <p>本类<b>浏览器无关</b>：不自行驱动浏览器，只依赖注入的契约（AssetRepository / IsolationHarness /
 * StepPlanRunner）。真实 harness 与资产库实现由 pw-web-ui 生产装配注入。不产生任何 LLM 调用（R6）。</p>
 */
public final class AssetStepPlanExecutor {

    private final AssetRepository repo;
    private final IsolationHarness harness;

    public AssetStepPlanExecutor(AssetRepository repo, IsolationHarness harness) {
        this.repo = repo;
        this.harness = harness;
    }

    /**
     * 执行指定 {@code STEP_PLAN} 资产并回写验证报告。
     *
     * @param id    资产标识（须为 {@link AssetType#STEP_PLAN}）
     * @param scope 验证范围（目标 URL + 重复次数）
     * @return 验证报告（同时已回写仓库）
     * @throws IllegalArgumentException 资产不存在或类型非 {@code STEP_PLAN}
     */
    public VerificationReport run(AssetId id, VerificationScope scope) {
        StoredAsset asset = repo.get(id);
        if (asset == null) {
            throw new IllegalArgumentException("Unknown asset: " + id);
        }
        if (asset.type() != AssetType.STEP_PLAN) {
            throw new IllegalArgumentException(
                    "AssetStepPlanExecutor 仅消费 STEP_PLAN 资产，given: " + asset.type() + " (" + id + ")");
        }
        VerifiableStepPlan vsp = VerifiableStepPlan.fromJson(asset.contentJson());

        StepPlanRunner runner = new StepPlanRunner(harness);
        int repeat = scope.repeat();
        int passRuns = 0;
        StepVerificationResult last = null;
        for (int i = 0; i < repeat; i++) {
            StepVerificationResult res = runner.run(vsp);
            last = res;
            if (res.allPassed()) {
                passRuns++;
            }
        }
        VerificationReport report = toReport(vsp, last, repeat, passRuns, id);
        repo.recordVerification(id, report);
        return report;
    }

    private static VerificationReport toReport(VerifiableStepPlan vsp, StepVerificationResult last,
                                               int repeat, int passRuns, AssetId id) {
        List<VerificationReport.VerificationItem> items = new ArrayList<>();
        List<Command> steps = vsp.plan().steps();
        List<SnapshotVerificationResult> perStep = last == null ? List.of() : last.perStep();
        for (int i = 0; i < steps.size(); i++) {
            Command step = steps.get(i);
            SnapshotVerificationResult svr = i < perStep.size() ? perStep.get(i) : null;
            boolean passed = svr != null && svr.allPassed();
            String detail = passed ? "ok" : (svr == null ? "no result" : firstFailure(svr));
            String ref = step.target() == null ? "<navigate>" : step.target().canonical();
            items.add(new VerificationReport.VerificationItem(
                    "step-" + i + ":" + step.action().name() + "@" + ref, passed, detail));
        }
        double stability = (double) passRuns / repeat;
        VerificationReport.Status status = stability >= 1.0
                ? VerificationReport.Status.PASS
                : (stability >= 0.5 ? VerificationReport.Status.FLAKY : VerificationReport.Status.FAIL);
        String evidence = "trace://step-plan/" + id + "?runs=" + repeat;
        return new VerificationReport(status, stability, items, evidence);
    }

    private static String firstFailure(SnapshotVerificationResult svr) {
        List<AssertionFailure> f = svr.failures();
        return f.isEmpty() ? "no snapshot" : f.get(0).getMessage();
    }
}

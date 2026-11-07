package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StepPlanApprovalBinding;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;
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
 *
 * <h2>审批绑定（T8）</h2>
 * <p>执行前对 {@code STEP_PLAN} 资产做<b>摘要校验</b>：若资产已审批（{@code StoredAsset#approvedDigest()}
 * 非 {@code null}），则由 {@link StepPlanApprovalBinding#verify} 校验其落库内容反算出的
 * {@code StepPlan.planDigest()} 与已审批摘要逐字一致，否则判定「审批后内容被篡改」并以
 * {@link IllegalStateException} 拒绝执行（fail-closed）。未审批（DRAFT/PENDING_REVIEW）或非 {@code STEP_PLAN}
 * 资产 {@code approvedDigest()} 为 {@code null}，此时不做绑定校验，保留开发/测试期直接跑 DRAFT 的既有行为。</p>
 *
 * <p><b>两套摘要的语义边界（务必区分）</b>：本执行域校验使用的是 {@code StepPlan.planDigest()}（前缀
 * {@code stepplan-v1}，覆盖 schemaVersion + 逐步 action/target/参数 + scope），与 pw-codegen 中
 * {@code LocatorCandidate.planDigestOf()}（前缀 {@code locator-v1}，覆盖定位器回注域的 targetRef/spec/expectedRole）
 * 是<b>两套互不相通</b>的体系。二者不可混用：本执行器只认 StepPlan 摘要，定位器回注域仍由
 * {@code ApprovalGrant#covers()} 把关，互不干扰。</p>
 *
 * <h2>运行时接线（T5）</h2>
 * <p>可注入 {@link AgentRuntimeContext}，使落库资产执行与业务链共享同一份路由/会话/并发/监控状态；
 * 默认 {@link AgentRuntimeContext#empty()}（四槽 no-op）保证既有调用方零回归。生产组合根
 * {@link AssetVerificationWiring#fromConfig()} 注入 SPI 聚合结果。</p>
 */
public final class AssetStepPlanExecutor {

    private final AssetRepository repo;
    private final IsolationHarness harness;
    private final AgentRuntimeContext runtime;

    /** 默认装配：不接业务链运行时，行为与 T5 之前一致。 */
    public AssetStepPlanExecutor(AssetRepository repo, IsolationHarness harness) {
        this(repo, harness, AgentRuntimeContext.empty());
    }

    /**
     * 完整装配（T5）。
     *
     * @param runtime 运行时角色组合；{@code null} 视为 {@link AgentRuntimeContext#empty()}
     */
    public AssetStepPlanExecutor(AssetRepository repo, IsolationHarness harness, AgentRuntimeContext runtime) {
        this.repo = repo;
        this.harness = harness;
        this.runtime = (runtime == null) ? AgentRuntimeContext.empty() : runtime;
    }

    /**
     * 执行指定 {@code STEP_PLAN} 资产并回写验证报告。
     *
     * <p>执行目标恒取资产自带的 {@link VerifiableStepPlan#pageRef()}（由 {@link StepPlanRunner}
     * 消费），而非 {@code scope.targetUrl()}——资产自描述、避免外部覆盖导致与落库内容不一致；
     * {@code scope} 在此仅提供 {@link VerificationScope#repeat()}（稳定性统计重复次数）。</p>
     *
     * @param id    资产标识（须为 {@link AssetType#STEP_PLAN}）
     * @param scope 验证范围（其重复次数用于稳定性统计；目标 URL 不覆盖资产 pageRef）
     * @return 验证报告（同时已回写仓库）
     * @throws IllegalArgumentException 资产不存在或类型非 {@code STEP_PLAN}
     * @throws IllegalStateException   已审批资产的内容摘要与审批时不一致（疑似篡改），拒绝执行
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
        // T8 审批绑定前置校验（与审批记录共用 StepPlanApprovalBinding 单一口径，fail-closed）。
        StepPlanApprovalBinding.verify(id, asset.approvedDigest(), asset.contentJson());

        VerifiableStepPlan vsp = VerifiableStepPlan.fromJson(asset.contentJson());
        StepPlanRunner runner = new StepPlanRunner(harness, null, runtime);
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

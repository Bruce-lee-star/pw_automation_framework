package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.AssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 联动图谱验证驱动的最小可用实现（设计 §3.1.2.1）。
 *
 * <p>编排：在隔离靶场 acquire 通道 → 把 {@link LinkageGraph} 经 {@link LinkageGraphStepPlans}
 * 派生为「可验证步骤计划」→ 跨 repeat 次执行 {@link StepVerifier}（授权执行 + 执行后联动断言）
 * → 统计稳定性 → 产出 {@link VerificationReport}。</p>
 *
 * <p>执行与断言统一收敛到闭环引擎：本类不再自建 Command / 内联快照比对，而是复用
 * {@code StepDriver}（参数范围门禁）+ {@code SnapshotVerifier}（绝对态字段断言）——与
 * pw-agent-analysis 资产生产流水线派生的 {@code VerifiableStepPlan} 走<b>同一个闭环入口</b>。
 * 不产生 LLM 调用（R6）。真正驱动 Playwright 的 BrowserCommandChannel 与 IsolationHarness
 * 由 pw-web-ui 基于 RouteTestBed 提供；本类只依赖契约（pw-core 内）。</p>
 */
public final class LinkageGraphVerifier implements AssetVerifier {

    private final IsolationHarness harness;
    /** 多语言目录（可选）：用于把资产里的 NLS key 解析成当前语言文案（设计 §4.7）。 */
    private final NlsCatalog nls;

    public LinkageGraphVerifier(IsolationHarness harness) {
        this(harness, null);
    }

    /**
     * @param nls 多语言目录；{@code null} 表示单语言环境（资产里只有字面文案）
     */
    public LinkageGraphVerifier(IsolationHarness harness, NlsCatalog nls) {
        this.harness = harness;
        this.nls = nls;
    }

    @Override
    public VerificationReport verify(LinkageGraph graph, VerificationScope scope) {
        BrowserCommandChannel channel = harness.acquire(scope.targetUrl());
        try {
            int passRuns = 0;
            List<VerificationReport.VerificationItem> lastRunItems = new ArrayList<>();
            final int repeat = scope.repeat();
            VerifiableStepPlan vsp = LinkageGraphStepPlans.toVerifiableStepPlan(graph, scope.targetUrl(), nls);
            for (int run = 0; run < repeat; run++) {
                StepVerificationResult result = StepVerifier.verify(vsp, channel);
                List<VerificationReport.VerificationItem> items = toItems(graph, result);
                lastRunItems = items;
                if (items.stream().allMatch(VerificationReport.VerificationItem::passed)) {
                    passRuns++;
                }
            }
            double stability = (double) passRuns / repeat;
            VerificationReport.Status status = stability >= 1.0
                    ? VerificationReport.Status.PASS
                    : (stability >= 0.5 ? VerificationReport.Status.FLAKY : VerificationReport.Status.FAIL);
            String evidence = "trace://linkage/" + graph.requirementId() + "?runs=" + repeat;
            return new VerificationReport(status, stability, lastRunItems, evidence);
        } finally {
            harness.release(channel);
        }
    }

    private static List<VerificationReport.VerificationItem> toItems(LinkageGraph graph, StepVerificationResult result) {
        List<VerificationReport.VerificationItem> items = new ArrayList<>();
        // 计划步骤布局：step0 = NAVIGATE（无断言），step(i+1) = 第 i 条规则的触发步骤
        for (int i = 0; i < graph.rules().size(); i++) {
            LinkageGraph.LinkageRule rule = graph.rules().get(i);
            String ruleId = rule.trigger().refId() + ":" + rule.triggerAction();
            SnapshotVerificationResult svr = result.perStep().get(i + 1);
            boolean passed = svr.allPassed();
            String detail = passed ? "ok" : firstFailure(svr);
            items.add(new VerificationReport.VerificationItem(ruleId, passed, detail));
        }
        return items;
    }

    private static String firstFailure(SnapshotVerificationResult svr) {
        List<AssertionFailure> f = svr.failures();
        return f.isEmpty() ? "no snapshot" : f.get(0).getMessage();
    }
}

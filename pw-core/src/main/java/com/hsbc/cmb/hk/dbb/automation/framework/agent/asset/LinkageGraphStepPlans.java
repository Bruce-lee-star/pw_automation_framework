package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联动图谱 → 可验证步骤计划 的构建器（闭环执行入口收敛，与 pw-agent-analysis 的
 * {@code StepPlanProducer} 对称）。
 *
 * <p>把 {@link LinkageGraph}（本就是「触发操作 + 期望后态」验证规格）派生为
 * {@link VerifiableStepPlan}：首步 {@link Command.ActionType#NAVIGATE}(pageRef)、每条
 * {@link LinkageGraph.LinkageRule} → 触发步骤（CLICK/FILL/SELECT/CHECK/UNCHECK，写入参数进入
 * {@link ParamScope} 治理域）、{@link LinkageGraph.ExpectedChange} → 逐步期望；无角色优先定位符
 * 的规则被跳过（与 {@code StepPlanProducer} 一致）。</p>
 *
 * <p><b>治理边界</b>：导航的 {@code url} 与副作用写入参数（{@code value}/{@code option}）都按
 * 「资产自身取值」声明允许域——门禁仍 fail-closed 且有意义（每个值都被显式授权），但不阻断
 * 合法验证（值即来自被验证的资产本身）。</p>
 *
 * <p><b>多语言</b>：触发与期望定位符均经 {@link LocatorSpec#localize(NlsCatalog)} 解析为当前语言
 * 文案，与 {@code LinkageGraphVerifier} 原三级回退中的「本地化精确命中」语义一致
 * （{@link ExpectedChangeMapper} 共享同一来源）。</p>
 */
public final class LinkageGraphStepPlans {

    private LinkageGraphStepPlans() {
    }

    public static VerifiableStepPlan toVerifiableStepPlan(LinkageGraph graph, String targetUrl, NlsCatalog nls) {
        List<Command> steps = new ArrayList<>();
        List<ParamScope> stepScopes = new ArrayList<>();
        List<List<SnapshotExpectation>> expectationsPerStep = new ArrayList<>();

        // 首步：导航到目标页面（pageRef 即导航目标）
        steps.add(Command.navigate(targetUrl));
        stepScopes.add(ParamScope.EMPTY);
        expectationsPerStep.add(List.of());

        for (LinkageGraph.LinkageRule rule : graph.rules()) {
            LocatorSpec triggerLoc = localize(rule.trigger().primaryLocator(), nls);
            steps.add(new Command(rule.triggerAction(), triggerLoc, triggerParams(rule)));
            stepScopes.add(triggerScope(rule));
            expectationsPerStep.add(ruleExpectations(rule, nls));
        }

        // plan 级 scope：导航 url 授权（其余副作用参数在 step 级按资产取值授权）
        ParamScope planScope = new ParamScope(Map.of("url", ParamConstraint.allowedValues(targetUrl)));
        StepPlan plan = new StepPlan(1, "linkage-" + graph.requirementId(), steps, planScope, stepScopes);
        return new VerifiableStepPlan(plan, expectationsPerStep, targetUrl);
    }

    private static LocatorSpec localize(LocatorSpec spec, NlsCatalog nls) {
        return spec == null ? null : spec.localize(nls);
    }

    private static Map<String, String> triggerParams(LinkageGraph.LinkageRule rule) {
        Map<String, String> p = new HashMap<>();
        String v = rule.triggerValue();
        if (v != null && !v.isEmpty()) {
            switch (rule.triggerAction()) {
                case FILL:   p.put("value", v); break;
                case SELECT: p.put("option", v); break;
                default:     break;
            }
        }
        return p;
    }

    private static ParamScope triggerScope(LinkageGraph.LinkageRule rule) {
        String v = rule.triggerValue();
        if (v == null || v.isEmpty()) {
            return ParamScope.EMPTY;
        }
        switch (rule.triggerAction()) {
            case FILL:   return new ParamScope(Map.of("value", ParamConstraint.allowedValues(v)));
            case SELECT: return new ParamScope(Map.of("option", ParamConstraint.allowedValues(v)));
            default:     return ParamScope.EMPTY;
        }
    }

    private static List<SnapshotExpectation> ruleExpectations(LinkageGraph.LinkageRule rule, NlsCatalog nls) {
        List<SnapshotExpectation> list = new ArrayList<>();
        for (LinkageGraph.ExpectedChange exp : rule.expectations()) {
            LocatorSpec targetLoc = localize(exp.target().primaryLocator(), nls);
            String ref = (targetLoc == null) ? null : targetLoc.canonical();
            SnapshotExpectation sexp = ExpectedChangeMapper.toExpectation(exp, ref, nls);
            if (sexp != null) {
                list.add(sexp);
            }
        }
        return list;
    }
}

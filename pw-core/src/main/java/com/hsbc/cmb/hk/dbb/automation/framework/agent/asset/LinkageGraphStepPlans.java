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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(LinkageGraphStepPlans.class);

    private LinkageGraphStepPlans() {
    }

    public static VerifiableStepPlan toVerifiableStepPlan(LinkageGraph graph, String targetUrl, NlsCatalog nls) {
        List<Command> steps = new ArrayList<>();
        List<ParamScope> stepScopes = new ArrayList<>();

        // 首步：导航到目标页面（pageRef 即导航目标）；导航无执行后期望
        steps.add(Command.navigate(targetUrl));
        stepScopes.add(ParamScope.EMPTY);

        for (LinkageGraph.LinkageRule rule : graph.rules()) {
            LocatorSpec triggerLoc = rule.trigger().primaryLocator();
            if (triggerLoc == null) {
                // 无角色优先定位符：确定性校验已 ERROR，跳过派生（与产线 StepPlanProducer 一致）。
                // DEBUG 仅作可观测性：运维可据此定位"某触发元素为何在该路径没有步骤"。
                LOG.debug("跳过无角色优先定位符的联动规则 requirement={} 触发refId={}（与产线 StepPlanProducer 一致）",
                        graph.requirementId(), rule.trigger().refId());
                continue;
            }
            // 期望内联进命令（一步 = 触发动作 + 其执行后绝对态期望）
            Command trigger = new Command(rule.triggerAction(), localize(triggerLoc, nls), triggerParams(rule))
                    .withExpectations(ruleExpectations(rule, nls));
            steps.add(trigger);
            stepScopes.add(ParamScope.forAssetWriteValue(rule.triggerAction(), rule.triggerValue()));
        }

        // plan 级 scope：导航 url 授权（其余副作用参数在 step 级按资产取值授权）
        ParamScope planScope = new ParamScope(Map.of("url", ParamConstraint.allowedValues(targetUrl)));
        StepPlan plan = new StepPlan(1, "linkage-" + graph.requirementId(), steps, planScope, stepScopes);
        return new VerifiableStepPlan(plan, targetUrl);
    }

    /**
     * 资产定位符的「角色优先 + 多语言」解析，是触发/期望定位符推导的<b>唯一来源</b>，
     * 与 {@code StepPlanProducer}（分析层产线路径）共用——两者对同一条规则必须产出同一个
     * localized 定位符，否则 {@link StepPlan#planDigest()} 指纹不一致（同一资产两条闭环路径对账失败）。
     *
     * @param spec 原始定位符，可为 {@code null}
     * @param nls  多语言目录，可为 {@code null}（缺失时原样返回）
     * @return {@code spec} 为 {@code null} 时返回 {@code null}；否则按当前语言解析 {@code nameKey}
     *         （{@link LocatorSpec#localize(NlsCatalog)}，catalog 或 key 缺失时原样返回）
     *
     * @apiNote 闭环引擎两条构建路径（{@code LinkageGraphStepPlans} 验证入口 与
     *          {@code StepPlanProducer} 产线入口）<b>必须</b>经本方法推导定位符，<b>不得</b>在任一侧
     *          另起本地化逻辑——否则会重新引入 NLS 下定位符 / 指纹双轨不一致（历史回归，已由
     *          {@code ClosedLoopPlanIsomorphismTest} 锁死）。本方法提升为 {@code public static} 仅为
     *          跨模块（pw-core ↔ pw-agent-analysis）共享同一来源，并非对外开放的业务 API。
     */
    public static LocatorSpec localize(LocatorSpec spec, NlsCatalog nls) {
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

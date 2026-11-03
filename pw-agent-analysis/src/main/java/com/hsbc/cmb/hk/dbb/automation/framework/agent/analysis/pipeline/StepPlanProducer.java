package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ExpectedChangeMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把资产生产流水线的产物转成「可执行 + 可验证」步骤计划（设计 §4.6 路径 B 延伸）。
 *
 * <p>闭环引擎接入资产生产流水线：从 {@link AssetDraftBundle} 的<b>联动图谱</b>（本就是
 * 「触发操作 + 期望后态」的验证规格）派生 {@link VerifiableStepPlan}——
 * <ul>
 *   <li>首步 {@code NAVIGATE} 到 {@code pageRef}，其 {@code url} 进入参数范围治理域；</li>
 *   <li>每条 {@link LinkageGraph.LinkageRule} → 一个触发步骤（FILL/SELECT/CLICK/CHECK/UNCHECK），
 *       其写入型参数（value / option）进入治理域白名单；</li>
 *   <li>每条规则的 {@link LinkageGraph.ExpectedChange} → 该步骤的执行后绝对态期望
 *       （复用 {@link ExpectedChangeMapper}，与 {@code LinkageGraphVerifier} 同一语义来源）。</li>
 * </ul>
 * 本类<b>零浏览器</b>：只生成计划与治理域，真实执行/验证交由 pw-web-ui 的 {@code StepPlanRunner}
 * 消费（经 pw-core 的 {@link VerifiableStepPlan} 载体流动，分析层不反向依赖 web-ui）。</p>
 */
public final class StepPlanProducer {

    private final NlsCatalog nls;

    public StepPlanProducer() {
        this(null);
    }

    public StepPlanProducer(NlsCatalog nls) {
        this.nls = nls;
    }

    /** 从资产草稿包派生「可验证步骤计划」。 */
    public VerifiableStepPlan produce(AssetDraftBundle bundle) {
        String pageRef = bundle.pageRef();
        List<Command> steps = new ArrayList<>();
        List<List<SnapshotExpectation>> expectationsPerStep = new ArrayList<>();

        // 首步：导航到来源页面（其 url 须进入治理域）
        steps.add(Command.navigate(pageRef));
        expectationsPerStep.add(List.of());

        Set<String> values = new LinkedHashSet<>();
        Set<String> options = new LinkedHashSet<>();

        for (LinkageGraph.LinkageRule rule : bundle.linkage().rules()) {
            LocatorSpec triggerLoc = rule.trigger().primaryLocator();
            if (triggerLoc == null) {
                continue; // 无角色优先定位符：确定性校验已 ERROR，跳过派生
            }
            steps.add(toCommand(rule, values, options));
            expectationsPerStep.add(toExpectations(rule));
        }

        ParamScope scope = buildScope(pageRef, values, options);
        StepPlan plan = new StepPlan(1, "plan-" + bundle.linkage().requirementId(),
                List.copyOf(steps), scope, List.of());
        return new VerifiableStepPlan(plan, List.copyOf(expectationsPerStep), pageRef);
    }

    private Command toCommand(LinkageGraph.LinkageRule rule, Set<String> values, Set<String> options) {
        LocatorSpec loc = rule.trigger().primaryLocator();
        return switch (rule.triggerAction()) {
            case FILL -> {
                values.add(rule.triggerValue());
                yield Command.fill(loc, rule.triggerValue());
            }
            case SELECT -> {
                options.add(rule.triggerValue());
                yield Command.select(loc, rule.triggerValue());
            }
            case CHECK -> new Command(Command.ActionType.CHECK, loc, Map.of());
            case UNCHECK -> new Command(Command.ActionType.UNCHECK, loc, Map.of());
            default -> Command.click(loc); // CLICK / 其它用户动作
        };
    }

    private List<SnapshotExpectation> toExpectations(LinkageGraph.LinkageRule rule) {
        List<SnapshotExpectation> out = new ArrayList<>();
        for (LinkageGraph.ExpectedChange ch : rule.expectations()) {
            LocatorSpec targetLoc = ch.target().primaryLocator();
            if (targetLoc == null) {
                continue;
            }
            SnapshotExpectation sexp = ExpectedChangeMapper.toExpectation(ch, targetLoc.canonical(), nls);
            if (sexp != null) {
                out.add(sexp);
            }
        }
        return List.copyOf(out);
    }

    private ParamScope buildScope(String pageRef, Set<String> values, Set<String> options) {
        Map<String, ParamConstraint> m = new java.util.HashMap<>();
        m.put("url", ParamConstraint.allowedValues(pageRef));
        if (!values.isEmpty()) {
            m.put("value", ParamConstraint.allowedValues(values.toArray(new String[0])));
        }
        if (!options.isEmpty()) {
            m.put("option", ParamConstraint.allowedValues(options.toArray(new String[0])));
        }
        return new ParamScope(m);
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ExpectedChangeMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraphStepPlans;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamConstraint;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(StepPlanProducer.class);

    private final NlsCatalog nls;

    public StepPlanProducer() {
        this(null);
    }

    public StepPlanProducer(NlsCatalog nls) {
        this.nls = nls;
    }

    /** 从资产草稿包派生「可验证步骤计划」（仅依赖图谱内嵌的触发值）。 */
    public VerifiableStepPlan produce(AssetDraftBundle bundle) {
        return produce(bundle, null);
    }

    /**
     * 从资产草稿包派生「可验证步骤计划」，并优先采用需求源声明的权威测试数据。
     *
     * <p>对于 FILL/SELECT 等写入型触发，若 {@code req} 为 {@code null} 或其测试数据未覆盖该
     * 触发元素的 refId，则回退到联动图谱内嵌的 {@code triggerValue()}；否则使用需求源的值，
     * 使派生的执行计划真正带有业务值而非图谱默认值。</p>
     *
     * @param bundle 资产草稿包
     * @param req    需求上下文（含测试数据），可为 {@code null}
     *
     * @apiNote 本产线入口与 pw-core 的验证入口 {@code LinkageGraphStepPlans} 构成闭环引擎的两条
     *          构建路径，二者产出<b>治理内容完全同构</b>（步骤 / 动作 / 本地化定位符 / 参数 / 逐步 scope /
     *          plan scope / 内联期望），仅写入值来源有意不同（本入口优先需求测试数据覆盖，验证入口用
     *          资产内嵌 {@code triggerValue}）。定位符一律经 {@code LinkageGraphStepPlans.localize}
     *          唯一来源推导，空定位符规则一致跳过；该同构契约由 {@code ClosedLoopPlanIsomorphismTest}
     *          锁定，防止任一侧漂移回双轨。
     */
    public VerifiableStepPlan produce(AssetDraftBundle bundle, RequirementContext req) {
        String pageRef = bundle.pageRef();
        List<Command> steps = new ArrayList<>();
        List<ParamScope> stepScopes = new ArrayList<>();

        // 首步：导航到来源页面；导航无副作用参数，step 级范围为空，url 由 plan 级授权
        steps.add(Command.navigate(pageRef));
        stepScopes.add(ParamScope.EMPTY);

        for (LinkageGraph.LinkageRule rule : bundle.linkage().rules()) {
            LocatorSpec triggerLoc = rule.trigger().primaryLocator();
            if (triggerLoc == null) {
                // 无角色优先定位符：确定性校验已 ERROR，跳过派生（与验证入口 LinkageGraphStepPlans 一致）。
                LOG.debug("跳过无角色优先定位符的联动规则 触发refId={}（与验证入口 LinkageGraphStepPlans 一致）",
                        rule.trigger().refId());
                continue;
            }
            // 一步 = 触发动作 + 其执行后绝对态期望（期望内联进命令）
            steps.add(toCommand(rule, req).withExpectations(toExpectations(rule)));
            // 该步的范围：副作用参数按资产取值精确授权（与 LinkageGraphStepPlans 同构）
            stepScopes.add(stepScopeFor(rule, req));
        }

        // plan 级 scope：仅声明导航 url（与 LinkageGraphStepPlans 对称）；
        // 副作用参数（value/option）在 step 级按资产取值授权，两条闭环路径结构一致、指纹可比对
        ParamScope planScope = new ParamScope(Map.of("url", ParamConstraint.allowedValues(pageRef)));
        StepPlan plan = new StepPlan(1, "plan-" + bundle.linkage().requirementId(),
                List.copyOf(steps), planScope, List.copyOf(stepScopes));
        return new VerifiableStepPlan(plan, pageRef);
    }

    private Command toCommand(LinkageGraph.LinkageRule rule, RequirementContext req) {
        // 触发定位符须经多语言解析（与 LinkageGraphStepPlans 同一来源），保证 NLS 下定位符同构
        LocatorSpec loc = LinkageGraphStepPlans.localize(rule.trigger().primaryLocator(), nls);
        String value = resolvedValue(rule, req);
        return switch (rule.triggerAction()) {
            case FILL -> Command.fill(loc, value);
            case SELECT -> Command.select(loc, value);
            case CHECK -> new Command(Command.ActionType.CHECK, loc, Map.of());
            case UNCHECK -> new Command(Command.ActionType.UNCHECK, loc, Map.of());
            default -> Command.click(loc); // CLICK / 其它用户动作
        };
    }

    /** 写入型触发取值：优先需求源测试数据，缺失回退图谱内嵌 triggerValue。 */
    private String resolvedValue(LinkageGraph.LinkageRule rule, RequirementContext req) {
        String override = (req == null) ? null : req.testValue(rule.trigger().refId());
        return (override != null && !override.isBlank()) ? override : rule.triggerValue();
    }

    /** 该步的参数范围：FILL→value、SELECT→option，按解析后取值精确授权。 */
    private ParamScope stepScopeFor(LinkageGraph.LinkageRule rule, RequirementContext req) {
        return ParamScope.forAssetWriteValue(rule.triggerAction(), resolvedValue(rule, req));
    }

    private List<SnapshotExpectation> toExpectations(LinkageGraph.LinkageRule rule) {
        List<SnapshotExpectation> out = new ArrayList<>();
        for (LinkageGraph.ExpectedChange ch : rule.expectations()) {
            // 期望目标定位符同样经多语言解析（与 LinkageGraphStepPlans 同一来源）
            LocatorSpec targetLoc = LinkageGraphStepPlans.localize(ch.target().primaryLocator(), nls);
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
}

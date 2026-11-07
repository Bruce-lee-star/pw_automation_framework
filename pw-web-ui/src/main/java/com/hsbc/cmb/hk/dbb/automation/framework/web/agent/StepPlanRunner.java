package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepDriver;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepIdempotency;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContexts;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeScope;

import java.util.List;
import java.util.Objects;

/**
 * 把 pw-core 的「授权执行 + 执行后联动断言」闭环接到真实浏览器执行底座（完整驱动引擎专项）。
 *
 * <p>组合三块已落地的最小构成：
 * <ol>
 *   <li>{@link IsolationHarness}（此处为 {@link RouteTestBedHarness} 的 Playwright 真实通道）—— 隔离靶场；</li>
 *   <li>{@link StepDriver} —— 参数范围门禁闭环（治理预检 + 逐步过门禁边界）；</li>
 *   <li>{@link StepVerifier} —— 执行后绝对态字段断言。</li>
 * </ol>
 * 本类不重新实现执行或断言，只做<b>编排</b>：经 harness 取真实通道 → 跑 {@code StepDriver}/{@code StepVerifier} → 释放通道。
 * 门禁 fail-closed 在通道边界默认方法内自动生效（{@code BrowserCommandChannel.execute(Command, ParamScope)}）。</p>
 *
 * <p>通道边界接线点：{@code PlaywrightChannel} 实现 {@code BrowserCommandChannel}，其 2-arg {@code execute(Command, ParamScope)}
 * 默认方法内置 {@code ParamScopeGate} 核对——故本闭环对接真通道时，参数范围门禁<b>零改动</b>即生效。</p>
 *
 * <h2>运行时组合根（T5 / I-06）</h2>
 * <p>每次执行包在 {@link AgentRuntimeContext#runInScope} 内：进入顺序 Concurrency → Session → Route →
 * Monitor，退出逆序，且异常时精确回滚。默认构造用 {@link AgentRuntimeContext#empty()}（四槽全 no-op），
 * <b>逐字保持 T5 之前的行为</b>；生产组合根 {@link AssetVerificationWiring#fromConfig()} 注入
 * {@link AgentRuntimeContexts#current()} 的 SPI 聚合结果，使执行链与业务链共享同一份
 * 路由/会话/并发/监控状态，消除孤岛。</p>
 *
 * <p>带 {@code sessionKey} 的重载供业务链在已登录身份下重放：该身份会透传给
 * {@code ConcurrencyGate} 适配器，与业务链 SSO 互斥用同一把闸门（profile 不进键）。</p>
 */
public final class StepPlanRunner {

    private final IsolationHarness harness;
    private final StepIdempotency idempotency;
    private final AgentRuntimeContext runtime;

    /** 默认构造：不做幂等记忆、不接业务链运行时（四槽 no-op），零回归。 */
    public StepPlanRunner(IsolationHarness harness) {
        this(harness, StepIdempotency.none(), AgentRuntimeContext.empty());
    }

    /**
     * 带步骤级幂等上下文的构造（T7）：同一计划重跑时，此前已通过且命令内容未变的步骤会被跳过。
     *
     * @param idempotency 幂等记忆（如 {@link com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.InMemoryStepIdempotency}）；
     *                     {@code null} 视为 {@link StepIdempotency#none()}
     */
    public StepPlanRunner(IsolationHarness harness, StepIdempotency idempotency) {
        this(harness, idempotency, AgentRuntimeContext.empty());
    }

    /**
     * 完整构造（T5）：显式注入运行时组合根。
     *
     * @param runtime 运行时角色组合；{@code null} 视为 {@link AgentRuntimeContext#empty()}
     */
    public StepPlanRunner(IsolationHarness harness, StepIdempotency idempotency, AgentRuntimeContext runtime) {
        this.harness = Objects.requireNonNull(harness, "harness");
        this.idempotency = (idempotency == null) ? StepIdempotency.none() : idempotency;
        this.runtime = (runtime == null) ? AgentRuntimeContext.empty() : runtime;
    }

    /** 授权执行 + 执行后联动断言（单次执行、自带断言；推荐入口）。 */
    public StepVerificationResult run(StepPlan plan, List<List<SnapshotExpectation>> expectations, String targetUrl) {
        return run(plan, expectations, targetUrl, null);
    }

    /** 带登录会话身份的重载（T5）：身份透传给并发闸门，无身份时传 {@code null}。 */
    public StepVerificationResult run(StepPlan plan, List<List<SnapshotExpectation>> expectations,
                                      String targetUrl, String sessionKey) {
        return runtime.runInScope(scopeOf(plan, targetUrl, sessionKey), () -> {
            BrowserCommandChannel channel = null;
            try {
                channel = harness.acquire(targetUrl);
                return StepVerifier.verify(plan, channel, expectations, idempotency);
            } finally {
                if (channel != null) {
                    harness.release(channel);
                }
            }
        });
    }

    /**
     * 直接消费分析层产出的「可验证计划」载体（闭环引擎接入资产生产流水线的另一端）。
     *
     * <p>pw-agent-analysis 生成的 {@link VerifiableStepPlan}（执行计划 + 逐步期望 + 页面引用）
     * 经 pw-core 数据载体流动到执行层，本重载直接喂给真实浏览器验证——分析层与执行层
     * 解耦，互不反向依赖。</p>
     */
    public StepVerificationResult run(VerifiableStepPlan vsp) {
        return run(vsp.plan(), vsp.expectationsPerStep(), vsp.pageRef());
    }

    /** 带登录会话身份的载体重载（T5）。 */
    public StepVerificationResult run(VerifiableStepPlan vsp, String sessionKey) {
        return run(vsp.plan(), vsp.expectationsPerStep(), vsp.pageRef(), sessionKey);
    }

    /** 仅授权执行（不带断言），返回每步结果。 */
    public List<CommandResult> execute(StepPlan plan, String targetUrl) {
        return execute(plan, targetUrl, null);
    }

    /** 带登录会话身份的仅执行重载（T5）。 */
    public List<CommandResult> execute(StepPlan plan, String targetUrl, String sessionKey) {
        return runtime.runInScope(scopeOf(plan, targetUrl, sessionKey), () -> {
            BrowserCommandChannel channel = null;
            try {
                channel = harness.acquire(targetUrl);
                return StepDriver.execute(plan, channel, idempotency);
            } finally {
                if (channel != null) {
                    harness.release(channel);
                }
            }
        });
    }

    private static AgentRuntimeScope scopeOf(StepPlan plan, String targetUrl, String sessionKey) {
        Objects.requireNonNull(plan, "plan");
        return AgentRuntimeScope.of(plan.planId(), targetUrl, sessionKey);
    }
}

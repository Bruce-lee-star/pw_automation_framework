package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepIdempotency;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;

import java.util.List;

/**
 * 把 pw-core 的「授权执行 + 执行后联动断言」闭环接到真实浏览器执行底座（完整驱动引擎专项）。
 *
 * <p><b>T10 起本类是兼容委托门面</b>：所有执行已收敛到 {@link AgentDriver} 的唯一编排模板
 * （作用域 → 计时 → acquire → 引擎 → release → 样本上报）。本类<b>不再持有编排逻辑</b>，
 * 只把既有公开签名逐字转发给门面，从而保证 3 个构造器 + 7 个 public 方法的调用方零回归，
 * 同时消除原先 {@code run} / {@code execute} 各写一份 acquire-release 编排的重复实现。</p>
 *
 * <p>新代码请直接使用 {@link AgentDriver}；本类保留用于既有业务与测试的平滑迁移。</p>
 *
 * <h2>运行时组合根（T5 / I-06）</h2>
 * <p>默认构造仍等价于 {@code AgentRuntimeContext.empty()}（四槽全 no-op，逐字保持 T5 之前行为）；
 * 生产组合根由 {@code AssetVerificationWiring.fromConfig()} 经 {@code AgentDriver.fromConfig()}
 * 注入 SPI 聚合结果，使执行链与业务链共享同一份路由/会话/并发/监控状态。</p>
 *
 * <p>带 {@code sessionKey} 的重载供业务链在已登录身份下重放：该身份会透传给并发闸门适配器，
 * 与业务链 SSO 互斥用同一把闸门（profile 不进键）。</p>
 */
public final class StepPlanRunner {

    private final AgentDriver driver;

    /** 默认构造：不做幂等记忆、不接业务链运行时（四槽 no-op），零回归。 */
    public StepPlanRunner(IsolationHarness harness) {
        this(harness, StepIdempotency.none(), AgentRuntimeContext.empty());
    }

    /**
     * 带步骤级幂等上下文的构造（T7）：同一计划重跑时，此前已通过且命令内容未变的步骤会被跳过。
     *
     * @param idempotency 幂等记忆（如 {@code InMemoryStepIdempotency}）；{@code null} 视为 {@link StepIdempotency#none()}
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
        this.driver = new AgentDriver(harness, idempotency, runtime);
    }

    /** 授权执行 + 执行后联动断言（单次执行、自带断言）。 */
    public StepVerificationResult run(StepPlan plan, List<List<SnapshotExpectation>> expectations, String targetUrl) {
        return driver.run(plan, expectations, targetUrl);
    }

    /** 带登录会话身份的重载（T5）：身份透传给并发闸门，无身份时传 {@code null}。 */
    public StepVerificationResult run(StepPlan plan, List<List<SnapshotExpectation>> expectations,
                                      String targetUrl, String sessionKey) {
        return driver.run(plan, expectations, targetUrl, sessionKey);
    }

    /**
     * 直接消费分析层产出的「可验证计划」载体（闭环引擎接入资产生产流水线的另一端）。
     *
     * <p>pw-agent-analysis 生成的 {@link VerifiableStepPlan}（执行计划 + 逐步期望 + 页面引用）
     * 经 pw-core 数据载体流动到执行层；目标恒取 {@code pageRef()}，期望取
     * {@link VerifiableStepPlan#expectationsPerStep()}。</p>
     */
    public StepVerificationResult run(VerifiableStepPlan vsp) {
        return driver.run(vsp);
    }

    /** 带登录会话身份的载体重载（T5）。 */
    public StepVerificationResult run(VerifiableStepPlan vsp, String sessionKey) {
        return driver.run(vsp, sessionKey);
    }

    /** 仅授权执行（不带断言），返回每步结果。 */
    public List<CommandResult> execute(StepPlan plan, String targetUrl) {
        return driver.execute(plan, targetUrl);
    }

    /** 带登录会话身份的仅执行重载（T5）。 */
    public List<CommandResult> execute(StepPlan plan, String targetUrl, String sessionKey) {
        return driver.execute(plan, targetUrl, sessionKey);
    }
}

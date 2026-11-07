package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.JsonFileAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.BrowserCommandChannel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.CommandResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.SnapshotExpectation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepDriver;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepIdempotency;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerificationResult;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepVerifier;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfProbe;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContexts;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeScope;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * <b>Agent 执行链的唯一生产门面</b>（T10 / I-10）：把 {@code StepPlan}、{@code VerifiableStepPlan}
 * 与落库 {@code STEP_PLAN} 资产三类入口收敛到<b>一个入口、一套执行语义</b>。
 *
 * <p><b>为什么需要它</b>：T10 之前 {@link StepPlanRunner} 的 {@code run} 与 {@code execute}
 * 各写了一份「acquire → 引擎 → finally release」编排，而 {@link AssetStepPlanExecutor}
 * 又自行 {@code new StepPlanRunner(...)}，形成三处可漂移的执行路径。本类把该编排收成
 * 唯一的 {@link #withChannel} 模板：所有入口只做<b>参数形态归一化</b>，再交给同一模板，
 * 因此「授权执行 / 联动断言 / 资产验证」不可能出现两套语义。</p>
 *
 * <h2>入口分层</h2>
 * <ul>
 *   <li>{@code run(StepPlan, expectations, targetUrl[, sessionKey])} —— 带联动断言；</li>
 *   <li>{@code run(VerifiableStepPlan[, sessionKey])} —— 分析层载体，拆包成上一条，
 *       期望取自 {@link VerifiableStepPlan#expectationsPerStep()}、目标取 {@code pageRef()}；</li>
 *   <li>{@code execute(StepPlan, targetUrl[, sessionKey])} —— 仅授权执行，不带断言；</li>
 *   <li>{@code verify(AssetId, VerificationScope)} —— 落库资产入口：审批校验、重复执行、
 *       稳定性统计与报告回写由 {@link AssetStepPlanExecutor} 承担，但每轮执行仍回调本门面。</li>
 * </ul>
 * <p>{@link StepPlanRunner} / {@link AssetStepPlanExecutor} / {@link AssetVerificationWiring}
 * 的既有公开签名<b>全部保留</b>为薄委托（公开 API 零回归），新代码应直接使用本门面。</p>
 *
 * <h2>语义保真（改动时不得破坏）</h2>
 * <ul>
 *   <li>每次执行包进 {@link AgentRuntimeContext#runInScope}（T5：进入序 Concurrency → Session →
 *       Route → Monitor，逆序退出；默认 {@link AgentRuntimeContext#empty()} 四槽 no-op）；</li>
 *   <li>T9 计时与并发观测：每计划一次 {@link AgentPerfProbe#refresh()}，计时覆盖
 *       acquire/release 与整个作用域，异常路径同样记录失败样本且并发计数严格配对；</li>
 *   <li>资产路径的 {@code repeat} 仍然<b>每轮</b>开一次作用域与通道（不得把作用域提到循环外，
 *       否则会改变 T5 闸门配对与 T9 的 plan 样本数）；执行目标恒取资产 {@code pageRef}，
 *       {@code VerificationScope.targetUrl()} 不覆盖它；</li>
 *   <li>T8 审批绑定前置校验保留在 {@link AssetStepPlanExecutor#run}，发生在任何 acquire 之前；</li>
 *   <li>幂等 seam 使用带 {@link StepIdempotency} 的 4 参 {@code StepVerifier.verify}，
 *       <b>不可</b>退化为固定 {@code none()} 的 {@code verify(VerifiableStepPlan, channel)} 重载。</li>
 * </ul>
 *
 * <h2>装配</h2>
 * <p>显式构造不做隐式 SPI 解析（同 T5 惯例）；只有 {@link #fromConfig()} 解析配置并装配
 * {@link AgentRuntimeContexts#current()}、{@link JsonFileAssetRepository} 与
 * {@link RouteTestBedHarness}，作为唯一生产组合根。{@link #close()} 幂等。</p>
 */
public final class AgentDriver implements AutoCloseable {

    /** 资产仓库根目录配置键（与 {@link AssetVerificationWiring} 共享同一常量源）。 */
    public static final String ASSET_ROOT_KEY = "pw.agent.asset.root";
    /** 资产仓库根目录默认值。 */
    public static final String DEFAULT_ASSET_ROOT = "target/pw-assets";

    /**
     * 框架内部 seam：资产执行的「每轮怎么跑」由门面注入，避免
     * {@link AssetStepPlanExecutor} 自行创建执行器而形成第二条编排路径。
     */
    @FunctionalInterface
    interface PlanExecution {
        StepVerificationResult run(VerifiableStepPlan vsp);
    }

    private final IsolationHarness harness;
    private final StepIdempotency idempotency;
    private final AgentRuntimeContext runtime;
    private final AssetRepository repository;

    /** 最简构造：无幂等记忆、无业务链运行时、无资产库（资产入口需显式配置）。 */
    public AgentDriver(IsolationHarness harness) {
        this(harness, StepIdempotency.none(), AgentRuntimeContext.empty(), null);
    }

    /** 带步骤级幂等上下文（T7）。 */
    public AgentDriver(IsolationHarness harness, StepIdempotency idempotency) {
        this(harness, idempotency, AgentRuntimeContext.empty(), null);
    }

    /** 显式注入运行时组合根（T5）；{@code null} 回落为 {@link AgentRuntimeContext#empty()}。 */
    public AgentDriver(IsolationHarness harness, StepIdempotency idempotency, AgentRuntimeContext runtime) {
        this(harness, idempotency, runtime, null);
    }

    /**
     * 完整装配。
     *
     * @param repository 资产仓库；仅 {@link #verify} 需要，{@code null} 时调用资产入口会 fail-fast
     */
    public AgentDriver(IsolationHarness harness, StepIdempotency idempotency,
                       AgentRuntimeContext runtime, AssetRepository repository) {
        this.harness = Objects.requireNonNull(harness, "harness");
        this.idempotency = (idempotency == null) ? StepIdempotency.none() : idempotency;
        this.runtime = (runtime == null) ? AgentRuntimeContext.empty() : runtime;
        this.repository = repository;
    }

    /** 按框架配置装配真实实现（单语言环境），唯一生产组合根。 */
    public static AgentDriver fromConfig() {
        return fromConfig(null);
    }

    /**
     * 按框架配置装配真实实现：文件资产库 + 真实隔离靶场 + SPI 聚合运行时。
     *
     * @param nls 多语言目录；{@code null} 表示单语言环境
     */
    public static AgentDriver fromConfig(NlsCatalog nls) {
        String root = ConfigReader.getProperty(ASSET_ROOT_KEY, DEFAULT_ASSET_ROOT);
        Path assetRoot = Paths.get(root == null || root.isBlank() ? DEFAULT_ASSET_ROOT : root);
        return new AgentDriver(
                new RouteTestBedHarness(nls),
                StepIdempotency.none(),
                AgentRuntimeContexts.current(),
                new JsonFileAssetRepository(assetRoot));
    }

    /** 授权执行 + 执行后联动断言。 */
    public StepVerificationResult run(StepPlan plan, List<List<SnapshotExpectation>> expectations,
                                      String targetUrl) {
        return run(plan, expectations, targetUrl, null);
    }

    /** 带登录会话身份的联动断言入口（身份透传给并发闸门）。 */
    public StepVerificationResult run(StepPlan plan, List<List<SnapshotExpectation>> expectations,
                                      String targetUrl, String sessionKey) {
        Objects.requireNonNull(plan, "plan");
        return withChannel(plan, targetUrl, sessionKey,
                channel -> StepVerifier.verify(plan, channel, expectations, idempotency));
    }

    /** 分析层载体入口：拆包为计划 + 逐步期望 + pageRef，走同一模板。 */
    public StepVerificationResult run(VerifiableStepPlan vsp) {
        Objects.requireNonNull(vsp, "vsp");
        return run(vsp.plan(), vsp.expectationsPerStep(), vsp.pageRef(), null);
    }

    /** 带登录会话身份的载体入口。 */
    public StepVerificationResult run(VerifiableStepPlan vsp, String sessionKey) {
        Objects.requireNonNull(vsp, "vsp");
        return run(vsp.plan(), vsp.expectationsPerStep(), vsp.pageRef(), sessionKey);
    }

    /** 仅授权执行（不带断言），返回每步结果。 */
    public List<CommandResult> execute(StepPlan plan, String targetUrl) {
        return execute(plan, targetUrl, null);
    }

    /** 带登录会话身份的仅执行入口。 */
    public List<CommandResult> execute(StepPlan plan, String targetUrl, String sessionKey) {
        Objects.requireNonNull(plan, "plan");
        return withChannel(plan, targetUrl, sessionKey,
                channel -> StepDriver.execute(plan, channel, idempotency));
    }

    /**
     * 落库资产入口：把「每轮怎么跑」注入 {@link AssetStepPlanExecutor}，由它完成审批校验、
     * repeat / 稳定性统计与报告回写。此处不创建长期持有的执行器，避免与执行器形成循环引用。
     *
     * @throws IllegalStateException 未配置 {@link AssetRepository}
     */
    public VerificationReport verify(AssetId id, VerificationScope scope) {
        if (repository == null) {
            throw new IllegalStateException(
                    "AssetRepository is not configured; use fromConfig() or the 4-arg AgentDriver constructor");
        }
        return new AssetStepPlanExecutor(repository, this::run).run(id, scope);
    }

    /**
     * <b>唯一的执行编排模板</b>：作用域 → 计时 → acquire → 引擎 → release → 样本上报。
     * 所有入口（含资产 repeat 的每一轮）都经这里，故不存在第二套执行语义。
     *
     * <p>通道释放放在内层 finally，保证异常路径也释放；T9 样本上报放在外层 finally，
     * 保证 {@code failure} 已被捕获且并发计数必定配对。执行体异常原样上抛，不被收尾异常掩盖。</p>
     */
    private <T> T withChannel(StepPlan plan, String targetUrl, String sessionKey,
                              Function<BrowserCommandChannel, T> action) {
        Objects.requireNonNull(plan, "plan");
        AgentPerfProbe.refresh();
        AgentRuntimeScope scope = AgentRuntimeScope.of(plan.planId(), targetUrl, sessionKey);
        return runtime.runInScope(scope, () -> {
            long startedAt = AgentPerfProbe.start();
            int inFlight = AgentPerfProbe.enterScope();
            Throwable failure = null;
            try {
                BrowserCommandChannel channel = null;
                try {
                    channel = harness.acquire(targetUrl);
                    return action.apply(channel);
                } finally {
                    if (channel != null) {
                        harness.release(channel);
                    }
                }
            } catch (RuntimeException | Error e) {
                failure = e;
                throw e;
            } finally {
                try {
                    AgentPerfProbe.recordPlan(startedAt, plan.steps().size(),
                            failure == null, failure, inFlight);
                } finally {
                    AgentPerfProbe.exitScope(inFlight);
                }
            }
        });
    }

    public IsolationHarness harness() {
        return harness;
    }

    public AssetRepository repository() {
        return repository;
    }

    public AgentRuntimeContext runtime() {
        return runtime;
    }

    /** 幂等收口：真实靶场关闭所有通道与 Playwright 实例。 */
    @Override
    public void close() {
        if (harness instanceof RouteTestBedHarness testBed) {
            testBed.closeAll();
        }
    }
}

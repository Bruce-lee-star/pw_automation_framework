package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.RequirementCoverage;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationCoverageService;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepIdempotency;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;

import java.util.List;

/**
 * <b>资产验证闭环的真实生产接线</b>（组合根 / composition root）：把真实资产仓库实现与真实
 * Playwright 隔离靶场注入 {@link AgentDriver}，并附带生命周期收口。
 *
 * <p><b>T10 起执行入口统一</b>：{@link #verify} 委托 {@link AgentDriver#verify}，
 * {@code executor()} 返回的 {@link AssetStepPlanExecutor} 也用同一个 {@code AgentDriver::run}
 * 注入，故「直接调 executor」与「经 wiring」不可能出现两套执行语义。本类仍保留需求资产查询
 * 与覆盖度聚合等<b>非执行</b>能力。</p>
 *
 * <p>默认装配（{@link #fromConfig()}）：</p>
 * <ul>
 *   <li>{@code JsonFileAssetRepository} —— 文件型资产仓库（资产本体 JSON + 索引，进程重启可重建），
 *       根目录取配置键 {@link #ASSET_ROOT_KEY}，默认 {@link #DEFAULT_ASSET_ROOT}；</li>
 *   <li>{@link RouteTestBedHarness} —— 真实 Playwright 隔离靶场（可注入 {@link NlsCatalog}）；</li>
 *   <li>{@link AgentDriver} + {@link VerificationCoverageService} —— 唯一执行门面与需求覆盖度聚合。</li>
 * </ul>
 *
 * <p><b>靶场约束（R4/R8，fail-closed）</b>：执行目标恒取落库 {@code STEP_PLAN} 资产自带的
 * {@code pageRef}，{@code VerificationScope.targetUrl()} <b>不会</b>覆盖它。因此产线落库时资产的
 * {@code pageRef} 必须是靶场可达目标——{@code file://} 本地 fixture、loopback，或配置键
 * {@code pw.agent.testbed.allowedHosts} 显式列出的主机。{@link RouteTestBedHarness#acquire(String)}
 * 在 {@code Playwright.create()} 之前即校验目标，非靶场目标抛
 * {@link TestBedTargetRejectedException}，不会启动浏览器、不会误指向生产环境。</p>
 *
 * <p><b>生命周期</b>：实现 {@link AutoCloseable}，用完须关闭（推荐 try-with-resources），
 * {@link #close()} 委托门面关闭所有通道与 Playwright 实例，幂等。零 LLM 调用（R6）。</p>
 *
 * <pre>{@code
 * try (AssetVerificationWiring wiring = AssetVerificationWiring.fromConfig()) {
 *     for (StoredAsset a : wiring.stepPlanAssetsFor("REQ-101@1.0")) {
 *         VerificationReport report = wiring.verify(a.id(), new VerificationScope("ignored", 2));
 *         // report 已回写资产库
 *     }
 *     RequirementCoverage c = wiring.coverage("REQ-101@1.0");
 * }
 * }</pre>
 */
public final class AssetVerificationWiring implements AutoCloseable {

    /** 资产仓库根目录配置键（单一事实来源在 {@link AgentDriver}）。 */
    public static final String ASSET_ROOT_KEY = AgentDriver.ASSET_ROOT_KEY;
    /** 资产仓库根目录默认值（相对工作目录）。 */
    public static final String DEFAULT_ASSET_ROOT = AgentDriver.DEFAULT_ASSET_ROOT;

    private final AssetRepository repository;
    private final IsolationHarness harness;
    private final AgentDriver driver;
    private final AssetStepPlanExecutor executor;
    private final VerificationCoverageService coverageService;

    /**
     * 显式装配（便于替换实现 / 浏览器无关单测注入假 harness）。
     *
     * @param repository 资产仓库（生产为 {@code JsonFileAssetRepository}）
     * @param harness    隔离靶场（生产为 {@link RouteTestBedHarness}）
     */
    public AssetVerificationWiring(AssetRepository repository, IsolationHarness harness) {
        this(repository, harness, AgentRuntimeContext.empty());
    }

    /**
     * 完整装配（T5）：显式注入运行时组合根；{@code null} 视为 {@link AgentRuntimeContext#empty()}。
     * 供宿主/测试精确控制角色实现，避免隐式 SPI 解析。
     */
    public AssetVerificationWiring(AssetRepository repository, IsolationHarness harness,
                                   AgentRuntimeContext runtime) {
        this(createDriver(repository, harness, runtime));
    }

    /** 由已装配的唯一门面构造（T10）：{@link #fromConfig} 与显式构造共用这一条装配路径。 */
    private AssetVerificationWiring(AgentDriver driver) {
        this.repository = driver.repository();
        this.harness = driver.harness();
        this.driver = driver;
        this.executor = new AssetStepPlanExecutor(this.repository, driver::run);
        this.coverageService = new VerificationCoverageService(this.repository);
    }

    private static AgentDriver createDriver(AssetRepository repository, IsolationHarness harness,
                                            AgentRuntimeContext runtime) {
        // 保留既有异常契约：显式构造对 null 入参抛 IllegalArgumentException（而非裸 NPE）。
        if (repository == null || harness == null) {
            throw new IllegalArgumentException("repository and harness are required");
        }
        return new AgentDriver(harness, StepIdempotency.none(), runtime, repository);
    }

    /** 按框架配置装配真实实现（单语言环境）。 */
    public static AssetVerificationWiring fromConfig() {
        return fromConfig(null);
    }

    /**
     * 按框架配置装配真实实现：唯一生产组合根在 {@link AgentDriver#fromConfig(NlsCatalog)}，
     * 本方法只做一层包装，避免仓库/靶场/运行时被装配两次。
     *
     * @param nls 多语言目录；{@code null} 表示单语言环境
     */
    public static AssetVerificationWiring fromConfig(NlsCatalog nls) {
        return new AssetVerificationWiring(AgentDriver.fromConfig(nls));
    }

    /** 执行指定 {@code STEP_PLAN} 资产并回写报告（统一走执行门面）。 */
    public VerificationReport verify(AssetId id, VerificationScope scope) {
        return driver.verify(id, scope);
    }

    /** 列出某需求下全部 {@code STEP_PLAN} 资产，供调用方挑选待验证 id。 */
    public List<StoredAsset> stepPlanAssetsFor(String requirementId) {
        return repository.queryByRequirement(requirementId).stream()
                .filter(a -> a.type() == AssetType.STEP_PLAN)
                .toList();
    }

    /** 聚合某需求的验证覆盖度（消费已回写的报告）。 */
    public RequirementCoverage coverage(String requirementId) {
        return coverageService.forRequirement(requirementId);
    }

    public AssetRepository repository() {
        return repository;
    }

    public IsolationHarness harness() {
        return harness;
    }

    /** 兼容既有调用方；内部执行已注入同一门面，与 {@link #verify} 语义一致。 */
    public AssetStepPlanExecutor executor() {
        return executor;
    }

    /** 唯一执行门面（T10）：新代码可直接经它跑任意计划 / 载体 / 资产。 */
    public AgentDriver driver() {
        return driver;
    }

    @Override
    public void close() {
        driver.close();
    }
}

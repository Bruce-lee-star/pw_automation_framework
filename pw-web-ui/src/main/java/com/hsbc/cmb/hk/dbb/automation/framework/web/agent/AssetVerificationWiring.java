package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.JsonFileAssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.RequirementCoverage;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.StoredAsset;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationCoverageService;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.VerificationScope;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.harness.IsolationHarness;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime.AgentRuntimeContexts;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * <b>资产验证闭环的真实生产接线</b>（组合根 / composition root）：把真实资产仓库实现与真实
 * Playwright 隔离靶场注入 {@link AssetStepPlanExecutor}，并附带生命周期收口。
 *
 * <p>默认装配（{@link #fromConfig()}）：</p>
 * <ul>
 *   <li>{@link JsonFileAssetRepository} —— 文件型资产仓库（资产本体 JSON + 索引，进程重启可重建），
 *       根目录取配置键 {@link #ASSET_ROOT_KEY}，默认 {@link #DEFAULT_ASSET_ROOT}；</li>
 *   <li>{@link RouteTestBedHarness} —— 真实 Playwright 隔离靶场（可注入 {@link NlsCatalog}）；</li>
 *   <li>{@link AssetStepPlanExecutor} + {@link VerificationCoverageService} —— 执行/回写与需求覆盖度聚合。</li>
 * </ul>
 *
 * <p><b>靶场约束（R4/R8，fail-closed）</b>：{@link AssetStepPlanExecutor} 的执行目标恒取落库
 * {@code STEP_PLAN} 资产自带的 {@code pageRef}（见其 Javadoc），{@code VerificationScope.targetUrl()}
 * <b>不会</b>覆盖它。因此产线落库时资产的 {@code pageRef} 必须是靶场可达目标——{@code file://}
 * 本地 fixture、loopback，或配置键 {@code pw.agent.testbed.allowedHosts} 显式列出的主机。
 * 本接线层<b>不绕过、不重复实现</b>该护栏：{@link RouteTestBedHarness#acquire(String)} 在
 * {@code Playwright.create()} 之前即校验目标，非靶场目标抛 {@link TestBedTargetRejectedException}，
 * 不会启动浏览器、不会误指向生产环境。</p>
 *
 * <p><b>生命周期</b>：实现 {@link AutoCloseable}，用完须关闭（推荐 try-with-resources），
 * {@link #close()} 委托 {@link RouteTestBedHarness#closeAll()} 兜底关闭所有通道与 Playwright 实例，
 * 避免 Node 子进程泄漏。零 LLM 调用（R6）。</p>
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

    /** 资产仓库根目录配置键。 */
    public static final String ASSET_ROOT_KEY = "pw.agent.asset.root";
    /** 资产仓库根目录默认值（相对工作目录）。 */
    public static final String DEFAULT_ASSET_ROOT = "target/pw-assets";

    private final AssetRepository repository;
    private final IsolationHarness harness;
    private final AssetStepPlanExecutor executor;
    private final VerificationCoverageService coverageService;

    /**
     * 显式装配（便于替换实现 / 浏览器无关单测注入假 harness）。
     *
     * @param repository 资产仓库（生产为 {@link JsonFileAssetRepository}）
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
        if (repository == null || harness == null) {
            throw new IllegalArgumentException("repository and harness are required");
        }
        this.repository = repository;
        this.harness = harness;
        this.executor = new AssetStepPlanExecutor(repository, harness, runtime);
        this.coverageService = new VerificationCoverageService(repository);
    }

    /** 按框架配置装配真实实现（单语言环境）。 */
    public static AssetVerificationWiring fromConfig() {
        return fromConfig(null);
    }

    /**
     * 按框架配置装配真实实现。
     *
     * @param nls 多语言目录；{@code null} 表示单语言环境
     */
    public static AssetVerificationWiring fromConfig(NlsCatalog nls) {
        String root = ConfigReader.getProperty(ASSET_ROOT_KEY, DEFAULT_ASSET_ROOT);
        Path assetRoot = Paths.get(root == null || root.isBlank() ? DEFAULT_ASSET_ROOT : root);
        // T5：生产组合根装配 SPI 聚合的运行时角色（Route/Session/Concurrency/Monitor），
        // 使落库资产执行与业务链共享同一份状态；显式 2/3 参构造不受影响（零回归）。
        return new AssetVerificationWiring(
                new JsonFileAssetRepository(assetRoot),
                new RouteTestBedHarness(nls),
                AgentRuntimeContexts.current());
    }

    /** 执行指定 {@code STEP_PLAN} 资产并回写报告（委托生产执行组件）。 */
    public VerificationReport verify(AssetId id, VerificationScope scope) {
        return executor.run(id, scope);
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

    public AssetStepPlanExecutor executor() {
        return executor;
    }

    @Override
    public void close() {
        if (harness instanceof RouteTestBedHarness testBed) {
            testBed.closeAll();
        }
    }
}

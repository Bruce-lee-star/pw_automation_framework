package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.BehaviorModel;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.ElementInventory;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.PageUnderstandingEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservationProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementSource;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.AssetValidator;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.ValidationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetDraft;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetRepository;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetType;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.LinkageGraph;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.Provenance;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.VerifiableStepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopePolicy;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScopeViolationException;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.nls.NlsCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 资产生产流水线编排器（设计 §3.1.2 路径 B / §4.6 企业级流水线）。
 *
 * <p>串联五段 SPI：需求源 → 页面观测 → 理解引擎 → 确定性校验 → 资产仓库。
 * 仅产出 {@code DRAFT} 资产（AI/规则永不自动可信，R6）；校验不通过则失败关闭转人工
 * （{@link PipelineValidationException}，§4.4 铁律）。</p>
 *
 * <p>本编排器<b>零浏览器、零强制 LLM 依赖</b>：理解引擎与需求源均为 SPI，可注入规则引擎或 LLM 实现。</p>
 */
public final class AssetPipelineOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AssetPipelineOrchestrator.class);

    private final RequirementSource requirementSource;
    private final PageObservationProvider observationProvider;
    private final PageUnderstandingEngine engine;
    private final AssetValidator validator;
    private final AssetRepository repository;
    private final NlsCatalog nls;
    // pw-core 的 ElementRef/LocatorSpec/LinkageGraph 等使用 refId()/strategy() 风格 getter（非 getX），
    // 且含 record 与 final class 混合；以字段可见性序列化，避免 Jackson 找不到属性。
    private final ObjectMapper mapper = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);

    public AssetPipelineOrchestrator(RequirementSource requirementSource,
                                     PageObservationProvider observationProvider,
                                     PageUnderstandingEngine engine,
                                     AssetValidator validator,
                                     AssetRepository repository) {
        this(requirementSource, observationProvider, engine, validator, repository, null);
    }

    public AssetPipelineOrchestrator(RequirementSource requirementSource,
                                     PageObservationProvider observationProvider,
                                     PageUnderstandingEngine engine,
                                     AssetValidator validator,
                                     AssetRepository repository,
                                     NlsCatalog nls) {
        this.requirementSource = requirementSource;
        this.observationProvider = observationProvider;
        this.engine = engine;
        this.validator = validator;
        this.repository = repository;
        this.nls = nls;
    }

    /**
     * 执行一次资产生产流水线。
     * @param requirementSourceRef 需求源引用（Feature 文件 / 文档内容）
     * @param pageRef              目标页面引用
     * @return 流水线结果（含落库草稿标识）
     * @throws PipelineValidationException 校验失败（失败关闭，不落库可信资产）
     */
    public PipelineResult run(String requirementSourceRef, String pageRef) {
        RequirementContext req = requirementSource.parse(requirementSourceRef);
        PageObservation obs = observationProvider.observe(pageRef);
        AssetDraftBundle bundle = engine.analyze(req, obs);

        ValidationReport report = validator.validate(bundle);
        if (!report.passed()) {
            LOG.warn("Pipeline validation failed, fail-closed (no asset promoted): {}", report.summary());
            throw new PipelineValidationException(report);
        }

        // 闭环引擎接入点：从资产草稿派生「可执行 + 可验证」步骤计划，并做浏览器无关的治理预检
        VerifiableStepPlan vsp = new StepPlanProducer(nls).produce(bundle);
        try {
            ParamScopePolicy.validatePlan(vsp.plan());
        } catch (ParamScopeViolationException ex) {
            ValidationReport govFail = new ValidationReport(false,
                    List.of(ValidationReport.Issue.error("StepPlan 治理预检失败: " + ex.getMessage())),
                    "stepplan-governance-failed");
            throw new PipelineValidationException(govFail);
        }

        String primaryReq = bundle.linkage().requirementId();
        Provenance provenance = bundle.provenance();
        List<AssetId> saved = new ArrayList<>();
        saved.add(repository.save(new AssetDraft(AssetType.ELEMENT_INVENTORY, primaryReq,
                toJson(bundle.inventory()), provenance)));
        saved.add(repository.save(new AssetDraft(AssetType.BEHAVIOR_MODEL, primaryReq,
                toJson(bundle.behaviors()), provenance)));
        saved.add(repository.save(new AssetDraft(AssetType.LINKAGE_GRAPH, primaryReq,
                toJson(bundle.linkage()), provenance)));
        saved.add(repository.save(new AssetDraft(AssetType.STEP_PLAN, primaryReq,
                toJson(vsp), provenance)));

        LOG.info("Pipeline produced {} DRAFT assets for requirement {}", saved.size(), primaryReq);
        return new PipelineResult(bundle, report, saved, vsp);
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize asset content: " + e.getMessage(), e);
        }
    }
}

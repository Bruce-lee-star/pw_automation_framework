package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.pipeline;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation.ValidationReport;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.AssetId;

import java.util.List;

/**
 * 流水线执行结果（设计 §4.6 资产生产流水线）。
 *
 * @param bundle   产出的资产草稿包
 * @param report   确定性校验报告
 * @param savedIds 落库为 {@code DRAFT} 的资产标识（AI 仅产草稿，R6）
 */
public record PipelineResult(AssetDraftBundle bundle, ValidationReport report, List<AssetId> savedIds) {
}

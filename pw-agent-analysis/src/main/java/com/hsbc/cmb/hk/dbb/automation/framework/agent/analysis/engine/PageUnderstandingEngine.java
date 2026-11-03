package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.observation.PageObservation;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement.RequirementContext;

/**
 * 页面理解引擎 SPI（设计 §3.1.3 / §4.6 ③）。
 *
 * <p>结合<b>需求上下文</b>与<b>脱敏观测</b>推断三类资产（元素清单 / 行为模型 / 联动图谱）及需求映射。
 * 实现可为 LLM 引擎或确定性规则引擎；本模块仅定义契约，LLM 客户端/凭据不进核心（R6）。
 * 引擎仅产 {@code draft}，不持有浏览器原语、不执行（R6）。</p>
 */
public interface PageUnderstandingEngine {

    /**
     * 分析页面，产出资产草稿包。
     * @param requirement 需求上下文（结合需求）
     * @param observation 脱敏观测包
     * @return 资产草稿包（全部为未验证建议）
     */
    AssetDraftBundle analyze(RequirementContext requirement, PageObservation observation);
}

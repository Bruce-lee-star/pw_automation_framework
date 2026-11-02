package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.validation;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine.AssetDraftBundle;

/**
 * 资产校验 SPI（设计 §3.1.3 / §4.6 ④）。
 *
 * <p>对理解引擎产出的 {@link AssetDraftBundle} 做<b>确定性</b>校验（§4.4 梯队）：
 * 定位器唯一性/可见/可操作、业务断言可执行、联动可复现。不含 LLM（R6）。
 * 实现可为静态规则校验器或复用靶场 {@code AssetVerifier} 的轻量验证。</p>
 */
public interface AssetValidator {

    /**
     * 校验资产草稿包。
     * @param bundle 待校验包
     * @return 校验报告（passed=false 表示须失败关闭转人工）
     */
    ValidationReport validate(AssetDraftBundle bundle);
}

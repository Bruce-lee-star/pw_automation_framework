package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 资产验证驱动契约（设计 §3.1.2.1）。
 *
 * <p>消费已落库（active / pending_review）的资产——尤其是联动图谱与行为模型——
 * 在隔离靶场驱动浏览器执行资产定义的操作/联动序列，验证其是否可复现，并产出 VerificationReport。
 *
 * <p><b>不产生任何 LLM 调用</b>（R6）：验证是纯确定性执行，AI 仅停留在分析期。</p>
 */
public interface AssetVerifier {

    /**
     * 验证给定联动图谱。
     * @param graph 待验证的联动图谱资产
     * @param scope 验证范围（目标 URL + 重复次数）
     * @return 验证报告（pass/fail/flaky + 稳定性 + 证据）
     */
    VerificationReport verify(LinkageGraph graph, VerificationScope scope);
}

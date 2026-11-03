package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

/**
 * 候选定位器的<b>取证探针</b>：在真实页面上观测"这条定位符到底命中了什么"。
 *
 * <p>存在的理由很直接：证据必须来自<b>实际观测</b>。如果允许人手工填
 * {@code --matches 1 --visible true}，那么"验证通过"只是重复了一遍填表人的假设，
 * 与真页面毫无关系——而定位器失效恰恰是自动化维护里最常见的故障。</p>
 *
 * <p><b>实现方只管观测，不做判断</b>：命中数、可见性、可操作性、实际可访问名。
 * 是否通过由 {@link CandidateValidator} 依据 {@link CandidateEvidence} 判定。
 * 这样探针可以被替换（Playwright / Selenium / 离线回放），验证规则却只有一份。</p>
 *
 * <p><b>异常约定</b>：无法完成观测时返回 {@link StructuralObservation#unobserved} 而不是抛异常，
 * 因为"探针失败"本身就是要记录的证据；抛异常会让整批候选处理中断，
 * 反而掩盖了其他候选的真实状态。</p>
 */
public interface EvidenceProbe {

    /**
     * 观测候选定位符在当前页面上的落点。
     *
     * @param candidate 待取证候选
     * @return 结构性观测结果；无法观测时返回 {@link StructuralObservation#unobserved}
     */
    StructuralObservation observe(LocatorCandidate candidate);
}

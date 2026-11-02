package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement;

import java.util.List;

/**
 * 单条需求条目（设计 §4.6 需求上下文）。
 *
 * <p>从 Cucumber Feature / 需求文档解析而来；含验收标准与业务规则，作为 LLM/规则理解的上下文输入。
 * 需求文本默认过脱敏白名单（R1/R7）——样例中的真实账号/金额/PII 须遮蔽，不进入模型上下文。</p>
 *
 * @param id                 需求标识
 * @param title              需求标题
 * @param acceptanceCriteria 验收标准（列表）
 * @param businessRules      业务规则（列表，联动/校验规则来源）
 * @param sourceRef          来源引用（Feature 文件 / 文档章节）
 */
public record RequirementEntry(RequirementId id, String title,
                               List<String> acceptanceCriteria, List<String> businessRules,
                               String sourceRef) {

    /** 用于关键词匹配的全文（标题 + 验收 + 规则拼接，小写）。 */
    public String corpus() {
        StringBuilder sb = new StringBuilder();
        sb.append(title == null ? "" : title).append(' ');
        if (acceptanceCriteria != null) {
            acceptanceCriteria.forEach(s -> sb.append(s).append(' '));
        }
        if (businessRules != null) {
            businessRules.forEach(s -> sb.append(s).append(' '));
        }
        return sb.toString().toLowerCase();
    }
}

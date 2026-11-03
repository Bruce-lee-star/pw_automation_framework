package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 结构化需求上下文（设计 §4.6 ① 需求接入）。
 *
 * <p>分析引擎（LLM / 规则）理解页面时必须结合本上下文，才能把元素/操作/联动映射到需求条目，
 * 使资产可评审、可追溯、可度量覆盖度。</p>
 *
 * @param entries   需求条目列表
 * @param sourceName 来源名称（如 "transfer.feature"）
 * @param testData  权威测试数据（按元素 refId → 输入值）；由需求源声明，供闭环引擎派生
 *                  执行计划时优先使用，使 FILL/SELECT 真正带有业务值而非依赖联动图谱内嵌值
 */
public record RequirementContext(List<RequirementEntry> entries, String sourceName,
                                 Map<String, String> testData) {

    /** 兼容构造：无显式测试数据时为空映射。 */
    public RequirementContext(List<RequirementEntry> entries, String sourceName) {
        this(entries, sourceName, Map.of());
    }

    public RequirementContext {
        entries = entries == null ? Collections.emptyList() : List.copyOf(entries);
        testData = testData == null ? Map.of() : Map.copyOf(testData);
    }

    public List<RequirementEntry> entries() {
        return entries;
    }

    /** 取指定元素的权威测试值（无则返回 null）。 */
    public String testValue(String refId) {
        return refId == null ? null : testData.get(refId);
    }

    /** 按关键词匹配命中的需求条目（小写子串，用于元素→需求映射）。无命中返回空。 */
    public RequirementEntry matchByKeyword(String... keywords) {
        for (RequirementEntry e : entries) {
            String corpus = e.corpus();
            for (String k : keywords) {
                if (k != null && !k.isBlank() && corpus.contains(k.toLowerCase())) {
                    return e;
                }
            }
        }
        return null;
    }
}

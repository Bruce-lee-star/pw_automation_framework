package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement;

import java.util.Collections;
import java.util.List;

/**
 * 结构化需求上下文（设计 §4.6 ① 需求接入）。
 *
 * <p>分析引擎（LLM / 规则）理解页面时必须结合本上下文，才能把元素/操作/联动映射到需求条目，
 * 使资产可评审、可追溯、可度量覆盖度。</p>
 *
 * @param entries   需求条目列表
 * @param sourceName 来源名称（如 "transfer.feature"）
 */
public record RequirementContext(List<RequirementEntry> entries, String sourceName) {

    public RequirementContext {
        entries = entries == null ? Collections.emptyList() : List.copyOf(entries);
    }

    public List<RequirementEntry> entries() {
        return entries;
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

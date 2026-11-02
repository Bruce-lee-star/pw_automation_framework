package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement;

import java.util.List;

/**
 * 静态/人工需求源（确定性、无外部依赖）。
 *
 * <p>用于测试、录制回放或人工录入场景：直接持有预设的 {@link RequirementContext}，
 * {@link #parse(String)} 忽略入参返回该上下文。作为 LLM 需求源不可用时的韧性降级路径。</p>
 */
public final class StaticRequirementSource implements RequirementSource {

    private final RequirementContext context;

    public StaticRequirementSource(RequirementContext context) {
        this.context = context;
    }

    public StaticRequirementSource(List<RequirementEntry> entries, String sourceName) {
        this(new RequirementContext(entries, sourceName));
    }

    @Override
    public RequirementContext parse(String source) {
        return context;
    }
}

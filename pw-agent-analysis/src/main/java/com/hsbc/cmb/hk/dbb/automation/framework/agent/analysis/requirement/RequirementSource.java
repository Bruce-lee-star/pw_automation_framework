package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement;

/**
 * 需求源 SPI（设计 §3.1.3 / §4.6 ①）。
 *
 * <p>把需求文档 / Cucumber Feature / 业务说明解析为 {@link RequirementContext}。
 * 实现可为：Cucumber Feature 解析器、需求文档（Markdown/Confluence）解析器、或人工录入。
 * 属于「分析期可选适配」，不进运行时（R6）；pw-core 仅定义契约，实现独立打包、依赖可排除。</p>
 */
public interface RequirementSource {

    /**
     * 解析需求源。
     * @param source 源标识或内容（如 Feature 文件路径 / 文档内容）
     * @return 结构化需求上下文
     */
    RequirementContext parse(String source);
}

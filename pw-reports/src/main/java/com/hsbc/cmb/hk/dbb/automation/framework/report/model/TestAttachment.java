package com.hsbc.cmb.hk.dbb.automation.framework.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 业务层自由附件——glue 代码可往报告里塞任意 HTML / 文本块，用于组织数据。
 *
 * <p>渲染为可折叠区块（{@code <details>}）。内容过 ScrubGate，但标签不过滤
 *（测试产物，自己人看自己的 DOM dump / 业务数据块）。
 *
 * @param name        显示名（如 "Page Source"、"业务汇总表"）
 * @param contentType text/html | text/plain | text/xml
 * @param content     附件内容（已脱敏）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestAttachment(String name, String contentType, String content) {
}

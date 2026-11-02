package com.hsbc.cmb.hk.dbb.automation.framework.report.model;

/**
 * 一次「挂载到报告」的下载文件记录（additive-only）。
 *
 * <p>由 {@code ReportBus.attachDownloadableFile} 在把文件复制进
 * {@code <outputDir>/<outcomeId>/<file>} 后登记，供 per-case 报告的「Downloads」Tab 罗列/链接。
 * 与纯文本证据（{@code "DOWNLOAD: ..."} attachment）并存：前者结构化、可点击下载；后者保留兼容老消费方。</p>
 *
 * @param title        展示标题（默认 {@code "DOWNLOAD: <fileName>"}）
 * @param fileName     归档文件名（与 per-case 页同目录，Tab 内以之为相对链接）
 * @param relativePath 运行根相对路径（{@code <outcomeId>/<fileName>}，供跨用例汇总 / JSON）
 * @param timestamp    挂载时刻（ISO-8601）
 */
public record DownloadRecord(String title, String fileName, String relativePath, String timestamp) {
}

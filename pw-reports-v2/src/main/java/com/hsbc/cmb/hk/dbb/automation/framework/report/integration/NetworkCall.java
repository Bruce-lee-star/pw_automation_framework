package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import java.util.List;
import java.util.Map;

/**
 * 一次网络调用的结构化快照（Tier2 #6 接缝 DTO）。
 *
 * <p>由框架侧 Playwright route 监控 / {@code RouteEvidenceRegistry} 采集后填入，
 * 经 {@link NetworkEvidenceBridge} 汇入 pw-reports 步骤证据。所有敏感字段
 * （url query、header 值、body）在 bridge 内统一过 {@code ScrubGate}，调用方
 * 不需自行脱敏。
 *
 * @param method         HTTP 方法（GET/POST/...）
 * @param url            完整请求 URL（可能含 query 与敏感参数）
 * @param requestHeaders 请求头（值可能含 token/cookie）
 * @param status         HTTP 状态码（<0 表示未发出/异常）
 * @param responseHeaders 响应头
 * @param requestDigest  请求体摘要（避免整段 body 入报告；由采集方算 sha256 或截断）
 * @param responseDigest 响应体摘要
 * @param durationMs     耗时
 * @param errorMessage   失败原因（status<0 或断言失败时的描述）
 */
public record NetworkCall(
        String method,
        String url,
        Map<String, String> requestHeaders,
        int status,
        Map<String, String> responseHeaders,
        String requestDigest,
        String responseDigest,
        long durationMs,
        String errorMessage
) {

    public static Builder builder() {
        return new Builder();
    }

    /** 流式构造器（采集方按需填字段）。 */
    public static final class Builder {
        private String method = "GET";
        private String url;
        private Map<String, String> requestHeaders = Map.of();
        private int status = -1;
        private Map<String, String> responseHeaders = Map.of();
        private String requestDigest;
        private String responseDigest;
        private long durationMs;
        private String errorMessage;

        public Builder method(String v) { this.method = v; return this; }
        public Builder url(String v) { this.url = v; return this; }
        public Builder requestHeaders(Map<String, String> v) { this.requestHeaders = v == null ? Map.of() : v; return this; }
        public Builder status(int v) { this.status = v; return this; }
        public Builder responseHeaders(Map<String, String> v) { this.responseHeaders = v == null ? Map.of() : v; return this; }
        public Builder requestDigest(String v) { this.requestDigest = v; return this; }
        public Builder responseDigest(String v) { this.responseDigest = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }

        public NetworkCall build() {
            return new NetworkCall(method, url, requestHeaders, status, responseHeaders,
                    requestDigest, responseDigest, durationMs, errorMessage);
        }
    }

    /** 格式化为一行（含 header 摘要），供 bridge 交给总线脱敏后落证据。 */
    public String toOneLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(method == null ? "?" : method.toUpperCase(java.util.Locale.ROOT));
        sb.append(' ').append(url == null ? "<no-url>" : url);
        if (status >= 0) {
            sb.append(" -> ").append(status);
        }
        if (durationMs > 0) {
            sb.append(" (").append(durationMs).append(" ms)");
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            sb.append(" ERR: ").append(errorMessage);
        }
        if (requestDigest != null && !requestDigest.isBlank()) {
            sb.append(" req=").append(requestDigest);
        }
        if (responseDigest != null && !responseDigest.isBlank()) {
            sb.append(" resp=").append(responseDigest);
        }
        List<String> hdrKeys = new java.util.ArrayList<>(requestHeaders.keySet());
        if (!hdrKeys.isEmpty()) {
            sb.append(" reqHdr=").append(String.join(",", hdrKeys));
        }
        return sb.toString();
    }
}

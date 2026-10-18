package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

/**
 * HTTP body 摘要——body 永不内联进报告（R3）。
 *
 * @param hash    {@code sha256:<前16位hex>}，稳定可校验
 * @param preview 脱敏后前 256 字节预览
 */
public record BodyDigest(String hash, String preview) {

    public static final BodyDigest EMPTY = new BodyDigest("sha256:empty", "");

    /** 渲染进证据行的紧凑形式。 */
    public String toEvidenceLine() {
        return "body " + hash + " preview: " + preview;
    }
}

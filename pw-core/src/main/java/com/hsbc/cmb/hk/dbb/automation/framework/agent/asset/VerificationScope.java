package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/** 验证范围：目标页面 URL 与重复次数（重复用于稳定性统计，§4.6 量化验收）。 */
public final class VerificationScope {
    private final String targetUrl;
    private final int repeat;

    public VerificationScope(String targetUrl, int repeat) {
        this.targetUrl = targetUrl;
        this.repeat = Math.max(1, repeat);
    }

    public String targetUrl() { return targetUrl; }
    public int repeat() { return repeat; }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.requirement;

/**
 * 需求标识（设计 §4.6 需求映射 / 溯源）。
 *
 * <p>形如 {@code "REQ-101"}；{@code version} 用于失效重验时按需求版本对齐资产。</p>
 */
public record RequirementId(String id, String version) {

    public RequirementId(String id) {
        this(id, "1.0");
    }

    /** 转为资产层使用的扁平字符串（避免 pw-core 依赖本模块类型）。 */
    public String flat() {
        return version == null || version.isBlank() ? id : id + "@" + version;
    }
}

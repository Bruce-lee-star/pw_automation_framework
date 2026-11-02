package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 资产标识（设计 §4.6 资产治理）。
 *
 * <p>同一业务资产跨多次分析/审批形成多个版本，{@code version} 单调递增；
 * {@code id} 为业务稳定主键（如 {@code "asset:linkage:REQ-101"}）。</p>
 */
public record AssetId(String id, int version) {

    public AssetId(String id) {
        this(id, 1);
    }

    /** 生成下一版本标识（不修改自身，返回新实例）。 */
    public AssetId nextVersion() {
        return new AssetId(id, version + 1);
    }

    @Override
    public String toString() {
        return id + "@v" + version;
    }
}

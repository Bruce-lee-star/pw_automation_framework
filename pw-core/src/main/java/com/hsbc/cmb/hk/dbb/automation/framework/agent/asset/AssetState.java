package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 资产生命周期状态（设计 §4.6 状态机）。
 *
 * <p>迁移路径：{@code DRAFT → PENDING_REVIEW → APPROVED → ACTIVE → DEPRECATED}。
 * AI 仅能产出 {@code DRAFT}；{@code APPROVED}/{@code ACTIVE} 的责任归属人工审批者（R6/R9）。</p>
 */
public enum AssetState {
    /** 草稿：分析期产出，未经任何校验/审批。 */
    DRAFT,
    /** 已提交待人工评审。 */
    PENDING_REVIEW,
    /** 人工审批通过，但尚未激活（可回滚）。 */
    APPROVED,
    /** 已激活，可被回归链路（路径 C）消费。 */
    ACTIVE,
    /** 已废弃（需求变更/页面改版导致定位器漂移需重验）。 */
    DEPRECATED
}

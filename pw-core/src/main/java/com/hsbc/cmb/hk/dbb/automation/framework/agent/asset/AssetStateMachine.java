package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 资产状态机（设计 §4.6 治理与生命周期）。
 *
 * <p>合法迁移：{@code DRAFT → PENDING_REVIEW → APPROVED → ACTIVE → DEPRECATED}；
 * 任意状态可 {@code DEPRECATED}；{@code APPROVED → PENDING_REVIEW}（重验）亦允许。
 * 任何状态变更须由调用方记录操作者与时间（R9），本机仅负责迁移合法性。</p>
 */
public interface AssetStateMachine {

    /** 状态迁移事件。 */
    enum Event {
        /** 提交评审：DRAFT → PENDING_REVIEW。 */
        SUBMIT,
        /** 审批通过：PENDING_REVIEW → APPROVED。 */
        APPROVE,
        /** 激活：APPROVED → ACTIVE。 */
        ACTIVATE,
        /** 废弃：任意 → DEPRECATED。 */
        DEPRECATE,
        /** 退回重验：APPROVED → PENDING_REVIEW。 */
        RETURN_FOR_REVIEW
    }

    /** 是否允许从 from 经 event 迁移到目标状态。 */
    boolean canTransition(AssetState from, Event event);

    /**
     * 计算迁移后的目标状态。
     * @throws IllegalStateException 当迁移非法时
     */
    AssetState transition(AssetState from, Event event);

    /** 默认实现：基于迁移表的纯函数式状态机。 */
    class DefaultAssetStateMachine implements AssetStateMachine {

        @Override
        public boolean canTransition(AssetState from, Event event) {
            return switch (from) {
                case DRAFT          -> event == Event.SUBMIT;
                case PENDING_REVIEW -> event == Event.APPROVE || event == Event.DEPRECATE;
                case APPROVED       -> event == Event.ACTIVATE || event == Event.RETURN_FOR_REVIEW || event == Event.DEPRECATE;
                case ACTIVE         -> event == Event.DEPRECATE;
                case DEPRECATED     -> false; // 终态
            };
        }

        @Override
        public AssetState transition(AssetState from, Event event) {
            if (!canTransition(from, event)) {
                throw new IllegalStateException("Illegal asset transition: " + from + " --" + event + "--> ?");
            }
            return switch (event) {
                case SUBMIT           -> AssetState.PENDING_REVIEW;
                case APPROVE          -> AssetState.APPROVED;
                case ACTIVATE         -> AssetState.ACTIVE;
                case RETURN_FOR_REVIEW -> AssetState.PENDING_REVIEW;
                case DEPRECATE        -> AssetState.DEPRECATED;
            };
        }
    }
}

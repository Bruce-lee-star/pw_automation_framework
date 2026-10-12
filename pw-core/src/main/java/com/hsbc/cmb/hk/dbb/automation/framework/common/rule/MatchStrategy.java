package com.hsbc.cmb.hk.dbb.automation.framework.common.rule;

/**
 * 规则匹配策略。
 *
 * <ul>
 *   <li>{@link #ALL}  - 广播式:跑遍所有已配置规则,逐条命中即执行动作,不短路。</li>
 *   <li>{@link #FIRST}- 短路式:命中首条即执行并停止,兼容需"首个匹配"语义的调用方。</li>
 * </ul>
 */
public enum MatchStrategy {
    ALL,
    FIRST
}

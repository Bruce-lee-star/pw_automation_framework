package com.hsbc.cmb.hk.dbb.automation.framework.agent.analysis.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.asset.ElementRef;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;

/**
 * 操作行为条目（设计 §4.6 ②）。
 *
 * <p>某元素的合法操作 + 预期结果类别 + 前置/后置业务断言 + 需求映射。
 * 预期结果类别为"无副作用 / 导航 / 异步 / 校验"之一（属未验证建议，须确定性校验 + 人工审批）。</p>
 *
 * @param ref                   元素引用
 * @param action                操作类型（CLICK/FILL/SELECT/CHECK/UNCHECK…）
 * @param expectedResultCategory 预期结果类别（navigation / toggle / reveal / input / submit…）
 * @param precondition          前置业务断言（可选）
 * @param postcondition         后置业务断言（可选）
 * @param requirementId         关联需求 id（需求映射；空串表示 incomplete）
 */
public record BehaviorEntry(ElementRef ref, Command.ActionType action,
                            String expectedResultCategory, String precondition,
                            String postcondition, String requirementId) {
}

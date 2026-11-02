package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

/**
 * 总线事件——sink 消费的统一契约（R5：所有事件带 reportVersion）。
 *
 * <p>additive-only：新增事件类型 = 新增 permits 子类 + sink 侧 switch 分支；
 * 已有事件的字段只增不减。读路径（collector 回放）遇未知字段忽略。
 *
 * <p>三个事件记录为独立文件（同包 public record），保持 sealed 契约可扩展。
 */
public sealed interface ReportEvent permits CaseStartedEvent, CaseFinishedEvent, RunFinishedEvent {

    /** 契约版本，当前 1。 */
    default int reportVersion() {
        return 1;
    }

    /** 事件类型名（collector 分流用）。 */
    String type();
}

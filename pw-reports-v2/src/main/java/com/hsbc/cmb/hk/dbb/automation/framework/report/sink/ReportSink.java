package com.hsbc.cmb.hk.dbb.automation.framework.report.sink;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportEvent;

/**
 * 报告出口——消费<b>脱敏后</b>的事件数据（ScrubGate 在总线入口，不在出口）。
 *
 * <p>不变式：{@code accept} 抛出的任何异常都由总线逐个 try/catch 隔离
 * （一个 sink 死，不影响测试本身与其他 sink）。实现方仍应内部自担异常。
 */
public interface ReportSink {

    /** sink 名（日志 / metrics 标识）。 */
    String name();

    void accept(ReportEvent event);

    /** 释放资源（推送线程等）。默认无操作。 */
    default void close() {
    }
}

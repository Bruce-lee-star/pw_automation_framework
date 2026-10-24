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

    /**
     * 整场开始（总线 {@code startRun()} 复位时回调）——sink 借此清掉上一轮 run 残留的<b>进程级</b>状态。
     *
     * <p>典型用途：{@code LocalReportSink} 清 {@code writtenIds}（否则同 JVM 第二轮 run 出现相同
     * outcomeId 时会被判"已写"而跳过 per-case JSON 真相源）与累计 {@code errors}。</p>
     *
     * <p>默认无操作（不破坏既有无状态 sink）；有进程级残余态的 sink 才需覆写。</p>
     */
    default void onRunStarted() {
    }

    /** 释放资源（推送线程等）。默认无操作。 */
    default void close() {
    }
}

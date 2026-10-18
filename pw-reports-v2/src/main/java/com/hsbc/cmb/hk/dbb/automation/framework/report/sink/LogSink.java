package com.hsbc.cmb.hk.dbb.automation.framework.report.sink;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseFinishedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.CaseStartedEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.RunFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制台事件流（调试用）。数据已过 ScrubGate，可直接打印。
 * 用例级 DEBUG、失败 WARN、整场结束 INFO——默认日志级别下只有失败与汇总可见。
 */
public final class LogSink implements ReportSink {

    private static final Logger log = LoggerFactory.getLogger(LogSink.class);

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void accept(ReportEvent event) {
        switch (event) {
            case CaseStartedEvent e -> log.debug("[report] case started: {} ({})", e.outcomeId(), e.scenario());
            case CaseFinishedEvent e -> {
                String result = e.outcome().getResult() == null ? "UNKNOWN" : e.outcome().getResult().name();
                if (e.outcome().getResult() != null && e.outcome().getResult().isFailure()) {
                    log.warn("[report] case FAILED: {} ({}) — {}",
                            e.outcome().getOutcomeId(), e.outcome().getName(), result);
                } else {
                    log.debug("[report] case finished: {} — {}", e.outcome().getOutcomeId(), result);
                }
            }
            case RunFinishedEvent e -> log.info("[report] run finished: {} outcome(s)", e.outcomes().size());
        }
    }
}

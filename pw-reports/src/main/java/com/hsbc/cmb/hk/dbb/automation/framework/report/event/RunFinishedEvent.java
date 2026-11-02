package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;

import java.util.List;

/** 整场结束——携带全部 outcome 快照（sink 侧渲染 HTML / 汇总 / 推送）。 */
public record RunFinishedEvent(List<TestOutcome> outcomes) implements ReportEvent {

    @Override
    public String type() {
        return "runFinished";
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;

/** 用例结束——携带全量 outcome（已脱敏）。实时落 per-case JSON 的触发点。 */
public record CaseFinishedEvent(TestOutcome outcome) implements ReportEvent {

    @Override
    public String type() {
        return "caseFinished";
    }
}

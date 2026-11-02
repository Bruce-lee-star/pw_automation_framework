package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

/** 用例开始。 */
public record CaseStartedEvent(String outcomeId, String feature, String scenario) implements ReportEvent {

    @Override
    public String type() {
        return "caseStarted";
    }
}

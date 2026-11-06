package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

/**
 * 一条 Playwright strict 违例证据（一次定位命中多个元素）。
 *
 * <p>携带<b>场景归因</b>（{@code scenario}）：报告里能直接回答「哪条用例在哪个操作上踩了多元素定位」，
 * 而不是只有一个孤立的选择器名。非 BDD 运行（无场景上下文）时采集侧记 {@code unknown}。
 */
public class LocatorHealthViolation {

    private final String scenario;
    private final String selector;
    private final String kind;
    private final String operation;
    private final int matchCount;
    private final String thread;
    private final String at;

    public LocatorHealthViolation(String scenario, String selector, String kind, String operation,
                                  int matchCount, String thread, String at) {
        this.scenario = scenario == null ? "unknown" : scenario;
        this.selector = selector == null ? "" : selector;
        this.kind = kind == null ? "" : kind;
        this.operation = operation == null ? "" : operation;
        this.matchCount = matchCount;
        this.thread = thread == null ? "" : thread;
        this.at = at == null ? "" : at;
    }

    /** 发生违例的场景名（无法归因时为 {@code unknown}）。 */
    public String getScenario() {
        return scenario;
    }

    public String getSelector() {
        return selector;
    }

    public String getKind() {
        return kind;
    }

    /** 触发该违例的元素操作（click / fill / getText …）。 */
    public String getOperation() {
        return operation;
    }

    /** Playwright 判定命中的元素数。 */
    public int getMatchCount() {
        return matchCount;
    }

    /** 记录时的线程名（并发运行下用于区分 lane）。 */
    public String getThread() {
        return thread;
    }

    /** 发生时刻（ISO-8601 字符串，已格式化，报告直接展示）。 */
    public String getAt() {
        return at;
    }
}

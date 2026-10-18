package com.hsbc.cmb.hk.dbb.automation.framework.common.reporting;

import java.nio.file.Path;

/**
 * 报告写入契约（依赖倒置点）。
 *
 * <p>把 web / route 框架层的「截图 / 证据 / 标量数据 / 场景结果」推送，从具体报告引擎
 * （框架 或 v2 {@code ReportBus}）解耦。web / route 仅依赖本接口（经
 * {@link ReportingBridgeRegistry} 取得），具体实现由报告模块（如 pw-reports-v2 的
 * {@code ReportBus} 适配器）通过 {@code META-INF/services} SPI 提供，运行时由
 * {@link java.util.ServiceLoader} 发现。借此打破 web / route ↔ 报告引擎的编译期耦合，
 * 使 {@code PlaywrightListener} 等框架监听器彻底摆脱对 serenity 的直接依赖。
 *
 * <p>所有方法均提供 <b>no-op 默认实现</b>：当 classpath 上不存在 SPI 实现时，
 * {@link ReportingBridgeRegistry#get()} 回落到默认桥，调用方无需判空、在非报告环境
 * （纯 JUnit 单测、未装配报告模块）下零副作用。
 */
public interface ReportingBridge {

    /**
     * 报告引擎是否处于激活态（用于截图 / 证据采集的短路判断）。
     *
     * @return {@code true} 表示有报告引擎在线、截图等证据值得采集；{@code false} 表示
     *         非报告环境，调用方可跳过昂贵的截图/序列化
     */
    default boolean isReportingActive() {
        return false;
    }

    /**
     * 挂一张步骤截图（失败诊断 / 策略截图）。
     *
     * @param screenshot 原生截图（含落盘文件与展示名）；{@code null}/空忽略
     */
    default void attachScreenshot(CapturedScreenshot screenshot) {
    }

    /**
     * 挂一段 HTML 证据（如 DOM dump、自定义卡片）。
     *
     * @param name         证据名（用于报告内展示）
     * @param htmlContent  HTML 内容；{@code null}/空忽略
     */
    default void attachHtml(String name, String htmlContent) {
    }

    /**
     * 挂一段文本证据（如 API 监控失败明细、路由命中信息）。
     *
     * @param text 证据文本；{@code null}/空忽略
     */
    default void attachEvidence(String text) {
    }

    /**
     * 记录一条标量报告数据（key/value，如 testResult / testDuration / testStart）。
     *
     * @param key   数据键（非空）
     * @param value 数据值（任意类型，实现方按需 {@code toString()}）
     */
    default void recordReportData(String key, Object value) {
    }

    /**
     * 挂一个可下载文件附件（如下载的文件、trace zip）。
     *
     * @param title 报告内展示标题
     * @param file  已落盘的源文件（绝对路径）；不存在/空忽略
     */
    default void recordDownloadableFile(String title, Path file) {
    }

    /**
     * 挂载 Playwright trace.zip 到当前用例：实现方把它拷到 {@code <outcomeId>/trace.zip} 并设
     * {@code tracePath}，使报告页渲染 "Play trace" 按钮指向 Trace Viewer。
     *
     * <p>须在测试主线程、场景尚未收尾（case 句柄仍可解析）时调用；非报告环境 / 无活动用例句柄时
     * no-op（trace 仍已落盘，仅不展示按钮）。默认 no-op，旧报告实现无需改动。
     *
     * @param traceZip 已落盘的 trace 源文件；{@code null}/不存在忽略
     */
    default void attachTrace(Path traceZip) {
    }

    /**
     * 记录场景级结果（scenario 名 + 结果 + 耗时），供报告汇总。
     *
     * @param scenarioName 场景名
     * @param result       结果字符串（SUCCESS/FAILURE/...）
     * @param durationMs   耗时（毫秒）
     */
    default void recordScenario(String scenarioName, String result, long durationMs) {
    }

    /**
     * 记录一条 RouteDsl 路由注册（API 注册），如 {@code [Route] register GET route for 'pattern' (...)}。
     *
     * <p>进报告"Route registrations"区，与运行期 stdout（{@code consoleOutput}）/ 网络调用
     * （{@code apiOperations}）分开归类，呼应报告 redesignd 的"route api 信息需归类"诉求。
     * 注册发生在测试主线程，故同步写入即可；内容内核侧已脱敏。
     *
     * @param line 已格式化的注册描述行
     */
    default void recordRouteRegistration(String line) {
    }

    /**
     * 冲刷待报告 API 操作队列（须在测试主线程调用）。
     *
     * <p>具体引擎若无独立队列（如 v2 经 {@code NetworkEvidenceBridge} 自治），本方法为 no-op。
     */
    default void flushPendingApiOperations() {
    }

    /**
     * 丢弃待报告 API 操作队列中的残留（防跨场景串扰）。
     *
     * <p>具体引擎若无独立队列，本方法为 no-op。
     */
    default void discardPendingApiOperations() {
    }
}

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
     * 挂载 Playwright trace.zip <b>并附带录制窗口</b>（起止时刻 + 时长），供报告页 Trace 卡片
     * 展示「录制窗口 / 时长」（对齐 Playwright Trace Viewer 的可读信息）。
     *
     * <p>默认实现回落到 {@link #attachTrace(Path)}（只登记路径）：旧报告实现无需改动即可兼容；
     * v2 报告覆写本方法以渲染窗口与时长。任一时点为 {@code null} 时表示采集方未提供窗口，
     * 报告侧相应省略该行。
     *
     * @param traceZip       已落盘的 trace 源文件；{@code null}/不存在忽略
     * @param traceStartTime 录制起始时刻（{@code HH:mm:ss.SSS}，可为 {@code null}）
     * @param traceEndTime   录制结束时刻（{@code HH:mm:ss.SSS}，可为 {@code null}）
     * @param traceDurationMs 录制时长（毫秒；{@code <=0} 表示未知）
     */
    default void attachTrace(Path traceZip, String traceStartTime, String traceEndTime, long traceDurationMs) {
        attachTrace(traceZip);
    }

    /**
     * 登记 Playwright 场景<b>录屏</b>（recordVideo 产出，webm）——报告侧把文件落入 per-case
     * （{@code <outcomeId>/records/<name>.webm}）并在 “Playwright records” Tab 渲染播放器。
     *
     * <p>调用时机：场景收尾、context 关闭<b>之后</b>（Playwright 的 {@code Video.path()}
     * 只在 context 关闭后才可用且文件才完整）。默认 no-op：未接报告 / 未开启录屏时无任何行为。
     *
     * @param video 录屏文件（可为 null——未开启录屏或采集失败时安全跳过）
     */
    default void attachVideo(Path video) {
    }

    /**
     * 报告侧 trace 落点（可选）：返回非 {@code null} 时，框架把本次 trace <b>直接导出到该路径</b>
     * （通常是报告 per-case 目录下的 {@code trace.zip}），从而不产生“框架 traces/ + per-case”两份、
     * 也无须事后拷贝/移动。
     *
     * <p>默认 {@code null} = 框架自选位置（固定 {@code target/site/serenity/traces}，非配置项）：
     * 无活动用例句柄 / 未接报告时走此路，trace 仍落盘作证据，由 TraceRetention 按 mtime 治理。
     *
     * @return trace 目标文件路径；不需要指导落点时返回 {@code null}
     */
    default Path traceTarget() {
        return null;
    }

    /**
     * 截图落盘<b>目录</b>（可选）：返回非 {@code null} 时，框架把截图写进该目录（通常是报告
     * per-case 目录下的 {@code screenshots/}），而不是固定的 {@code target/site/serenity} 根目录。
     *
     * <p>动机（2026-10-20 用户反馈）：截图曾以 sha256 命名散落在 {@code target/site/serenity/}
     * 根下，与 per-case 目录（报告真正引用的位置）割裂，产物目录被垃圾文件污染。
     * 与 {@link #traceTarget()} 同源：报告侧指导框架落点，从源头避免两份。
     *
     * <p>默认 {@code null} = 框架自选位置（{@code target/site/serenity}，非配置项）：
     * 未接报告 / 无活动用例句柄时走此路，截图仍落盘作证据。
     *
     * @return 截图目录；不需要指导落点时返回 {@code null}
     */
    default Path screenshotTarget() {
        return null;
    }

    /**
     * 当前用例的录屏落点（{@code <outputDir>/<outcomeId>/records}）——指导框架<b>直接录到 per-case
     * 目录</b>，从源头避免「全局 videoDir 先落一份、再搬进 per-case」的两份与中途失败。
     *
     * <p>与 {@link #screenshotTarget()} / {@code traceTarget()} 同策略：解析不到活动用例句柄时返回
     * {@code null}，框架回落其配置的录屏目录（随后由 {@link #attachVideo(Path)} 搬入）。</p>
     */
    default Path videoTarget() {
        return null;
    }

    /**
     * 按显式用例 id 解析 trace 落点，供 context 绑定的异步收尾使用。不得以最近活动用例替代缺失的 id。
     *
     * @param outcomeId 显式用例 id
     * @return trace 目标文件路径；未接报告或 id 无活动会话时返回 {@code null}
     */
    default Path traceTargetByOutcomeId(String outcomeId) {
        return null;
    }

    /**
     * 按显式用例 id 解析录屏落点，供 context 绑定的异步收尾使用。
     *
     * @param outcomeId 显式用例 id
     * @return 录屏目录；未接报告或 id 无活动会话时返回 {@code null}
     */
    default Path videoTargetByOutcomeId(String outcomeId) {
        return null;
    }

    /**
     * 当前线程绑定的用例 id。异步线程无句柄时返回 {@code null}，调用方必须从显式 context 绑定获取归属，
     * 不得回退到最近活动用例。
     */
    default String currentOutcomeId() {
        return null;
    }

    /**
     * 按显式用例 id 挂载场景录屏；归属由调用方凭 context 绑定传入。id 为 {@code null} 时不挂载。
     *
     * @param outcomeId 显式用例 id
     * @param video     录屏文件（可为 null）
     */
    default void attachVideo(String outcomeId, Path video) {
        if (outcomeId == null || outcomeId.isBlank()) {
            return;
        }
        attachVideo(video);
    }

    /**
     * 按显式用例 id 挂载 trace.zip；归属由调用方凭 context 绑定传入。id 为 {@code null} 时不挂载。
     *
     * @param outcomeId 显式用例 id
     * @param traceZip  已落盘的 trace 源文件
     */
    default void attachTrace(String outcomeId, Path traceZip) {
        if (outcomeId == null || outcomeId.isBlank()) {
            return;
        }
        attachTrace(traceZip);
    }

    /** 按显式用例 id 挂载 trace.zip 并附带录制窗口；id 为 {@code null} 时不挂载。 */
    default void attachTrace(String outcomeId, Path traceZip,
                             String traceStartTime, String traceEndTime, long traceDurationMs) {
        if (outcomeId == null || outcomeId.isBlank()) {
            return;
        }
        attachTrace(traceZip, traceStartTime, traceEndTime, traceDurationMs);
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
     * 记录一条<b>已捕获的 API 调用</b>（method / url / status / 摘要），进 per-case 页的
     * {@code apiOperations}（步骤内捕获归位到发生它的步骤）。
     *
     * <p>与 {@link #attachEvidence(String)} 的差异：证据是自由文本诊断，{@code apiOperations}
     * 是结构化的 API 调用清单，报告页按「每个抓取到的 API」逐条展示。实现方须在
     * <b>测试主线程</b>调用（典型时机：场景收尾 flush 队列）。
     *
     * @param line 已脱敏的 API 调用摘要行
     */
    default void recordApiOperation(String line) {
    }

    /**
     * 当前（或最近）活动步骤的 id —— 供<b>非测试线程</b>在证据产生时抓取归属。
     *
     * <p>存在的理由：route 命中发生在 Playwright 事件线程，该线程没有测试线程的 ThreadLocal 上下文；
     * 若不在证据产生时抓取 stepId，等到主线程 flush 时已回落到用例级，per-case 页的步骤下就看不到
     * Route Captured（全部掉进「用例级兜底」卡片）。取不到步骤上下文时返回 {@code null}。</p>
     *
     * <p>默认 {@code null}：未装配报告引擎时调用方按「用例级」处理。</p>
     */
    default String currentStepId() {
        return null;
    }

    /**
     * 记录一条已捕获的 API 调用，并<b>显式归属到 {@code stepId}</b>。
     *
     * <p>与 {@link #recordApiOperation(String)} 的差别仅在归属来源：调用方在证据产生时已抓到
     * stepId（见 {@link #currentStepId()}），故 flush 时不再依赖当时线程的上下文。stepId 为空时
     * 实现方按 {@link #recordApiOperation(String)} 处理。</p>
     *
     * @param stepId 归属步骤 id；{@code null}/空表示用例级
     * @param line   已脱敏的 API 调用摘要行
     */
    default void recordApiOperation(String stepId, String line) {
        recordApiOperation(line);
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

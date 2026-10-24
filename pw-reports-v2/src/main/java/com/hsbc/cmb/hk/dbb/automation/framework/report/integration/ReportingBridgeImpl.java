package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.CapturedScreenshot;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ScrubGate;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link ReportingBridge} 的 v2 实现 —— 把框架层推送落到 {@link ReportBus}。
 *
 * <p>web / route 经 {@code ReportingBridgeRegistry.get()} 取得本实现（通过
 * {@code META-INF/services} SPI 发现），从而在不依赖 pw-reports-v2 编译期类型的前提下，
 * 把截图 / 证据 / 场景结果写入 v2 报告。本实现不引入任何 serenity 依赖。
 *
 * <p>线程语义：所有写入均经 {@link ReportBus#captureHandle()} 解析当前步骤句柄
 * （场景线程内隐式嵌套），与 {@code ReportBus} 既有并发模型一致；非步骤上下文（如场景边界）
 * 句柄为 NOOP，写入安全跳过。
 */
public final class ReportingBridgeImpl implements ReportingBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportingBridgeImpl.class);

    private final ReportBus bus = ReportBus.getDefault();

    @Override
    public boolean isReportingActive() {
        return bus.isEnabled();
    }

    @Override
    public void attachScreenshot(CapturedScreenshot screenshot) {
        if (screenshot == null || screenshot.pngFile() == null || !screenshot.pngFile().exists()) {
            return;
        }
        try {
            byte[] png = Files.readAllBytes(screenshot.pngFile().toPath());
            bus.attachScreenshot(bus.captureHandle(), png);
        } catch (IOException e) {
            LOGGER.warn("[ReportingBridgeImpl] attachScreenshot failed for {}: {}",
                    screenshot.name(), e.toString());
        }
    }

    @Override
    public void attachHtml(String name, String htmlContent) {
        if (htmlContent == null || htmlContent.isEmpty()) {
            return;
        }
        bus.attachHtml(bus.captureHandle(), name, htmlContent);
    }

    @Override
    public void attachEvidence(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        bus.attachEvidence(text);
    }

    @Override
    public void recordReportData(String key, Object value) {
        if (key == null) {
            return;
        }
        bus.attachEvidence(key + " = " + (value == null ? "null" : value));
    }

    @Override
    public void recordDownloadableFile(String title, Path file) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        bus.attachDownloadableFile(file);
    }

    @Override
    public void attachTrace(Path traceZip) {
        if (traceZip == null || !Files.exists(traceZip)) {
            return;
        }
        //  句柄→用例归属的解析统一收口在 ReportBus（NOOP → activeOutcomeId 兜底），bridge 仅透传。
        bus.attachTrace(bus.captureHandle(), traceZip);
    }

    @Override
    public void attachTrace(Path traceZip, String traceStartTime, String traceEndTime, long traceDurationMs) {
        if (traceZip == null || !Files.exists(traceZip)) {
            return;
        }
        bus.attachTrace(bus.captureHandle(), traceZip, traceStartTime, traceEndTime, traceDurationMs);
    }

    @Override
    public Path traceTarget() {
        //  指导框架把 trace 直接导出到 per-case（<outputDir>/<outcomeId>/trace.zip）——不产生
        //    "traces/ + per-case" 两份、也无须移动。收尾线程（CloseGuard worker）无 ThreadLocal 句柄
        //    （恒 NOOP）时，回落到"跨线程可见的活动用例 id"解析落点，使 trace 始终直接落 per-case，
        //    从源头消除"traces/ 生成 + 事后拷贝/移动"的副本逻辑（与 attachTrace 兜底同源、同并行限制）。
        StepHandle handle = bus.captureHandle();
        if (!handle.isNoop()) {
            return bus.traceTarget(handle);
        }
        String outcomeId = bus.activeOutcomeId();
        return outcomeId != null ? bus.traceTargetByOutcomeId(outcomeId) : null;
    }

    @Override
    public Path traceTargetByOutcomeId(String outcomeId) {
        return bus.traceTargetByOutcomeId(outcomeId);
    }

    @Override
    public Path screenshotTarget() {
        //  指导框架把截图写进 per-case（<outputDir>/<outcomeId>/screenshots），
        //   避免 sha256 命名的截图散落在 target/site/serenity 根目录。
        StepHandle handle = bus.captureHandle();
        if (handle.isNoop()) {
            return null;
        }
        return bus.screenshotTarget(handle);
    }

    @Override
    public Path videoTarget() {
        //  指导框架把录屏直接写进 per-case（<outputDir>/<outcomeId>/records），避免「全局 videoDir
        //    先落一份、再搬进 per-case」的两份。收尾线程无 ThreadLocal 句柄时，回落到"跨线程可见的
        //    活动用例 id"解析落点，使录屏始终直接录进 per-case（与 attachVideo 兜底同源、同并行限制）。
        StepHandle handle = bus.captureHandle();
        if (!handle.isNoop()) {
            return bus.videoTarget(handle);
        }
        String outcomeId = bus.activeOutcomeId();
        return outcomeId != null ? bus.videoTargetByOutcomeId(outcomeId) : null;
    }

    @Override
    public Path videoTargetByOutcomeId(String outcomeId) {
        return bus.videoTargetByOutcomeId(outcomeId);
    }

    @Override
    public void attachVideo(Path video) {
        if (video == null || !Files.exists(video)) {
            return;
        }
        //  句柄→用例归属的解析统一收口在 ReportBus（NOOP → activeOutcomeId 兜底），bridge 仅透传。
        bus.attachVideo(bus.captureHandle(), video);
    }

    @Override
    public String currentOutcomeId() {
        return bus.activeOutcomeId();
    }

    @Override
    public void attachVideo(String outcomeId, Path video) {
        if (video == null || !Files.exists(video)) {
            return;
        }
        //  显式 outcomeId（来自 context 绑定）→ 收尾线程也能精确归属，不再依赖 ThreadLocal 句柄 /
        //    全局"活动用例"最佳努力；id 缺失时回落句柄解析（既有语义）。
        if (outcomeId != null) {
            bus.attachVideoToOutcome(outcomeId, video);
            return;
        }
        bus.attachVideo(bus.captureHandle(), video);
    }

    @Override
    public void attachTrace(String outcomeId, Path traceZip) {
        if (traceZip == null || !Files.exists(traceZip)) {
            return;
        }
        if (outcomeId != null) {
            bus.attachTraceToOutcome(outcomeId, traceZip, null, null, 0L);
            return;
        }
        bus.attachTrace(bus.captureHandle(), traceZip);
    }

    @Override
    public void attachTrace(String outcomeId, Path traceZip,
                           String traceStartTime, String traceEndTime, long traceDurationMs) {
        if (traceZip == null || !Files.exists(traceZip)) {
            return;
        }
        if (outcomeId != null) {
            bus.attachTraceToOutcome(outcomeId, traceZip, traceStartTime, traceEndTime, traceDurationMs);
            return;
        }
        bus.attachTrace(bus.captureHandle(), traceZip, traceStartTime, traceEndTime, traceDurationMs);
    }

    @Override
    public void recordRouteRegistration(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        bus.recordRouteRegistration(line);
    }

    /** 复用的脱敏器（无状态、线程安全）：API 行可能携带 URL query 中的 token / key / password。 */
    private static final ScrubGate SCRUB = ScrubGate.defaults();

    @Override
    public void recordApiOperation(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        bus.recordApiOperation(SCRUB.scrubLine(line));
    }

    @Override
    public String currentStepId() {
        return bus.activeStepId();
    }

    @Override
    public void recordApiOperation(String stepId, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        if (stepId == null || stepId.isBlank()) {
            bus.recordApiOperation(SCRUB.scrubLine(line));
            return;
        }
        bus.recordApiOperationByStep(stepId, SCRUB.scrubLine(line));
    }

    @Override
    public void recordScenario(String scenarioName, String result, long durationMs) {
        bus.attachEvidence("SCENARIO: " + (scenarioName == null ? "" : scenarioName)
                + " result=" + result + " durationMs=" + durationMs);
    }

    @Override
    public void flushPendingApiOperations() {
        //  v2 的 API 操作证据由 NetworkEvidenceBridge 自治写入，无需主线程冲刷
    }

    @Override
    public void discardPendingApiOperations() {
        //  v2 无独立待报告队列，no-op
    }
}

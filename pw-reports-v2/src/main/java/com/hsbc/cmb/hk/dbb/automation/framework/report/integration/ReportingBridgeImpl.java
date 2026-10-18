package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.CapturedScreenshot;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
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
        //  场景收尾线程、case 尚未 finishCase 时可解析到 case 级句柄；此后 finishCase 已 remove → NOOP。
        //    NOOP 时静默跳过（trace 仍已落盘，仅不展示按钮），打 debug 便于定位接线/顺序问题。
        StepHandle handle = bus.captureHandle();
        if (handle.isNoop()) {
            LOGGER.debug("[ReportingBridgeImpl] attachTrace skipped (no active case handle) for {}", traceZip);
            return;
        }
        bus.attachTrace(handle, traceZip);
    }

    @Override
    public void recordRouteRegistration(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        bus.recordRouteRegistration(line);
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

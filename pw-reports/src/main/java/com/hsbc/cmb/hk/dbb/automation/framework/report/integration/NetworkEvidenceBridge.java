package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;

/**
 * 网络证据接缝（Tier2 #6）：框架侧 Playwright route 监控 / {@code RouteEvidenceRegistry}
 * 采集到一次网络调用后，调用本接口把它汇入 pw-reports 的<b>当前步骤证据</b>。
 *
 * <p>设计要点：
 * <ul>
 *   <li>脱敏由总线入口统一做（{@code attachEvidence} 内部过 {@code ScrubGate}），
 *       调用方只管传原始 {@link NetworkCall}，无需自管脱敏口径；</li>
 *   <li>异步安全：网络回调在 IO 线程时，先用 {@link ReportBus#captureHandle()} 取句柄，
 *       再 {@link #record(StepHandle, NetworkCall)} 传回，避免跨线程踩当前步骤上下文；</li>
 *   <li>框架侧具体适配（如何挂到 {@code RouteEvidenceRegistry} / 失败的 {@code RouteMonitorFailureReportSink}）
 *       在框架目录实现，本接口与默认实现零 serenity 依赖、可独立 adopt。</li>
 * </ul>
 */
public interface NetworkEvidenceBridge {

    /** 挂到当前步骤（无当前步骤 → 用例级证据）。 */
    void record(NetworkCall call);

    /** 显式句柄版（异步安全）。 */
    void record(StepHandle handle, NetworkCall call);
}

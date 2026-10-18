package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link NetworkEvidenceBridge} 默认实现：把 {@link NetworkCall} 格式化为一行
 * （含 header 键名、status、耗时），交给 {@link ReportBus#attachEvidence}——
 * 总线入口统一过 {@code ScrubGate}，敏感 url query / header 值被遮蔽。
 *
 * <p>同时作为 API 操作记录（{@code recordApiOperation}），使调用既出现在步骤证据、
 * 也出现在用例级 {@code apiOperations} 列表（与 Serenity 双口径对齐）。
 */
public final class DefaultNetworkEvidenceBridge implements NetworkEvidenceBridge {

    private static final Logger log = LoggerFactory.getLogger(DefaultNetworkEvidenceBridge.class);

    private final ReportBus bus;

    public DefaultNetworkEvidenceBridge(ReportBus bus) {
        this.bus = bus;
    }

    @Override
    public void record(NetworkCall call) {
        record(bus.captureHandle(), call);
    }

    @Override
    public void record(StepHandle handle, NetworkCall call) {
        if (call == null) {
            return;
        }
        String line = "NET " + call.toOneLine();
        try {
            bus.attachEvidence(handle, line);
            bus.recordApiOperation(handle, line);
        } catch (Throwable t) {
            log.debug("network evidence attach skipped: {}", t.toString());
        }
    }
}

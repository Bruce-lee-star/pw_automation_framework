package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.AgentTraceEvent;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.AgentTraceSink;
import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentTrace 上报 SPI 实现（R9）。把跨模块的 {@link AgentTraceEvent} 组装为 pw-reports 的
 * {@link AgentTrace} 并写入 {@link AgentTraceStore}；JVM 退出时一次性落盘（与其它诊断同编排器）。
 *
 * <p>失败静默：审计不得影响主流程（与执行链埋点的 {@code catch (Throwable)} 一致）。</p>
 */
public final class AgentTraceSinkImpl implements AgentTraceSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTraceSinkImpl.class);

    private static final AtomicReference<AgentTraceStore> STORE =
            new AtomicReference<>(new AgentTraceStore());

    static {
        ShutdownCoordinator.register(ShutdownCoordinator.ORDER_DIAGNOSTICS, "agent-trace",
                AgentTraceSinkImpl::writeDefaultIfAny);
    }

    /** 当前累积的 trace store（测试/报表可读取）。 */
    public static AgentTraceStore store() {
        return STORE.get();
    }

    @Override
    public void record(AgentTraceEvent e) {
        try {
            STORE.get().record(AgentTrace.forAction(
                    e.stepId(), e.policyVersion(), e.actionCategory(), e.rawTargetUrl(),
                    e.durationMs(), e.success(), e.approvalRef(), null));
        } catch (Throwable t) {
            LOGGER.debug("[agent-trace] record failed, skipped: {}", t.toString());
        }
    }

    /** JVM 退出时落盘（无数据则跳过）。 */
    public static void writeDefaultIfAny() {
        try {
            AgentTraceStore s = STORE.get();
            if (s.size() == 0) {
                return;
            }
            Path dir = Paths.get(System.getProperty("pw.agent.trace.dir", "target/agent-trace"));
            Path file = AgentTraceWriter.writeTo(dir, "agent-trace.json", s);
            LOGGER.debug("[agent-trace] wrote {}", file);
        } catch (Throwable t) {
            LOGGER.debug("[agent-trace] write failed, skipped: {}", t.toString());
        }
    }
}

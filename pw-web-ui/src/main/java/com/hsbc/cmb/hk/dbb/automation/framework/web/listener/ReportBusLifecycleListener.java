package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber.OutcomeIdFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 框架内置 {@link FrameworkListener}：把 web / 框架-bus 生命周期桥接进 {@link ReportBus}，
 * 使<b>非 Cucumber</b> 运行也能产出 TestOutcome（对齐 框架「任何测试引擎都产出 outcome」语义）。
 *
 * <p><b>防重复计数</b>：Cucumber 路由 {@code PwReportPlugin} 驱动 {@link ReportBus} 产出 outcome，
 * 并在运行开始打 {@code ReportBus.ReportSource.CUCUMBER} 标记；本监听器仅在 source 为
 * {@code GENERIC} 时驱动 outcome 生命周期，Cucumber 路下退化为 no-op（证据 enrich 仍由既有
 * {@code ReportingBridge} 路径处理，不受影响）。</p>
 *
 * <p><b>显式开启</b>：受系统属性 {@code pw.report.generic.bridge.enabled} 门控（默认 false）。
 * 理由：本框架主用 Cucumber，且 {@code pw.report.enabled} 默认 true，若无条件驱动会在单测 /
 * 非报告环境写出报告产物；业务需在「非 Cucumber 的 框架-bus 报告运行」中开启此开关。</p>
 *
 * <p><b>已知差异</b>（additive-only 约束下可接受）：GENERIC 路不携带 tags / feature / uri
 * （{@link FrameworkListener} 不含），feature 记为 {@code "generic"}；步骤级失败不精确到步
 * （{@code afterStep} 不携结果），以场景级 {@code failed} 为准、失败步骤标记 FAILURE。</p>
 */
public final class ReportBusLifecycleListener implements FrameworkListener {

    private static final Logger logger = LoggerFactory.getLogger(ReportBusLifecycleListener.class);

    /** 显式开启开关（默认 false）——避免单测 / 非报告环境误写报告产物。 */
    private static final String GENERIC_BRIDGE_ENABLED = "pw.report.generic.bridge.enabled";

    /**
     * pw-reports 是否在 classpath（{@link ReportBus} 可加载）。本监听器经 SPI 常驻发现；
     * 缺席时静默 no-op，避免每个生命周期回调抛 {@link NoClassDefFoundError} 污染日志（运行仍被保护）。
     */
    private static final boolean REPORT_BUS_PRESENT = probeReportBus();

    private static boolean probeReportBus() {
        try {
            Class.forName("com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private final ThreadLocal<StepHandle> caseHandle = new ThreadLocal<>();
    private final ThreadLocal<Deque<StepHandle>> stepStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Long> caseStartNanos = new ThreadLocal<>();
    private final ThreadLocal<AtomicBoolean> scenarioFailed = ThreadLocal.withInitial(AtomicBoolean::new);

    @Override
    public void beforeScenario(String scenarioName) {
        if (!REPORT_BUS_PRESENT) {
            return; // pw-reports 缺席：静默 no-op
        }
        ReportBus bus = ReportBus.getDefault();
        if (!shouldDrive(bus)) {
            return;
        }
        String baseId = OutcomeIdFactory.timestampedId(stripScenarioSuffix(scenarioName));
        StepHandle handle = bus.startCase(baseId, "generic", scenarioName, List.of(), null);
        caseHandle.set(handle);
        caseStartNanos.set(System.nanoTime());
        scenarioFailed.get().set(false);
    }

    @Override
    public void afterScenario(String scenarioName, boolean failed) {
        if (!REPORT_BUS_PRESENT) {
            return; // pw-reports 缺席：静默 no-op
        }
        ReportBus bus = ReportBus.getDefault();
        if (!shouldDrive(bus)) {
            return;
        }
        StepHandle handle = caseHandle.get();
        caseHandle.remove();
        if (handle == null || handle.isNoop()) {
            return;
        }
        long durationMs = caseStartNanos.get() == null ? 0L
                : (System.nanoTime() - caseStartNanos.get()) / 1_000_000;
        caseStartNanos.remove();
        TestResult result = failed ? TestResult.FAILURE : TestResult.SUCCESS;
        bus.finishCase(handle, result, null, durationMs);
    }

    @Override
    public void beforeStep(String stepName) {
        if (!REPORT_BUS_PRESENT) {
            return; // pw-reports 缺席：静默 no-op
        }
        ReportBus bus = ReportBus.getDefault();
        if (!shouldDrive(bus)) {
            return;
        }
        StepHandle handle = bus.stepStarted(stepName);
        stepStack.get().push(handle);
    }

    @Override
    public void afterStep(String stepName) {
        if (!REPORT_BUS_PRESENT) {
            return; // pw-reports 缺席：静默 no-op
        }
        ReportBus bus = ReportBus.getDefault();
        if (!shouldDrive(bus)) {
            return;
        }
        StepHandle handle = stepStack.get().poll();
        if (handle == null || handle.isNoop()) {
            return;
        }
        TestResult result = scenarioFailed.get().get() ? TestResult.FAILURE : TestResult.SUCCESS;
        bus.stepFinished(handle, result, null);
    }

    @Override
    public void onFailure(String scenarioName, Throwable cause) {
        if (!REPORT_BUS_PRESENT) {
            return; // pw-reports 缺席：静默 no-op
        }
        ReportBus bus = ReportBus.getDefault();
        if (!shouldDrive(bus)) {
            return;
        }
        scenarioFailed.get().set(true);
        if (cause != null) {
            bus.attachEvidence("FAILURE: " + cause.getClass().getSimpleName() + ": "
                    + (cause.getMessage() == null ? "" : cause.getMessage()));
        }
    }

    // ---------------------------------------------------------------- internals

    /** 是否应当驱动 outcome 生命周期：总线启用 + 非 Cucumber 源 + 显式开关开启。 */
    private static boolean shouldDrive(ReportBus bus) {
        if (bus == null || !bus.isEnabled()) {
            return false;
        }
        if (bus.getSource() == ReportBus.ReportSource.CUCUMBER) {
            return false; // Cucumber 路已由 PwReportPlugin 产出 outcome
        }
        return Boolean.getBoolean(GENERIC_BRIDGE_ENABLED);
    }

    /** 剥离 FrameworkListener 注入的线程 ID + 时间戳后缀，使 outcomeId 稳定（R1）。 */
    private static String stripScenarioSuffix(String scenarioName) {
        if (scenarioName == null || scenarioName.isBlank()) {
            return "scenario";
        }
        // 形如 "name - <threadId> - <timestamp>" 的后缀：去掉末尾两段 " - xxx"
        String s = scenarioName.trim();
        int last = s.lastIndexOf(" - ");
        if (last > 0) {
            int prev = s.lastIndexOf(" - ", last - 1);
            if (prev > 0) {
                return s.substring(0, prev).trim();
            }
        }
        return s;
    }
}

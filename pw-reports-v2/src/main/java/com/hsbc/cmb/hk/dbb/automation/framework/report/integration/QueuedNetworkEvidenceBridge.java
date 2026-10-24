package com.hsbc.cmb.hk.dbb.automation.framework.report.integration;

import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ApiOperationKind;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 跨线程网络证据桥：**任意线程入队 + 测试主线程 flush**（Tier2 #6 真实接线形态）。
 *
 * <h2>为什么必须有它</h2>
 * <p>框架侧 {@code RouteEvidenceSink.record(...)} 发生在 <b>Playwright 事件线程</b>
 * （route 内核的调用线程），那条线程与 Cucumber 测试主线程<b>没有任何 ThreadLocal 关联</b>。
 * 此时直接调 {@link ReportBus#captureHandle()} 拿到的是 {@link StepHandle#NOOP}
 * ——证据会被静默丢弃（{@code attachEvidence} 对 noop 直接 return）。
 * 这与框架现有 {@code RouteEvidenceSink} 「入队 + 主线程 flush」是同一个道理，
 * 本类把它做成<b>可直接注册为 {@code RouteEvidenceSink} 实现</b>的形态。</p>
 *
 * <h2>与框架现有实现的差异（per-thread，非 static）</h2>
 * <p>框架的 {@code RouteEvidenceSink} 用 {@code static ConcurrentLinkedQueue} +
 * {@code static AtomicInteger}——JVM 全局单队列，并行场景下 A 场景的证据会被 B 场景的
 * flush 写进 B 的报告，且线程池复用时上个场景的残留会串到下个场景。本类队列是
 * <b>实例字段（默认每场景/每 glue 一个实例）</b>：不跨场景、不跨并行度串证据。</p>
 *
 * <h2>用法（框架侧接线契约，零改动本模块）</h2>
 * <pre>{@code
 * // 1) 场景装配期（主线程）创建并 capture 句柄——此刻 current 步骤上下文正确
 * QueuedNetworkEvidenceBridge bridge = new QueuedNetworkEvidenceBridge(bus);
 * StepHandle handle = bridge.capture();
 *
 * // 2) route 内核命中处（Playwright 事件线程）——只入队，绝不碰总线
 * bridge.record(operation, url, detail);
 *
 * // 3) 场景收尾（主线程）——批量写入报告
 * bridge.flush();
 * }</pre>
 *
 * <h2>降级语义</h2>
 * <ul>
 *   <li>队列上限 {@code maxPending}（默认 500），超限<b>丢最旧</b>并 WARN（防极端场景积压）；</li>
 *   <li>{@code flush} 在<b>无当前场景</b>（非 BDD 环境 / 纯单测）时：清空队列并 debug 日志返回，
 *       既不抛错也不积压——上报是旁路，绝不影响路由主流程；</li>
 *   <li>所有方法 fail-open：任何异常吞掉降 debug，绝不外抛打断被测流程。</li>
 * </ul>
 */
public final class QueuedNetworkEvidenceBridge implements NetworkEvidenceBridge {

    private static final Logger log = LoggerFactory.getLogger(QueuedNetworkEvidenceBridge.class);

    /** 单次待写入上限：防极端场景积压（丢最旧）。 */
    public static final int DEFAULT_MAX_PENDING = 500;

    private final ReportBus bus;

    /** 非线程安全队列：本类所有方法都在<b>单一 owner 线程</b>调用（入队=事件线程串行、flush=主线程串行）。 */
    private final Deque<String[]> pending = new ArrayDeque<>();

    private final int maxPending;

    /** 记录被丢弃的条数（flush 时汇总上报，便于回答"是否有证据没落进报告"）。 */
    private int dropped;

    public QueuedNetworkEvidenceBridge(ReportBus bus) {
        this(bus, DEFAULT_MAX_PENDING);
    }

    public QueuedNetworkEvidenceBridge(ReportBus bus, int maxPending) {
        this.bus = bus;
        this.maxPending = maxPending > 0 ? maxPending : DEFAULT_MAX_PENDING;
    }

    /**
     * 在<b>主线程、进入异步前</b>捕获当前步骤句柄。
     *
     * <p>返回的句柄要一直留着，在 {@link #flush(StepHandle)} 时传回——这是把
     * 「事件线程的证据」正确归位到「当时那个步骤」的唯一途径。</p>
     */
    public StepHandle capture() {
        return bus.captureHandle();
    }

    /**
     * route 内核命中处调用（<b>任意线程</b>，含 Playwright 事件线程）：只入队。
     *
     * <p>签名与框架 {@code RouteEvidenceSink.record(String operation, String url, String detail)}
     * 完全一致，故本类可直接作为该 SPI 的实现（lambda 适配亦可）。</p>
     */
    public void record(String operation, String url, String detail) {
        try {
            if (bus != null && !bus.isEnabled()) {
                return;
            }
            synchronized (pending) {
                if (pending.size() >= maxPending) {
                    pending.pollFirst();
                    dropped++;
                    log.warn("[Route] network evidence queue exceeded {} records — dropping the oldest "
                            + "(is a flush missing?)", maxPending);
                }
                pending.addLast(new String[] {
                        operation == null || operation.isBlank() ? "-" : operation,
                        url == null ? "-" : url,
                        detail == null ? "" : detail});
            }
        } catch (Throwable t) {
            // 上报是旁路：入队失败绝不影响路由主流程
            log.debug("[Route] network evidence enqueue skipped: {}", t.toString());
        }
    }

    /** 在<b>测试主线程</b>用先前 {@link #capture()} 的句柄批量写入报告。 */
    public void flush(StepHandle handle) {
        flush(handle, true);
    }

    /**
     * 主线程 flush：把队列逐条写入步骤证据 + 用例级 API 操作列表。
     *
     * @param attachToStep 是否同时挂到步骤证据（{@code false} 时只进用例级列表，
     *                     用于证据量大、只想在用例页汇总的场景）
     * @return 实际写入条数
     */
    public int flush(StepHandle handle, boolean attachToStep) {
        if (bus == null) {
            return 0;
        }
        // 先摘走整批再逐条写：避免写入过程被 record 追加的记录污染本次批次
        String[][] batch;
        int droppedNow;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return 0;
            }
            batch = pending.toArray(new String[0][]);
            pending.clear();
            droppedNow = dropped;
            dropped = 0;
        }
        if (batch.length == 0) {
            return 0;
        }
        if (handle == null || handle.isNoop()) {
            // 无当前场景（纯 JUnit 单测 / 收尾钩子漏调）：证据无处可去，整批丢弃。
            // 不抛错、不积压——上报是旁路，绝不影响路由主流程。
            log.debug("[Route] network evidence flush skipped (no active scenario), drained {} record(s)",
                    batch.length);
            return 0;
        }
        int written = 0;
        for (String[] item : batch) {
            try {
                String line = ApiOperationKind.CAPTURED + " " + item[0] + " " + item[1]
                        + (item[2].isEmpty() ? "" : " — " + item[2]);
                if (attachToStep) {
                    bus.attachEvidence(handle, line);
                }
                bus.recordApiOperation(handle, line);
                written++;
            } catch (Throwable t) {
                log.debug("[Route] network evidence write skipped: {}", t.toString());
            }
        }
        if (droppedNow > 0) {
            log.warn("[Route] {} network evidence record(s) were dropped before flush (queue overflow)", droppedNow);
        }
        if (written > 0) {
            log.info("[Route] network evidence written to report: {} record(s)", written);
        }
        return written;
    }

    /** 主线程 flush（用当前线程的句柄）。 */
    public int flush() {
        return flush(bus.captureHandle(), true);
    }

    /** 丢弃残留队列（场景被中断、收尾钩子未跑时兜底，防证据串到下个场景）。 */
    public int discard() {
        synchronized (pending) {
            int n = pending.size();
            pending.clear();
            dropped = 0;
            return n;
        }
    }

    /** 当前待写入条数（测试/诊断用）。 */
    public int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    // ── NetworkEvidenceBridge：直接持有句柄的场景（同步调用，如 APIRequestContext 拦截） ──

    @Override
    public void record(NetworkCall call) {
        if (call != null) {
            record(bus.captureHandle(), call);
        }
    }

    @Override
    public void record(StepHandle handle, NetworkCall call) {
        if (call == null || bus == null) {
            return;
        }
        try {
            String line = ApiOperationKind.CAPTURED + " " + call.toOneLine();
            bus.attachEvidence(handle, line);
            bus.recordApiOperation(handle, line);
        } catch (Throwable t) {
            log.debug("[Route] network evidence attach skipped: {}", t.toString());
        }
    }
}

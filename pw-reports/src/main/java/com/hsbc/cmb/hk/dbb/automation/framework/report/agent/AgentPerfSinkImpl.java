package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfSample;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.perf.AgentPerfSink;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 非功能基线的 SPI 出口实现（T9 / I-08）：内存聚合样本 → JVM 退出时产出「一页基线数据」。
 *
 * <p>与 R9 {@link AgentTraceSinkImpl} 同编排器（{@link ShutdownCoordinator#ORDER_DIAGNOSTICS}），
 * 但<b>刻意分离存储</b>：审计常开且受保留期限治理，基线默认关闭且只代表本次运行，
 * 二者混在一起会让「清退审计」误删基线、或「关闭采集」误关审计。</p>
 *
 * <p><b>有界性</b>：样本队列有硬上限 {@link #MAX_SAMPLES}，越界只丢弃新样本并打一次性
 * WARN，避免 100 步计划 × 多轮 CI 把内存吃满。<b>尽力而为</b>：记录与落盘的异常
 * 一律 {@code catch (Throwable)} 降级为 debug，绝不改变执行链结果或阻断 JVM 退出。</p>
 *
 * <p><b>产物路径</b>：系统属性 {@link #OUTPUT_DIR_KEY}（默认 {@code target/agent-perf}），
 * 支持 CI 指定工程目录外的绝对路径；{@code agent-perf-baseline.md} + 同名 JSON。
 * 采集关闭 / 无样本时<b>不生成任何文件</b>，保持产物目录整洁。</p>
 */
public final class AgentPerfSinkImpl implements AgentPerfSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentPerfSinkImpl.class);

    /** 产物目录（接受绝对路径，供 CI 落到工程目录之外）。 */
    public static final String OUTPUT_DIR_KEY = "pw.agent.perf.dir";
    private static final String DEFAULT_OUTPUT_DIR = "target/agent-perf";

    /** 并发上限配置键（展示用；实际钳制由 pw-web-ui 的 ConcurrentContextOptions 执行）。 */
    private static final String CONCURRENCY_CAP_KEY = "framework.playwright.concurrent.max";
    private static final String ADAPTIVE_CAP = "adaptive (CPU/2, clamp [2,32])";

    /** 单次运行的样本硬上限。 */
    public static final int MAX_SAMPLES = 200_000;

    private static final ConcurrentLinkedQueue<AgentPerfSample> SAMPLES = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static final AtomicBoolean OVERFLOW_WARNED = new AtomicBoolean();

    static {
        ShutdownCoordinator.register(ShutdownCoordinator.ORDER_DIAGNOSTICS, "agent-perf",
                AgentPerfSinkImpl::writeDefaultIfAny);
    }

    /** 当前样本快照（测试 / 报表读取）。 */
    public static List<AgentPerfSample> samples() {
        return List.copyOf(SAMPLES);
    }

    /** 清空样本（测试隔离；不影响其它诊断产物）。 */
    public static void clear() {
        SAMPLES.clear();
        COUNT.set(0);
        OVERFLOW_WARNED.set(false);
    }

    @Override
    public void record(AgentPerfSample sample) {
        if (sample == null) {
            return;
        }
        try {
            if (COUNT.get() >= MAX_SAMPLES) {
                if (OVERFLOW_WARNED.compareAndSet(false, true)) {
                    LOGGER.warn("[agent-perf] sample cap {} reached; further samples dropped", MAX_SAMPLES);
                }
                return;
            }
            SAMPLES.add(sample);
            COUNT.incrementAndGet();
        } catch (Throwable t) {
            LOGGER.debug("[agent-perf] sample dropped, execution unaffected: {}", t.toString());
        }
    }

    /** JVM 退出钩子入口（无样本时 no-op）。 */
    public static void writeDefaultIfAny() {
        flush();
    }

    /**
     * 主动落盘（CI / 测试可显式触发），返回 Markdown 路径；无样本时返回 {@code null}。
     * 任何失败都只记 debug 并返回 {@code null}，不影响调用方。
     */
    public static Path flush() {
        try {
            List<AgentPerfSample> snapshot = List.copyOf(SAMPLES);
            if (snapshot.isEmpty()) {
                return null;
            }
            AgentPerfBaseline baseline = AgentPerfBaseline.from(snapshot, resolveConcurrencyCap());
            Path dir = Paths.get(System.getProperty(OUTPUT_DIR_KEY, DEFAULT_OUTPUT_DIR));
            Path file = baseline.writeTo(dir);
            LOGGER.debug("[agent-perf] wrote baseline {}", file);
            return file;
        } catch (Throwable t) {
            LOGGER.debug("[agent-perf] baseline write failed, skipped: {}", t.toString());
            return null;
        }
    }

    private static String resolveConcurrencyCap() {
        try {
            String raw = ConfigReader.resolve(CONCURRENCY_CAP_KEY, ADAPTIVE_CAP);
            return raw == null || raw.isBlank() ? ADAPTIVE_CAP : raw;
        } catch (Throwable t) {
            LOGGER.debug("[agent-perf] concurrency cap resolution failed: {}", t.toString());
            return ADAPTIVE_CAP;
        }
    }
}

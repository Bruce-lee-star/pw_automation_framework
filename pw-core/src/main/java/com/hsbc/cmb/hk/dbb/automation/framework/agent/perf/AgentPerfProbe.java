package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 非功能采集的<b>唯一埋点门面</b>（T9 / I-08）：单步延迟 + 整计划端到端延迟 + 并发观测。
 *
 * <p><b>默认关闭时零开销</b>：{@link #start()} 不取 {@code System.nanoTime()}，
 * {@link #recordStep} / {@link #recordPlan} 在读到 {@code enabled == false} 后立即返回，
 * 既不解析 SPI 也不构造样本。配置只在<b>每个执行计划开始时</b>由 {@link #refresh()}
 * 解析一次并缓存到 volatile 字段，步骤级不再触碰配置层。</p>
 *
 * <p><b>两条铁律</b>：① 采集是尽力而为的诊断旁路，任何异常（含 SPI 加载失败、
 * sink 抛错）都不得改变执行链结果，故所有上报路径自带 {@code catch (Throwable)}；
 * ② 样本按<b>独立随机抽样</b>决定是否上报（canary 灰度），不做跨步骤 ThreadLocal 状态，
 * 避免并行场景下的状态泄漏与清理时序问题。</p>
 *
 * <p><b>覆盖口径</b>：单步耗时为墙钟时间且含该步的有限重试（与 R9 审计同源计时），
 * 幂等命中路径直接返回、<b>不产生样本</b>；整计划耗时含 harness acquire / release 与
 * 运行时组合根作用域。快照采集、浏览器冷启动与网络往返未单独拆分，见设计稿 §8.1.1。</p>
 */
public final class AgentPerfProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentPerfProbe.class);

    /** {@link #start()} 在采集关闭时的哨兵值。 */
    public static final long DISABLED = -1L;

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger PEAK_IN_FLIGHT = new AtomicInteger();

    private static volatile boolean enabled;
    private static volatile double sampleRate;

    private AgentPerfProbe() {
    }

    /**
     * 每个执行计划开始时刷新一次配置快照。调用点：{@code StepPlanRunner}、
     * {@code StepDriver.execute}、{@code StepVerifier.verify}（覆盖所有执行入口）。
     * 解析本身失败时安全降级为「关闭采集」。
     */
    public static void refresh() {
        try {
            boolean on = AgentPerfConfig.isEnabled();
            enabled = on;
            sampleRate = on ? AgentPerfConfig.sampleRate() : 0.0;
        } catch (Throwable t) {
            enabled = false;
            sampleRate = 0.0;
            LOGGER.debug("[agent-perf] config resolution failed, collection disabled: {}", t.toString());
        }
    }

    /** 当前缓存的开关状态（步骤级只读，不再解析配置）。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 开始一次计时；关闭时返回 {@link #DISABLED} 且<b>不取</b>高精度时间。 */
    public static long start() {
        return enabled ? System.nanoTime() : DISABLED;
    }

    /**
     * 进入一次执行作用域，返回本作用域观测到的并发数；关闭时返回 {@code 0}（不计数）。
     * 返回值须原样传给 {@link #exitScope(int)}，避免运行中切换开关导致的计数错配。
     */
    public static int enterScope() {
        if (!enabled) {
            return 0;
        }
        int now = IN_FLIGHT.incrementAndGet();
        PEAK_IN_FLIGHT.accumulateAndGet(now, Math::max);
        return now;
    }

    /** 与 {@link #enterScope()} 严格配对；{@code enteredInFlight <= 0} 时 no-op。 */
    public static void exitScope(int enteredInFlight) {
        if (enteredInFlight > 0) {
            IN_FLIGHT.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /** 观测到的并发峰值（进程内累计，供诊断与单测）。 */
    public static int peakInFlight() {
        return PEAK_IN_FLIGHT.get();
    }

    /** 当前在途执行数。 */
    public static int currentInFlight() {
        return IN_FLIGHT.get();
    }

    /** 上报单步样本。{@code startedAt} 来自 {@link #start()} 或执行链既有的 nanoTime 起点。 */
    public static void recordStep(long startedAt, boolean success, Throwable failure) {
        if (!enabled || startedAt == DISABLED || !shouldSample()) {
            return;
        }
        record(new AgentPerfSample(
                AgentPerfSample.STEP_BUCKET,
                1,
                elapsedMs(startedAt),
                success,
                success ? null : AgentPerfSample.FailureKind.of(failure),
                Math.max(1, IN_FLIGHT.get()),
                System.currentTimeMillis()));
    }

    /** 上报整计划端到端样本（1 / 10 / 100 步按步数自动分桶）。 */
    public static void recordPlan(long startedAt, int stepCount, boolean success,
                                  Throwable failure, int inFlight) {
        if (!enabled || startedAt == DISABLED || !shouldSample()) {
            return;
        }
        record(new AgentPerfSample(
                AgentPerfSample.planBucket(stepCount),
                Math.max(1, stepCount),
                elapsedMs(startedAt),
                success,
                success ? null : AgentPerfSample.FailureKind.of(failure),
                Math.max(1, inFlight),
                System.currentTimeMillis()));
    }

    private static void record(AgentPerfSample sample) {
        try {
            AgentPerfRegistry.get().record(sample);
        } catch (Throwable t) {
            LOGGER.debug("[agent-perf] sample dropped, execution unaffected: {}", t.toString());
        }
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static boolean shouldSample() {
        double rate = sampleRate;
        if (rate >= 1.0) {
            return true;
        }
        if (rate <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    /** 测试 seam：清空在途 / 峰值计数并按当前系统属性重新刷新配置。 */
    public static void resetForTest() {
        IN_FLIGHT.set(0);
        PEAK_IN_FLIGHT.set(0);
        refresh();
    }
}

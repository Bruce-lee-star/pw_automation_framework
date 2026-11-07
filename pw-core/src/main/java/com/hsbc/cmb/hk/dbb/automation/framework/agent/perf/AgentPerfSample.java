package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import java.util.Locale;
import java.util.Objects;

/**
 * 一次非功能基线测量的不可变样本（T9 / I-08）。
 *
 * <p>与 R9 审计事件 {@code AgentTraceEvent} <b>刻意分离</b>：审计是常开的最小元数据轨迹，
 * 本样本是默认关闭的诊断测量；二者字段语义、开关与消费方都不同，混用会让「关掉采集」
 * 变成「关掉审计」或反之。</p>
 *
 * <p><b>隐私</b>：只含桶名、步数、耗时、成功标志、失败类别、并发数与时间戳，
 * 不含 URL / 参数 / 异常消息 / 快照内容——避免把业务数据带进基线产物。
 * 失败类别由异常<b>类型与关键词</b>归一，不落原始 message。</p>
 *
 * @param bucket       统计桶：{@link #STEP_BUCKET}（单步）或 {@link #planBucket(int)}（整计划）
 * @param stepCount    该样本对应的计划步数；单步恒为 1
 * @param durationMs   墙钟耗时（毫秒，含该步的重试；不含幂等命中路径）
 * @param success      是否成功
 * @param failureKind  失败类别；成功时为 {@code null}
 * @param inFlight     采样瞬间的并发执行数（≥1），用于观测并发峰值
 * @param recordedAtMs 采样时刻（epoch millis），用于标注采样窗口
 */
public record AgentPerfSample(
        String bucket,
        int stepCount,
        long durationMs,
        boolean success,
        FailureKind failureKind,
        int inFlight,
        long recordedAtMs) {

    /** 单步（动作）延迟桶。 */
    public static final String STEP_BUCKET = "step";

    public AgentPerfSample {
        Objects.requireNonNull(bucket, "bucket");
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        if (stepCount < 1) {
            throw new IllegalArgumentException("stepCount must be >= 1");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0");
        }
        if (inFlight < 1) {
            throw new IllegalArgumentException("inFlight must be >= 1");
        }
    }

    /** 整计划端到端延迟桶：{@code plan-1} / {@code plan-10} / {@code plan-100} / {@code plan-N}。 */
    public static String planBucket(int stepCount) {
        return "plan-" + Math.max(1, stepCount);
    }

    /** 失败恢复维度（对齐设计稿 §8.1「浏览器崩溃 / 超时 / 取消 / 网络中断」）。 */
    public enum FailureKind {
        BROWSER_CRASH,
        TIMEOUT,
        NETWORK,
        CANCELLED,
        ASSERTION,
        UNKNOWN;

        /**
         * 从异常归一类目；只读取类名与消息关键词，<b>不返回、不存储原始消息</b>。
         * {@code null}（如命令结果为失败但未携带异常）归为 {@link #UNKNOWN}。
         */
        public static FailureKind of(Throwable t) {
            if (t == null) {
                return UNKNOWN;
            }
            String hay = (t.getClass().getName() + " " + t.getMessage()).toLowerCase(Locale.ROOT);
            if (hay.contains("targetclosed") || hay.contains("crash")
                    || hay.contains("browser has been closed") || hay.contains("browserclosed")) {
                return BROWSER_CRASH;
            }
            if (hay.contains("timeout") || hay.contains("timedout") || hay.contains("timed out")) {
                return TIMEOUT;
            }
            if (hay.contains("net::err") || hay.contains("network") || hay.contains("connection")
                    || hay.contains("socket") || hay.contains("econnreset")) {
                return NETWORK;
            }
            if (t instanceof InterruptedException || hay.contains("cancelled") || hay.contains("canceled")) {
                return CANCELLED;
            }
            if (t instanceof AssertionError || hay.contains("assertion")) {
                return ASSERTION;
            }
            return UNKNOWN;
        }
    }
}

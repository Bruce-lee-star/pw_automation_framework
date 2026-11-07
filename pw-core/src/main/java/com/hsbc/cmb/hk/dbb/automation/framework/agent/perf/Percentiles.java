package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 无第三方依赖的分位数计算（T9 / I-08）。
 *
 * <p>口径固定为 <b>nearest-rank</b>：对 N 个升序样本，第 p 百分位取第
 * {@code ceil(p/100 * N)} 个（1-based，边界钳制到 {@code [0, N-1]}）。
 * 选择该口径而非线性插值，是因为它<b>恒取真实观测值</b>、不会低估 p95，
 * 且结果可被人工从原始样本逐一复核——基线数据要与事实对齐，不宜引入插值假设。</p>
 *
 * <p>输入集合不会被修改（内部复制 + 排序）；空集合返回 {@link OptionalLong#empty()}，
 * 由渲染层显示 {@code n/a}，避免用 0 冒充「无数据」。</p>
 */
public final class Percentiles {

    /** 中位数。 */
    public static final double P50 = 50.0;
    /** 95 分位。 */
    public static final double P95 = 95.0;

    private Percentiles() {
    }

    /**
     * 计算分位数。
     *
     * @param values      耗时样本（毫秒）；元素不得为 {@code null}
     * @param percentile  百分位，闭区间 {@code [0, 100]}
     * @return 该分位对应的真实观测值；无样本时为 empty
     * @throws IllegalArgumentException percentile 越界 / NaN，或含 null 元素
     * @throws NullPointerException     values 为 {@code null}
     */
    public static OptionalLong percentile(Collection<Long> values, double percentile) {
        Objects.requireNonNull(values, "values");
        if (Double.isNaN(percentile) || percentile < 0.0 || percentile > 100.0) {
            throw new IllegalArgumentException("percentile must be within [0, 100]: " + percentile);
        }
        if (values.isEmpty()) {
            return OptionalLong.empty();
        }
        long[] sorted = new long[values.size()];
        int i = 0;
        for (Long value : values) {
            if (value == null) {
                throw new IllegalArgumentException("values must not contain null");
            }
            sorted[i++] = value;
        }
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        int index = Math.min(Math.max(rank - 1, 0), sorted.length - 1);
        return OptionalLong.of(sorted[index]);
    }
}

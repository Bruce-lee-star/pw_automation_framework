package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AgentTrace 收集与保留存储（R9）。
 *
 * <p>线程安全（{@link CopyOnWriteArrayList}）；两条保留策略可同时生效：
 * <ul>
 *   <li><b>容量上限</b>：超过 {@code maxEntries} 时环形淘汰最旧条目；</li>
 *   <li><b>保留期限</b>：{@link #deleteOlderThan(Instant)} 删除早于 cutoff 的条目（到点删除/合规清退）。</li>
 * </ul>
 *
 * <p>本类只持有已脱敏的 {@link AgentTrace}，自身不接触任何原始敏感值。</p>
 */
public final class AgentTraceStore {

    private final int maxEntries;
    /** 保留期限；{@code null} = 仅按容量，不自动过期。 */
    private final Duration retention;
    private final List<AgentTrace> traces = new CopyOnWriteArrayList<>();

    public AgentTraceStore() {
        this(10_000, Duration.ofDays(30));
    }

    public AgentTraceStore(int maxEntries, Duration retention) {
        this.maxEntries = Math.max(1, maxEntries);
        this.retention = retention;
    }

    /** 记录一条轨迹，并应用容量上限（环形淘汰最旧）。 */
    public void record(AgentTrace trace) {
        if (trace == null) {
            return;
        }
        traces.add(trace);
        while (traces.size() > maxEntries) {
            traces.remove(0);
        }
    }

    /** 当前快照（不可修改副本）。 */
    public List<AgentTrace> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(traces));
    }

    public int size() {
        return traces.size();
    }

    /**
     * 删除早于 cutoff 的条目（保留期限到点删除/合规清退）。
     *
     * @return 实际删除的条目数
     */
    public int deleteOlderThan(Instant cutoff) {
        if (cutoff == null) {
            return 0;
        }
        int before = traces.size();
        traces.removeIf(t -> t.recordedAt().isBefore(cutoff));
        return before - traces.size();
    }

    /** 清空全部（显式删除/测试隔离）。 */
    public void clear() {
        traces.clear();
    }

    /** 若配置了保留期限，返回该期限；否则 null（仅按容量）。 */
    public Duration retention() {
        return retention;
    }
}

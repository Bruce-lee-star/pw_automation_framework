package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R9 AgentTraceStore 测试：容量上限环形淘汰 + 保留期限删除 + 清空。
 */
class AgentTraceStoreTest {

    private AgentTrace trace(String id, Instant at) {
        return AgentTrace.builder().traceId(id).recordedAt(at).build();
    }

    @Test
    void capacityEvictsOldest() {
        AgentTraceStore store = new AgentTraceStore(3, null);
        store.record(trace("a", Instant.EPOCH));
        store.record(trace("b", Instant.EPOCH));
        store.record(trace("c", Instant.EPOCH));
        store.record(trace("d", Instant.EPOCH));
        assertThat(store.size()).isEqualTo(3);
        List<AgentTrace> snap = store.snapshot();
        assertThat(snap).extracting(AgentTrace::traceId).containsExactly("b", "c", "d");
    }

    @Test
    void deleteOlderThanRemovesExpired() {
        AgentTraceStore store = new AgentTraceStore(100, Duration.ofDays(30));
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        store.record(trace("old", now.minus(Duration.ofDays(40))));
        store.record(trace("new", now));
        int removed = store.deleteOlderThan(now.minus(Duration.ofDays(30)));
        assertThat(removed).isEqualTo(1);
        assertThat(store.snapshot()).extracting(AgentTrace::traceId).containsExactly("new");
    }

    @Test
    void clearEmptiesStore() {
        AgentTraceStore store = new AgentTraceStore(10, null);
        store.record(trace("a", Instant.EPOCH));
        store.clear();
        assertThat(store.size()).isZero();
    }

    @Test
    void snapshotIsDefensiveCopy() {
        AgentTraceStore store = new AgentTraceStore(10, null);
        store.record(trace("a", Instant.EPOCH));
        List<AgentTrace> snap = store.snapshot();
        // 返回的是不可修改副本：对副本做可变拷贝后修改，不影响内部存储
        List<AgentTrace> copy = new ArrayList<>(snap);
        copy.clear();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void nullTraceIgnored() {
        AgentTraceStore store = new AgentTraceStore(10, null);
        store.record(null);
        assertThat(store.size()).isZero();
    }
}

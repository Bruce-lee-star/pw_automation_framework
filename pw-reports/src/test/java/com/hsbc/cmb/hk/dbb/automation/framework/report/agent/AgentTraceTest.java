package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R9 AgentTrace 模型测试：验证"不保存原始 secret + 最小元数据"铁律。
 */
class AgentTraceTest {

    @Nested
    @DisplayName("目标 host 抽取（仅 host，绝不含 path/query secret）")
    class TargetHost {

        @Test
        void extractsHostOnlyFromAbsoluteUrl() {
            AgentTrace t = AgentTrace.forAction("s1", "v3.15", "NAVIGATE",
                    "https://api.example.com/v1/login?token=supersecret&user=bob", 12L, true, "AP-1", null);
            assertThat(t.targetHost()).isEqualTo("api.example.com");
            Map<String, Object> m = t.toMap();
            assertThat(m.get("targetHost")).isEqualTo("api.example.com");
            // 整条导出里不能出现 query 中的 secret
            assertThat(m.values()).noneMatch(v -> v != null && v.toString().contains("supersecret"));
            assertThat(m.values()).noneMatch(v -> v != null && v.toString().contains("token=supersecret"));
        }

        @Test
        void unparsableUrlYieldsPlaceholderNotRaw() {
            AgentTrace t = AgentTrace.forAction("s2", "v3.15", "NAVIGATE",
                    "::::not a url ::::", 1L, false, null, null);
            assertThat(t.targetHost()).isEqualTo("<unparsed>");
            assertThat(t.toMap().values()).noneMatch(v -> v != null && v.toString().contains("not a url"));
        }

        @Test
        void blankUrlYieldsUnknown() {
            AgentTrace t = AgentTrace.forAction("s3", "v3.15", "CLICK", "", 1L, true, null, null);
            assertThat(t.targetHost()).isEqualTo("<unknown>");
        }
    }

    @Nested
    @DisplayName("异常仅记 simpleName（不含可能带 secret 的 message）")
    class ErrorClass {

        @Test
        void recordsSimpleClassNameOnly() {
            IllegalStateException ex = new IllegalStateException("login failed for token=abc at https://x");
            AgentTrace t = AgentTrace.forAction("s4", "v3.15", "FILL", "https://x.com", 5L, false, null, ex);
            assertThat(t.errorClass()).isEqualTo("IllegalStateException");
            assertThat(t.outcome()).isEqualTo(AgentTrace.Outcome.FAILURE);
            assertThat(t.toMap().values()).noneMatch(v -> v != null && v.toString().contains("token=abc"));
            assertThat(t.toMap().values()).noneMatch(v -> v != null && v.toString().contains("login failed"));
        }

        @Test
        void nullErrorYieldsNullClassAndSuccess() {
            AgentTrace t = AgentTrace.forAction("s5", "v3.15", "CLICK", "https://x.com", 3L, true, "AP-9", null);
            assertThat(t.errorClass()).isNull();
            assertThat(t.outcome()).isEqualTo(AgentTrace.Outcome.SUCCESS);
        }
    }

    @Nested
    @DisplayName("构造与字段不变量")
    class Invariants {

        @Test
        void blankTraceIdRejected() {
            assertThatThrownBy(() -> AgentTrace.builder().traceId(" ").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void negativeDurationClampedToZero() {
            AgentTrace t = AgentTrace.builder().traceId("t-1").durationMs(-5L).build();
            assertThat(t.durationMs()).isZero();
        }

        @Test
        void recordedAtHonoured() {
            Instant fixed = Instant.parse("2026-11-05T00:00:00Z");
            AgentTrace t = AgentTrace.builder().traceId("t-2").recordedAt(fixed).build();
            assertThat(t.recordedAt()).isEqualTo(fixed);
        }
    }
}

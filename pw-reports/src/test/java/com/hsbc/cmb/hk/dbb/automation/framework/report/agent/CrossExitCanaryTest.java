package com.hsbc.cmb.hk.dbb.automation.framework.report.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.BodyDigest;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ScrubGate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1/R7 跨数据出口 canary 泄漏测试（设计 §10.4 / §11）。
 *
 * <p>断言每一条<b>数据出口通道</b>都经过脱敏，敏感值不会以明文落盘/推送：
 * 页面/快照文本、URL/链接、异常/堆栈、网络 header、网络 body，以及 R9 的 AgentTrace 通道。
 * 任一通道若把 secret 明文送出，对应用例即失败（canary 红灯）。</p>
 */
class CrossExitCanaryTest {

    private final ScrubGate gate = ScrubGate.defaults();

    @Nested
    @DisplayName("页面 / 快照文本出口")
    class PageExit {

        @Test
        void pageSourceWithTokenUrlIsScrubbed() {
            String pageSource = "<a href='https://app.example.com/home?token=SUPERSECRET&x=1'>go</a>";
            String out = gate.scrubLine(pageSource);
            assertThat(out).contains("token=***");
            assertThat(out).doesNotContain("SUPERSECRET");
        }
    }

    @Nested
    @DisplayName("URL / 链接出口")
    class UrlExit {

        @Test
        void navigationUrlQuerySecretMasked() {
            String nav = "navigate https://api.example.com/v1/users?api_key=AKIA-SECRET&page=2";
            String out = gate.scrubLine(nav);
            assertThat(out).contains("api_key=***");
            assertThat(out).doesNotContain("AKIA-SECRET");
            assertThat(out).contains("page=2");
        }
    }

    @Nested
    @DisplayName("异常 / 堆栈出口")
    class ExceptionExit {

        @Test
        void assertionMessageWithSecretIsScrubbed() {
            String stack = "AssertionError: expected visible but session=Bearer eyJ.secret.jwt at https://x.com?pwd=hunter2";
            String out = gate.scrubStackTrace(stack);
            assertThat(out).contains("[SCRUBBED]");
            assertThat(out).doesNotContain("eyJ.secret.jwt");
            // 相对/绝对 URL 中的 pwd 同样被打码
            assertThat(out).contains("pwd=***");
            assertThat(out).doesNotContain("hunter2");
        }
    }

    @Nested
    @DisplayName("网络 header 出口")
    class NetworkHeaderExit {

        @Test
        void authAndCookieHeadersMasked() {
            String headers = "Authorization: Bearer eyJ.abc.def\nCookie: sid=abcdef123456\nX-Api-Key: k-999\nContent-Type: application/json";
            String out = gate.scrubLine(headers);
            assertThat(out).contains("Authorization: ***");
            assertThat(out).contains("Cookie: ***");
            assertThat(out).contains("X-Api-Key: ***");
            assertThat(out).contains("Content-Type: application/json");
            assertThat(out).doesNotContain("eyJ.abc.def");
            assertThat(out).doesNotContain("sid=abcdef123456");
            assertThat(out).doesNotContain("k-999");
        }
    }

    @Nested
    @DisplayName("网络 body 出口")
    class NetworkBodyExit {

        @Test
        void bodyNeverInlinedOnlyHashAndPreview() {
            // body 内联的 URL 含 token —— 经 ScrubGate 脱敏后预览不含明文 secret
            byte[] body = "{\"url\":\"https://api.example.com/login?token=hunter2&user=bob\"}"
                    .getBytes(StandardCharsets.UTF_8);
            BodyDigest digest = gate.digestBody(body);
            assertThat(digest.hash()).startsWith("sha256:");
            assertThat(digest.preview()).doesNotContain("hunter2");
            assertThat(digest.preview()).contains("token=***").describedAs("预览仍脱敏，绝不内联原文");
        }

        @Test
        void emptyBodyYieldsEmptyDigest() {
            assertThat(gate.digestBody(null)).isEqualTo(BodyDigest.EMPTY);
            assertThat(gate.digestBody(new byte[0])).isEqualTo(BodyDigest.EMPTY);
        }
    }

    @Nested
    @DisplayName("R9 AgentTrace 通道（与 R1/R7 交叉）")
    class AgentTraceExit {

        @Test
        void agentTraceNeverRetainsRawSecret() {
            AgentTrace t = AgentTrace.forAction("step-x", "v3.15", "NAVIGATE",
                    "https://api.example.com/login?token=RAWSECRET&user=bob", 9L, true, "AP-1", null);
            assertThat(t.targetHost()).isEqualTo("api.example.com");
            assertThat(t.toMap().values()).noneMatch(v -> v != null && v.toString().contains("RAWSECRET"));
            assertThat(t.toMap().values()).noneMatch(v -> v != null && v.toString().contains("token=RAWSECRET"));
        }

        @Test
        void agentTraceErrorNeverRetainsRawMessage() {
            AgentTrace t = AgentTrace.forAction("step-y", "v3.15", "FILL", "https://x.com", 3L, false, null,
                    new IllegalStateException("fail token=ZZZ at https://x.com"));
            assertThat(t.errorClass()).isEqualTo("IllegalStateException");
            assertThat(t.toMap().values()).noneMatch(v -> v != null && v.toString().contains("token=ZZZ"));
        }
    }
}

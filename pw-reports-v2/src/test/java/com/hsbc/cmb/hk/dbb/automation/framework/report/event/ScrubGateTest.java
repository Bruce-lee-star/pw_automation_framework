package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScrubGate 规则回归（R3）——每条规则一个用例。
 *
 * <p>覆盖：URL query 敏感参数 / 敏感头 / 模式匹配（Bearer、JWT、sk-、长 base64、
 * 扩展模式）/ 行长截断 / UTF-8 边界 / body 摘要 / null 安全。
 */
class ScrubGateTest {

    private final ScrubGate gate = ScrubGate.defaults();

    @Nested
    @DisplayName("URL query 敏感参数")
    class UrlQuery {

        @Test
        void masksSensitiveParamValues() {
            String out = gate.scrubLine("GET https://api.example.com/v1/users?token=abc123&page=2&sig=zzz");
            assertThat(out).contains("token=***").contains("sig=***");
            assertThat(out).contains("page=2"); // 非敏感参数原样
        }

        @Test
        void masksAllConfiguredParamNames() {
            for (String param : List.of("token", "sig", "signature", "key", "secret",
                    "password", "pwd", "code", "state")) {
                // 参数名含敏感词即 masking（contains 语义）；相对路径与绝对 URL 双形态
                String relative = "GET /v1/items?" + param + "=supersecret&page=2";
                assertThat(gate.scrubLine(relative)).contains(param + "=***");
                assertThat(gate.scrubLine(relative)).contains("page=2");
                String absolute = "POST https://api.example.com/v1/items?" + param + "=supersecret";
                assertThat(gate.scrubLine(absolute)).contains(param + "=***");
            }
        }

        @Test
        void relativeRequestLineScrubbed() {
            // 无 scheme 的请求行是证据线的常见形态（Playwright 请求记录）
            assertThat(gate.scrubLine("GET /api/orders?page=1&sig=abc123"))
                    .isEqualTo("GET /api/orders?page=1&sig=***");
        }

        @Test
        void caseInsensitiveParamMatch() {
            assertThat(gate.scrubLine("https://x.test/a?AccessToken=abc&Keep=1"))
                    .contains("AccessToken=***").contains("Keep=1");
        }

        @Test
        void urlWithoutQueryUntouched() {
            String url = "https://api.example.com/v1/users";
            assertThat(gate.scrubLine(url)).isEqualTo(url);
        }

        @Test
        void fragmentAndTrailingAmpHandled() {
            assertThat(gate.scrubLine("https://x.test/a?token=abc&")).contains("token=***");
        }
    }

    @Nested
    @DisplayName("敏感头")
    class Headers {

        @Test
        void masksHeaderValues() {
            assertThat(gate.scrubLine("Authorization: Bearer xyz"))
                    .isEqualTo("Authorization: ***");
            assertThat(gate.scrubLine("Set-Cookie: session=abc"))
                    .isEqualTo("Set-Cookie: ***");
            assertThat(gate.scrubLine("X-Api-Key: 12345"))
                    .isEqualTo("X-Api-Key: ***");
            assertThat(gate.scrubLine("X-CSRF-Token: t0ken"))
                    .isEqualTo("X-CSRF-Token: ***");
        }

        @Test
        void nonSensitiveHeadersUntouched() {
            assertThat(gate.scrubLine("Content-Type: application/json"))
                    .isEqualTo("Content-Type: application/json");
        }

        @Test
        void quotedHeaderValuesMasked() {
            assertThat(gate.scrubLine("Authorization: \"Bearer abc\""))
                    .isEqualTo("Authorization: ***");
        }
    }

    @Nested
    @DisplayName("模式匹配")
    class Patterns {

        @Test
        void bearerTokenScrubbed() {
            assertThat(gate.scrubLine("sending Bearer abc.def-ghi_jkl"))
                    .isEqualTo("sending [SCRUBBED]");
        }

        @Test
        void jwtScrubbed() {
            String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.dozjgNryP4J3jVmNHl0w5N";
            assertThat(gate.scrubLine("token=" + jwt)).isEqualTo("token=[SCRUBBED]");
        }

        @Test
        void skKeyScrubbed() {
            assertThat(gate.scrubLine("sk-abcdefghijklmnopqrst"))
                    .isEqualTo("[SCRUBBED]");
        }

        @Test
        void longBase64Scrubbed() {
            String b64 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZWZnaGlqa2xtbm9wcXJz";
            assertThat(gate.scrubLine("data=" + b64)).isEqualTo("data=[SCRUBBED]");
        }

        @Test
        void shortBase64NotScrubbed() {
            assertThat(gate.scrubLine("id=YWJjZGVm")).isEqualTo("id=YWJjZGVm");
        }

        @Test
        void extraPatternsFromConfigApplied() {
            ScrubGate custom = new ScrubGate(List.of(Pattern.compile("PII-\\d+")), 2048, 256);
            assertThat(custom.scrubLine("record PII-12345 done")).isEqualTo("record [SCRUBBED] done");
        }
    }

    @Nested
    @DisplayName("截断与编码")
    class Truncation {

        @Test
        void longLineTruncatedWithMarker() {
            // 注意：内容必须是「不构成泄漏形态」的长文本（如带空格的句子）。
            // 5000 个连续 x 会被 base64 规则整体 [SCRUBBED]——那是正确行为，另见 Patterns 测试。
            String raw = "user input payload ".repeat(500);
            String out = gate.scrubLine(raw);
            assertThat(out).endsWith("…[truncated]");
            assertThat(out.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
        }

        @Test
        void multibyteNotCutIntoReplacementChar() {
            // 2000 个汉字 = 6000 字节 > 2048 → 截断点必须落在字符边界
            String raw = "测".repeat(2000);
            String out = gate.scrubLine(raw);
            assertThat(out).doesNotContain("\uFFFD");
            assertThat(out.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
        }

        @Test
        void shortLineUnchanged() {
            assertThat(gate.scrubLine("hello")).isEqualTo("hello");
        }

        @Test
        void nullAndEmptySafe() {
            assertThat(gate.scrubLine(null)).isEmpty();
            assertThat(gate.scrubLine("")).isEmpty();
        }

        @Test
        void stackTraceScrubbed() {
            assertThat(gate.scrubStackTrace("AssertionError: expected https://x.test/a?token=secret to be 200"))
                    .contains("token=***");
        }
    }

    @Nested
    @DisplayName("body 摘要")
    class BodyDigestRules {

        @Test
        void hashStableAndPreviewScrubbed() {
            byte[] body = "{\"token\":\"abcdef123456\"}".getBytes(StandardCharsets.UTF_8);
            BodyDigest d1 = gate.digestBody(body);
            BodyDigest d2 = gate.digestBody(body);
            assertThat(d1.hash()).isEqualTo(d2.hash());
            assertThat(d1.hash()).startsWith("sha256:");
            assertThat(d1.hash()).hasSize("sha256:".length() + 16);
            // 摘要 hex 与直接 sha256 前 16 位一致
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
                assertThat(d1.hash()).isEqualTo("sha256:" + HexFormat.of().formatHex(digest).substring(0, 16));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        @Test
        void previewIsScrubbedAndBounded() {
            byte[] body = ("{\"authorization\":\"Bearer " + "z".repeat(500) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            BodyDigest digest = gate.digestBody(body);
            assertThat(digest.preview()).doesNotContain("z".repeat(100));
            assertThat(digest.preview().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256);
        }

        @Test
        void emptyBodyDigest() {
            assertThat(gate.digestBody(new byte[0])).isEqualTo(BodyDigest.EMPTY);
            assertThat(gate.digestBody(null)).isEqualTo(BodyDigest.EMPTY);
        }
    }

    @Test
    @DisplayName("配置化的行上限与扩展模式经由 PwReportsConfig 贯通")
    void configWiring() {
        PwReportsConfig config = PwReportsConfig.builder()
                .limits(50, 128, 500)
                .extraScrubPatterns(List.of(Pattern.compile("SECRET-\\d+")))
                .build();
        ScrubGate configured = new ScrubGate(config.getExtraScrubPatterns(),
                config.getMaxEvidenceLineBytes(), 256);
        assertThat(configured.scrubLine("SECRET-99")).isEqualTo("[SCRUBBED]");
        assertThat(configured.scrubLine("y".repeat(500))
                .getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(128);
    }
}

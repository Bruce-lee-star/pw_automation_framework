package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiCallBlock} 解析门禁——报告卡片的结构化数据来源。
 *
 * <p>覆盖：标准管道格式逐字段解析、失败块（{@code ERR} 行替代 Response 行）、
 * 历史单行格式降级（整体作 URL、不抛不丢）、空值安全。
 */
class ApiCallBlockTest {

    @Test
    @DisplayName("标准管道格式：方法/URL/状态/耗时 + 四个分区行")
    void parsesStructuredBlock() {
        ApiCallBlock call = ApiCallBlock.parse(
                "REST|POST|https://api.example.com/api/session|401|86 ms\n"
                        + "REQH|Request headers|Content-Type=application/json\n"
                        + "REQB|Request body|{\"user\":\"wp7uat2\"}\n"
                        + "RESH|Response headers|Content-Type=text/html\n"
                        + "RESB|Response body|{\"error\":\"invalid credentials\"}");

        assertThat(call.getMethod()).isEqualTo("POST");
        assertThat(call.getUrl()).isEqualTo("https://api.example.com/api/session");
        assertThat(call.getStatus()).isEqualTo("401");
        assertThat(call.getElapsed()).isEqualTo("86 ms");
        assertThat(call.getRows()).hasSize(4);
        assertThat(call.getRows().get(0))
                .containsEntry("label", "Request headers")
                .containsEntry("value", "Content-Type=application/json");
        assertThat(call.getRows().get(3))
                .containsEntry("label", "Response body");
        //  response body 是 JSON → 经 prettyIfJson 美化（多行缩进、含原始键值）
        String pretty = call.getRows().get(3).get("value");
        assertThat(pretty).startsWith("{").contains("invalid credentials");
        assertThat(pretty.indexOf("\n")).isPositive(); // 多行：已被美化缩进
    }

    @Test
    @DisplayName("JSON 值按标准 JSON 排版（冒号后单空格）并带 json 标记")
    void jsonValuesUseStandardFormatting() {
        ApiCallBlock call = ApiCallBlock.parse(
                "REST|GET|https://api.example.com/x|200|5 ms\n"
                        + "RESB|Response body|{\"account\":null,\"used\":true}");

        Map<String, String> body = call.getRows().get(0);
        assertThat(body).containsEntry("json", "true");
        //  Jackson 默认输出 `"account" : null`（冒号前后都有空格）——用户反馈"不像 JSON"，
        //  故必须显式改写为标准写法 `"account": null`。
        assertThat(body.get("value"))
                .contains("\"account\": null")
                .doesNotContain("\" : ");
        //  精确断言缩进：2 空格层级（Jackson 的 pretty printer 是有状态对象，复用实例会让
        //  _nesting 泄漏成 4/6 空格，必须每次重新 createInstance）
        assertThat(body.get("value")).isEqualTo("{\n  \"account\": null,\n  \"used\": true\n}");
    }

    @Test
    @DisplayName("失败块：状态列 FAILED，ERR 行进入分区（无 Response 行）")
    void parsesFailedBlock() {
        ApiCallBlock call = ApiCallBlock.parse(
                "REST|GET|https://api.example.com/getotp.htm|FAILED|1234 ms\n"
                        + "REQH|Request headers|<none>\n"
                        + "REQB|Request body|<none>\n"
                        + "ERR|Error|java.net.SocketTimeoutException: timeout");

        assertThat(call.getStatus()).isEqualTo("FAILED");
        assertThat(call.getRows()).hasSize(3);
        assertThat(call.getRows().get(2))
                .containsEntry("label", "Error")
                .containsEntry("value", "java.net.SocketTimeoutException: timeout");
    }

    @Test
    @DisplayName("值内含管道符不破坏分栏（上报侧已编码为 %7C，此处按 3 段上限切分）")
    void valueMayContainSeparator() {
        ApiCallBlock call = ApiCallBlock.parse(
                "REST|GET|https://api.example.com/x|200|5 ms\n"
                        + "RESB|Response body|a|b|c");

        assertThat(call.getRows()).hasSize(1);
        assertThat(call.getRows().get(0)).containsEntry("value", "a|b|c");
    }

    @Test
    @DisplayName("历史单行格式降级：整体作 URL，无分区行，不抛异常")
    void legacySingleLineFallsBackToUrl() {
        ApiCallBlock call = ApiCallBlock.parse("POST /api/session?token=*** -> 401");

        assertThat(call.getMethod()).isEmpty();
        assertThat(call.getStatus()).isEmpty();
        assertThat(call.getUrl()).isEqualTo("POST /api/session?token=*** -> 401");
        assertThat(call.getRows()).isEmpty();
    }

    @Test
    @DisplayName("null/空白安全：全部字段空、无分区行")
    void nullAndBlankAreSafe() {
        assertThat(ApiCallBlock.parse(null).getRows()).isEmpty();
        assertThat(ApiCallBlock.parse("   ").getRows()).isEmpty();
        assertThat(ApiCallBlock.parse(null).getUrl()).isEmpty();
    }

    @Test
    @DisplayName("Route Captured 结构化块：与 API Request 同构（能力标签 + 方法/URL/状态/耗时 + 分区）")
    void parsesCapturedRouteBlock() {
        ApiCallBlock call = ApiCallBlock.parseCaptured(
                "[Route MONITOR] https://host/api/v2/auth/assert?*** — {\n"
                        + "  pattern   : auth/assert\n"
                        + "  request   : POST https://host/api/v2/auth/assert?ad=webapplication_cxp_sit1&did=56ab36e6\n"
                        + "  duration  : 417ms\n"
                        + "  reqHeaders:\n"
                        + "    accept: */*\n"
                        + "    content-type: application/json\n"
                        + "  reqBody:\n"
                        + "    {\n"
                        + "      \"assert\": \"authenticate\"\n"
                        + "    }\n"
                        + "  response  : 200\n"
                        + "  respHeaders:\n"
                        + "    content-type: application/json\n"
                        + "  respBody:\n"
                        + "    {\n"
                        + "      \"ok\": true\n"
                        + "    }\n"
                        + "  rule      : method=POST url=https://host/api/v2/auth/assert expectStatus=200 timeout=60s\n"
                        + "  stage     : HIT (outcome is reported by the matching 'RESULT' block)\n"
                        + "}");

        assertThat(call.getKind()).isEqualTo("MONITOR");
        assertThat(call.getMethod()).isEqualTo("POST");
        assertThat(call.getUrl()).isEqualTo("https://host/api/v2/auth/assert?ad=webapplication_cxp_sit1&did=56ab36e6");
        assertThat(call.getStatus()).isEqualTo("200");
        assertThat(call.getElapsed()).isEqualTo("417ms");
        //  分区顺序与 API Request 对齐：Request headers/body → Response headers/body，规则信息殿后
        assertThat(call.getRows()).extracting(row -> row.get("label"))
                .containsExactly("Pattern", "Request headers", "Request body",
                        "Response headers", "Response body", "Rule", "Stage");
        //  请求头改按 JSON 对象展示（2026-10-22 用户反馈）：不再是 "k=v; k=v" 拼成一坨
        assertThat(call.getRows().get(1)).containsEntry("json", "true");
        assertThat((String) call.getRows().get(1).get("value"))
                .contains("\"accept\"").contains("*/*")
                .contains("\"content-type\"").contains("application/json");
        //  请求体被识别为 JSON → 美化 + json 标记（模板改用 <pre class="api-json">）
        assertThat(call.getRows().get(2)).containsEntry("json", "true");
        assertThat(call.getRows().get(2).get("value")).contains("\"assert\": \"authenticate\"");
    }

    @Test
    @DisplayName("Route Captured 单行镜像：NET 行解析出方法与状态，能力标签回落 CAPTURED")
    void parsesCapturedConsoleLine() {
        ApiCallBlock call = ApiCallBlock.parseCaptured("NET GET https://api.example.com/profile/list -> 200 (18 ms)");

        assertThat(call.getKind()).isEqualTo("CAPTURED");
        assertThat(call.getMethod()).isEqualTo("GET");
        assertThat(call.getUrl()).isEqualTo("https://api.example.com/profile/list");
        assertThat(call.getStatus()).isEqualTo("200");
        assertThat(call.getElapsed()).isEqualTo("18 ms");
        assertThat(call.getRows()).isEmpty();
    }

    @Test
    @DisplayName("抓包体闭合 '}' 之后的 tail 不被截断：monitor 的 result / respBody (not read) 应可见")
    void capturedTailAfterClosingBraceIsPreserved() {
        //  模拟 MonitorSink.resultDetail() 经 detailWith 追加到 '}' 之后的 tail：
        //  通过态且仅校验 status 的 monitor 不读响应体，应给出 (not read — ...) 说明而非静默缺失。
        String raw = "[Route MONITOR] https://host/x — {\n"
                + "  pattern   : auth/assert\n"
                + "  reqHeaders:\n"
                + "    accept: application/json\n"
                + "  response  : 200\n"
                + "  respHeaders:\n"
                + "    content-type: application/json\n"
                + "}\n"
                + "  result    : PASS actual=200 expect=200 elapsed=417ms\n"
                + "  respBody  : (not read — event-stream bodies are deliberately not read)";
        ApiCallBlock call = ApiCallBlock.parseCaptured(raw);

        assertThat(call.getKind()).isEqualTo("MONITOR");
        assertThat(call.getMethod()).isEmpty(); // 块内无 method 分区
        //  tail 应被解析为分区行（旧实现 lastIndexOf('}') 会截掉它们）
        assertThat(call.getRows()).extracting(r -> r.get("label"))
                .contains("Result", "Response body");
        assertThat((String) call.getRows().stream()
                .filter(r -> "Response body".equals(r.get("label"))).findFirst().orElseThrow().get("value"))
                .contains("(not read");
        assertThat((String) call.getRows().stream()
                .filter(r -> "Result".equals(r.get("label"))).findFirst().orElseThrow().get("value"))
                .contains("actual=200 expect=200");
    }

    @Test
    @DisplayName("请求/响应头渲染为 JSON 对象（而非 k=v 拼接）")
    void headersAreRenderedAsJson() {
        String raw = "[Route CAPTURED] https://host/x — {\n"
                + "  reqHeaders:\n"
                + "    accept: */*\n"
                + "    content-type: application/json\n"
                + "  respHeaders:\n"
                + "    content-type: text/plain\n"
                + "  response  : 200\n"
                + "}";
        ApiCallBlock call = ApiCallBlock.parseCaptured(raw);

        assertThat(call.getRows()).extracting(r -> r.get("label"))
                .contains("Request headers", "Response headers");
        Map<String, String> req = call.getRows().stream()
                .filter(r -> "Request headers".equals(r.get("label"))).findFirst().orElseThrow();
        assertThat(req).containsEntry("json", "true");
        assertThat(req.get("value"))
                .contains("\"accept\":").contains("\"content-type\":").contains("application/json");
    }

    @Test
    @DisplayName("Route Captured 无法解析时降级为 Detail 分区：不抛异常、原文不丢")
    void capturedUnknownShapeKeepsDetail() {
        ApiCallBlock call = ApiCallBlock.parseCaptured("[Route MOCK] https://host/x — {not-a-section}");

        assertThat(call.getKind()).isEqualTo("MOCK");
        assertThat(call.getRows()).hasSize(1);
        assertThat(call.getRows().get(0)).containsEntry("label", "Detail");
        assertThat(call.getRows().get(0).get("value")).contains("not-a-section");
    }

    @Test
    @DisplayName("Route Captured 响应体不再截断：体分区只有 Response body，无截断说明行")
    void capturedResponseBodyIsNeverTruncated() {
        String longValue = "a".repeat(6000);
        ApiCallBlock call = ApiCallBlock.parseCaptured(
                "[Route MONITOR] https://host/x — {\n"
                        + "  response  : 200\n"
                        + "  respBody:\n"
                        + "    {\n"
                        + "      \"ok\": true,\n"
                        + "      \"note\": \"" + longValue + "\"\n"
                        + "    }\n"
                        + "}");

        assertThat(call.getRows()).extracting(row -> row.get("label"))
                .containsExactly("Response body");
        assertThat(call.getRows().get(0)).containsEntry("json", "true");
        //  整份体（含 6000 字符长值）必须完整进入卡片：块尾 '}' 不得污染响应体
        assertThat(call.getRows().get(0).get("value"))
                .contains("\"note\": \"" + longValue + "\"")
                .endsWith("}");
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.bridge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** stdio 帧协议契约：帧纯净、notification 静默、错误码与工具目录边界。 */
public class McpStdioServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void initializeReturnsProtocolVersionAndCapabilities() throws Exception {
        JsonNode frame = one("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}");

        assertEquals(McpProtocol.PROTOCOL_VERSION, frame.at("/result/protocolVersion").asText());
        assertTrue(frame.at("/result/capabilities/tools").isObject());
        assertEquals("pw-agent-driver-mcp", frame.at("/result/serverInfo/name").asText());
        assertEquals("1", frame.at("/id").asText());
        assertTrue("桥只做协议与门禁，必须明示零 LLM 调用",
                frame.at("/result/instructions").asText().toLowerCase().contains("never calls an llm"));
    }

    @Test
    public void toolsListExposesOnlyPromptAndCheck() throws Exception {
        JsonNode frame = one("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonNode tools = frame.at("/result/tools");
        assertTrue(tools.isArray());
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode tool : tools) {
            names.add(tool.at("/name").asText());
            assertEquals("inputSchema 必须拒绝额外参数（防路径/白名单注入）",
                    false, tool.at("/inputSchema/additionalProperties").asBoolean(true));
            JsonNode properties = tool.at("/inputSchema/properties");
            for (String forbidden : List.of("origins", "sources", "out", "features", "keepValues")) {
                assertFalse("不得暴露参数: " + forbidden, properties.has(forbidden));
            }
        }
        assertEquals(Set.of("prompt", "check"), names);
        assertFalse("impact 不得出现在 tools/list", names.contains("impact"));
    }

    @Test
    public void utf8BomOnFirstFrameDoesNotBreakHandshake() throws Exception {
        List<JsonNode> frames = exchange(
                "\uFEFF{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

        assertEquals(2, frames.size());
        assertFalse("带 BOM 的 initialize 首帧不得回 parse error", frames.get(0).has("error"));
        assertEquals(McpProtocol.PROTOCOL_VERSION,
                frames.get(0).at("/result/protocolVersion").asText());
    }

    @Test
    public void notificationDoesNotProduceFrame() throws Exception {
        assertTrue(exchange("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .isEmpty());
    }

    @Test
    public void unknownMethodAndMalformedJsonProduceRpcErrors() throws Exception {
        List<JsonNode> frames = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"nope\"}",
                "not json at all",
                "[1,2,3]");

        assertEquals(3, frames.size());
        assertEquals(McpProtocol.METHOD_NOT_FOUND, frames.get(0).at("/error/code").asInt());
        assertEquals(McpProtocol.PARSE_ERROR, frames.get(1).at("/error/code").asInt());
        assertEquals(McpProtocol.PARSE_ERROR, frames.get(2).at("/error/code").asInt());
        assertEquals("2.0", frames.get(0).at("/jsonrpc").asText());
    }

    @Test
    public void capabilityProbesAndPingAreServedWithoutFramesLeaking() throws Exception {
        List<JsonNode> frames = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"prompts/list\"}");

        assertEquals(3, frames.size());
        assertTrue(frames.get(0).at("/result").isObject());
        assertTrue(frames.get(1).at("/result/resources").isArray());
        assertTrue(frames.get(2).at("/result/prompts").isArray());
    }

    @Test
    public void everyStdoutLineIsExactlyOneJsonFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String input = String.join("\n", List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/list\"}",
                "",
                "{\"jsonrpc\":\"2.0\",\"id\":\"b\",\"method\":\"ping\"}")) + "\n";
        int handled = McpStdioServer.run(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        String stdout = out.toString(StandardCharsets.UTF_8);
        assertEquals(2, handled);
        int lines = 0;
        for (String line : stdout.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            lines++;
            JsonNode frame = MAPPER.readTree(line);
            assertNotNull("stdout 出现非 JSON 行: " + line, frame);
            assertTrue(frame.isObject());
            assertEquals("2.0", frame.at("/jsonrpc").asText());
        }
        assertEquals(handled, lines);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static JsonNode one(String request) throws Exception {
        List<JsonNode> frames = exchange(request);
        assertEquals(1, frames.size());
        return frames.get(0);
    }

    private static List<JsonNode> exchange(String... requests) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String input = String.join("\n", requests) + "\n";
        int handled = McpStdioServer.run(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        List<JsonNode> frames = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                frames.add(MAPPER.readTree(line));
            }
        }
        assertEquals("回帧数必须与 handled 一致", handled, frames.size());
        return frames;
    }
}

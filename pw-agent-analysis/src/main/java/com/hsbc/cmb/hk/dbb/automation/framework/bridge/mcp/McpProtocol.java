package com.hsbc.cmb.hk.dbb.automation.framework.bridge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.cli.SkeletonCli;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/**
 * 最小 MCP / JSON-RPC 2.0 编解码（<b>手写、零 MCP SDK</b>）。
 *
 * <p>只实现 stdio 桥需要的四个方法：{@code initialize}、{@code tools/list}、
 * {@code tools/call}、{@code ping}，另对 {@code resources/list} / {@code prompts/list}
 * 回空列表以兼容会探测全部能力的 client。不引入 SDK 是刻意的：官方 SDK 通常带
 * HTTP/SSE transport 与网络客户端，而本框架的铁律是「零 LLM / 零凭据 / 零出网」；
 * 当前 reactor 也没有「禁网络客户端」的 ArchUnit 规则，靠不引入依赖来保证更可靠。</p>
 *
 * <p>退出码语义（对齐 {@link SkeletonCli}）：{@code 0} = 可采信；{@code 1} = 建议被拒或
 * 不等价，是<b>正常 tool result</b>（{@code isError=true}，模型可读诊断后重试）；
 * {@code 2} = 用法/IO/解析失败，映射 JSON-RPC {@code -32602}，不把 CLI 的
 * {@code System.exit} 语义带进协议。</p>
 */
public final class McpProtocol {

    /** 本桥实现的 MCP 协议版本。 */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private static final String SERVER_NAME = "pw-agent-driver-mcp";
    private static final String SERVER_VERSION = "1.0.0";
    private static final int MAX_DIAGNOSTIC_CHARS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpProtocol() {
    }

    /** 处理一条请求；返回 {@code null} 表示 notification，不应回帧。 */
    public static JsonNode handle(JsonNode request, SkeletonToolAdapter adapter) {
        if (request == null || !request.isObject()) {
            return parseError("request must be a JSON object");
        }
        JsonNode id = request.get("id");
        if (id == null || id.isNull()) {
            return null;
        }
        String method = text(request.get("method"));
        if (method == null || method.isEmpty()) {
            return error(id, INVALID_REQUEST, "method is required");
        }
        try {
            return switch (method) {
                case "initialize" -> response(id, initializeResult());
                case "ping" -> response(id, MAPPER.createObjectNode());
                case "tools/list" -> response(id, toolsListResult());
                case "resources/list" -> response(id, emptyListResult("resources"));
                case "prompts/list" -> response(id, emptyListResult("prompts"));
                case "tools/call" -> callTool(request.get("params"), id, adapter);
                default -> error(id, METHOD_NOT_FOUND, "unknown method: " + method);
            };
        } catch (IllegalArgumentException e) {
            return error(id, INVALID_PARAMS, e.getMessage());
        } catch (IOException e) {
            return error(id, INTERNAL_ERROR, "tool IO failed (see server stderr)");
        } catch (RuntimeException e) {
            return error(id, INTERNAL_ERROR, "internal tool error (see server stderr)");
        }
    }

    /** 顶层 JSON 解析失败时回帧（此时没有可用 id）。 */
    public static JsonNode parseError(String message) {
        return error(NullNode.getInstance(), PARSE_ERROR, message);
    }

    public static JsonNode error(JsonNode id, int code, String message) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.set("id", id == null ? NullNode.getInstance() : id);
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        frame.set("error", error);
        return frame;
    }

    private static JsonNode response(JsonNode id, JsonNode result) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.set("id", id);
        frame.set("result", result);
        return frame;
    }

    private static ObjectNode initializeResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.set("tools", MAPPER.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = MAPPER.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);
        result.put("instructions", "Bridge only: prompt + check. "
                + "This server never calls an LLM, opens a network connection or reads credentials.");
        return result;
    }

    private static ObjectNode toolsListResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", McpToolCatalog.tools());
        return result;
    }

    private static ObjectNode emptyListResult(String key) {
        ObjectNode result = MAPPER.createObjectNode();
        result.set(key, MAPPER.createArrayNode());
        return result;
    }

    private static JsonNode callTool(JsonNode params, JsonNode id, SkeletonToolAdapter adapter)
            throws IOException {
        if (params == null || !params.isObject()) {
            return error(id, INVALID_PARAMS, "params must be an object");
        }
        String name = text(params.get("name"));
        if (name == null || name.isEmpty()) {
            return error(id, INVALID_PARAMS, "tool name is required");
        }
        if (!McpToolCatalog.isExposed(name)) {
            if (McpToolCatalog.IMPACT.equals(name)) {
                return error(id, METHOD_NOT_FOUND,
                        "tool 'impact' is not exposed by this server; use the local CLI instead");
            }
            return error(id, METHOD_NOT_FOUND, "unknown tool: " + name);
        }
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = MAPPER.createObjectNode();
        }
        if (!arguments.isObject()) {
            return error(id, INVALID_PARAMS, "arguments must be an object");
        }
        Set<String> allowed = McpToolCatalog.allowedArguments(name);
        Iterator<String> fields = arguments.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                return error(id, INVALID_PARAMS, "unsupported argument: " + field);
            }
        }
        String skeletonJson = text(arguments.get("skeletonJson"));
        SkeletonToolAdapter.Outcome outcome = McpToolCatalog.CHECK.equals(name)
                ? adapter.check(skeletonJson, text(arguments.get("suggestionText")))
                : adapter.prompt(skeletonJson);
        return toolResult(id, outcome);
    }

    private static JsonNode toolResult(JsonNode id, SkeletonToolAdapter.Outcome outcome) {
        if (outcome.cliExit() == SkeletonCli.EXIT_INPUT_ERROR) {
            return error(id, INVALID_PARAMS,
                    "CLI rejected input: " + firstLine(outcome.diagnostics()));
        }
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode textBlock = MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", outcome.text());
        content.add(textBlock);
        result.set("content", content);
        result.put("isError", outcome.cliExit() != SkeletonCli.EXIT_OK);
        return response(id, result);
    }

    private static String firstLine(String diagnostics) {
        if (diagnostics == null || diagnostics.isBlank()) {
            return "no diagnostics";
        }
        String first = diagnostics.trim().lines().findFirst().orElse("no diagnostics");
        return first.length() > MAX_DIAGNOSTIC_CHARS
                ? first.substring(0, MAX_DIAGNOSTIC_CHARS) + "…" : first;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}

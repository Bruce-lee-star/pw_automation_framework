package com.hsbc.cmb.hk.dbb.automation.framework.bridge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.StepPlan;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedSkeleton;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedStep;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.SkeletonPromptBundle;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.llm.LlmGateway;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T13 端到端契约：prompt / check 共用 CLI 的确定性门禁，check 直接回传可采信 StepPlan，
 * impact 不暴露、白名单不由 client 注入、桥 classpath 上仍无 LLM 实现。
 */
public class McpToolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PAGE_REF = "https://www.example.com/logon";

    private String previousOrigins;

    @Before
    public void configureApprovedOriginsOnServer() {
        previousOrigins = System.getProperty(McpConfig.ORIGINS_PROPERTY);
        System.setProperty(McpConfig.ORIGINS_PROPERTY, "codegen");
    }

    @After
    public void restoreOrigins() {
        if (previousOrigins == null) {
            System.clearProperty(McpConfig.ORIGINS_PROPERTY);
        } else {
            System.setProperty(McpConfig.ORIGINS_PROPERTY, previousOrigins);
        }
    }

    @Test
    public void promptReturnsCliRedactedBundleVerbatim() throws Exception {
        String skeleton = twoStepSkeleton();

        JsonNode frame = call("prompt", args(skeleton, null));

        assertFalse("prompt 不应产生 RPC error: " + frame, frame.has("error"));
        assertFalse(frame.at("/result/isError").asBoolean(true));
        String text = contentText(frame);
        assertEquals("必须与 CLI 同源，不得另写一套提示词",
                SkeletonPromptBundle.of(RecordedSkeleton.parse(skeleton), true).trim(), text.trim());
        assertTrue("值必须占位化", text.contains("${value}"));
        assertFalse("业务值不得出域", text.contains("alice"));
    }

    @Test
    public void checkReturnsTrustworthyStepPlanInTheSameSession() throws Exception {
        String suggestion = "好的，结果如下：\n```json\n" + clickPlan() + "\n```\n";

        JsonNode frame = call("check", args(singleClickSkeleton(), suggestion));

        assertFalse("门禁通过时 isError 必须为 false: " + frame,
                frame.at("/result/isError").asBoolean(true));
        JsonNode payload = MAPPER.readTree(contentText(frame));
        assertTrue(payload.get("ok").asBoolean());
        JsonNode plan = payload.get("plan");
        assertNotNull("验收要求：一次会话直接拿到 StepPlan，不再手工复制", plan);
        assertEquals("plan-from-copilot", plan.get("planId").asText());
        assertEquals("role=button;name=Submit", plan.at("/steps/0/target").asText());
        assertNotNull("回传的 plan 必须能被 StepPlan.parse（路径 C 可直接消费）",
                StepPlan.parse(plan.toString()));
    }

    @Test
    public void divergentSuggestionIsAToolErrorWithoutPlan() throws Exception {
        String bad = clickPlan().replace("name=Submit", "name=Go");

        JsonNode frame = call("check", args(singleClickSkeleton(), bad));

        assertFalse("退出码 1 是正常 tool result，不是协议错误", frame.has("error"));
        assertTrue(frame.at("/result/isError").asBoolean());
        JsonNode payload = MAPPER.readTree(contentText(frame));
        assertFalse(payload.get("ok").asBoolean());
        assertTrue("被拒时不得回传计划", payload.get("plan").isNull());
        assertTrue(payload.get("summary").asText().contains("equivalent=false"));
    }

    @Test
    public void checkFailsClosedWithoutServerConfiguredOrigins() throws Exception {
        System.clearProperty(McpConfig.ORIGINS_PROPERTY);
        Assume.assumeTrue("测试环境不应预置该环境变量",
                System.getenv(McpConfig.ORIGINS_ENV) == null);

        JsonNode frame = call("check", args(singleClickSkeleton(), clickPlan()));

        assertEquals(McpProtocol.INVALID_PARAMS, frame.at("/error/code").asInt());
        assertTrue(frame.at("/error/message").asText().contains("approved origins"));
    }

    @Test
    public void impactIsNotCallableOverTheProtocol() throws Exception {
        JsonNode frame = call("impact", args(singleClickSkeleton(), null));

        assertEquals(McpProtocol.METHOD_NOT_FOUND, frame.at("/error/code").asInt());
        assertTrue(frame.at("/error/message").asText().contains("not exposed"));
    }

    @Test
    public void clientCannotInjectOriginsOrPathArguments() throws Exception {
        for (String forbidden : List.of("origins", "sources", "out", "features", "keepValues")) {
            ObjectNode arguments = MAPPER.createObjectNode();
            arguments.put("skeletonJson", singleClickSkeleton());
            arguments.put(forbidden, "anything");
            JsonNode frame = call("check", arguments.toString());
            assertEquals("字段 " + forbidden + " 必须被服务端拒绝",
                    McpProtocol.INVALID_PARAMS, frame.at("/error/code").asInt());
        }
    }

    @Test
    public void blankOrOversizedArgumentsAreRejectedWithoutCrashing() throws Exception {
        assertEquals(McpProtocol.INVALID_PARAMS,
                call("prompt", args("   ", null)).at("/error/code").asInt());
        String oversized = "x".repeat(SkeletonToolAdapter.MAX_INPUT_CHARS + 1);
        assertEquals(McpProtocol.INVALID_PARAMS,
                call("prompt", args(oversized, null)).at("/error/code").asInt());
        assertEquals(McpProtocol.INVALID_PARAMS,
                call("check", args(singleClickSkeleton(), "")).at("/error/code").asInt());
    }

    @Test
    public void noProductionLlmGatewayRegistrationAndNoBridgeDependency() throws Exception {
        // 层次说明：本模块测试 JVM 的 test-classes 里有 StubLlmGateway（LlmGatewayContractTest 用），
        // 直接断言 LlmGateways.resolveOrNull()==null 在此必然失败；IDE 启动 MCP server 只加载
        // main 产物，故按 SPI 资源 URL 区分 test-classes 与 main classes/jar，验证生产装配面为空。
        String resource = "META-INF/services/" + LlmGateway.class.getName();
        Enumeration<URL> urls = McpToolContractTest.class.getClassLoader().getResources(resource);
        List<String> production = new ArrayList<>();
        List<String> testOnly = new ArrayList<>();
        while (urls.hasMoreElements()) {
            String url = urls.nextElement().toExternalForm();
            if (url.contains("/test-classes/")) {
                testOnly.add(url);
            } else {
                production.add(url);
            }
        }
        assertTrue("main 产物不得注册 LlmGateway（生产 resolveOrNull() 须为 null）: " + production,
                production.isEmpty());
        assertFalse("测试 SPI 桩应存在，证明枚举本身有效（防 vacuous pass）", testOnly.isEmpty());

        for (Class<?> bridge : List.of(McpStdioServer.class, McpProtocol.class,
                SkeletonToolAdapter.class, McpToolCatalog.class, McpConfig.class)) {
            for (Method m : bridge.getDeclaredMethods()) {
                assertFalse("桥不得返回 LlmGateway: " + bridge.getSimpleName() + "." + m.getName(),
                        m.getReturnType() == LlmGateway.class);
                for (Class<?> parameter : m.getParameterTypes()) {
                    assertFalse("桥不得接收 LlmGateway: " + m.getName(),
                            parameter == LlmGateway.class);
                }
            }
        }
    }

    @Test
    public void temporarySkeletonFilesAreCleanedUp() throws Exception {
        call("prompt", args(singleClickSkeleton(), null));

        try (Stream<Path> tmp = Files.list(Paths.get(System.getProperty("java.io.tmpdir")))) {
            assertFalse("临时骨架文件必须即用即删",
                    tmp.anyMatch(p -> p.getFileName().toString().startsWith("pw-mcp-skeleton-")));
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static JsonNode call(String tool, String argumentsJson) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", tool);
        params.set("arguments", MAPPER.readTree(argumentsJson));
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", "tc");
        request.put("method", "tools/call");
        request.set("params", params);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        McpStdioServer.run(
                new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        List<JsonNode> frames = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                frames.add(MAPPER.readTree(line));
            }
        }
        assertEquals("一次 tools/call 必须恰好回一帧: " + out.toString(StandardCharsets.UTF_8),
                1, frames.size());
        return frames.get(0);
    }

    private static String args(String skeletonJson, String suggestionText) {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("skeletonJson", skeletonJson);
        if (suggestionText != null) {
            arguments.put("suggestionText", suggestionText);
        }
        return arguments.toString();
    }

    private static String contentText(JsonNode frame) {
        return frame.at("/result/content/0/text").asText();
    }

    private static String singleClickSkeleton() {
        return new RecordedSkeleton(RecordedSkeleton.SUPPORTED_SCHEMA_VERSION, "codegen", PAGE_REF,
                List.of(new RecordedStep("CLICK", "role=button;name=Submit", Map.of()))).toJson();
    }

    private static String twoStepSkeleton() {
        return new RecordedSkeleton(RecordedSkeleton.SUPPORTED_SCHEMA_VERSION, "codegen", PAGE_REF,
                List.of(RecordedStep.of("NAVIGATE", null, "url", PAGE_REF),
                        new RecordedStep("FILL", "role=textbox;name=User", Map.of("value", "alice"))))
                .toJson();
    }

    /** 与 RuleBasedIntentTranslator 的基线同构：plan 级 url 取值域 + 单 CLICK。 */
    private static String clickPlan() {
        return "{\"schemaVersion\":1,\"planId\":\"plan-from-copilot\","
                + "\"scope\":{\"url\":{\"allow\":[\"" + PAGE_REF + "\"]}},"
                + "\"steps\":[{\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"}]}";
    }
}

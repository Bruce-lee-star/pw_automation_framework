package com.hsbc.cmb.hk.dbb.automation.framework.report.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON 契约门禁（DR-1 additive-only）——序列化产物的每个 JSON 路径都必须声明在
 * {@code pw-reports-schema.json} 里：新增字段忘了更新 schema → 本测试红。
 *
 * <p>方向性说明：JSON ⊆ schema（产物不得包含未声明路径）。反向（schema 声明但产物
 * 没有）是允许的——NON_NULL 序列化下空字段省略，且 additive-only 允许先声明后启用。
 *
 * <p>刻意不引 JSON-schema 校验库：路径覆盖 + 锚点断言（required / reportVersion /
 * result 枚举 / duration 整数）已足够守住「消费方不会读到意外结构」这一核心风险。
 */
class SchemaContractTest {

    private static final String SCHEMA_RESOURCE = "/pw-reports-schema.json";

    @Test
    @DisplayName("序列化 JSON 的每个路径都在 pw-reports-schema.json 中声明")
    void everyJsonPathIsDeclaredInSchema() throws Exception {
        TestOutcome outcome = OutcomeFixtures.full();
        JsonNode root = JsonWriter.mapper().readTree(
                JsonWriter.mapper().writeValueAsString(outcome));
        JsonNode schema = readSchema();

        Set<String> paths = new LinkedHashSet<>();
        collectPaths(root, "$", paths);

        // 防 vacuous：fixture 必须产生足量路径，且关键路径必须出现
        assertThat(paths).hasSizeGreaterThan(20);
        assertThat(paths).contains(
                "$.reportVersion",
                "$.outcomeId",
                "$.userStory.storyName",
                "$.testSteps[].children[].stepId",
                "$.testSteps[].children[].evidence[]",
                "$.testSteps[].exception.message",
                "$.testSteps[].screenshot",
                "$.testFailureCause.errorType",
                "$.testFailureSummary",
                "$.apiOperations[]",
                "$.routeRegistrations[]");

        for (String path : paths) {
            assertThat(isDeclared(schema, path))
                    .as("JSON path %s is not declared in pw-reports-schema.json "
                            + "(additive-only contract: update the schema first)", path)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("契约锚点：required 齐备、reportVersion=1、result 入枚举、duration 为非负整数")
    void contractAnchorsHold() throws Exception {
        TestOutcome outcome = OutcomeFixtures.full();
        JsonNode root = JsonWriter.mapper().readTree(
                JsonWriter.mapper().writeValueAsString(outcome));
        JsonNode schema = readSchema();

        for (JsonNode required : schema.get("required")) {
            assertThat(root.has(required.asText()))
                    .as("required field %s missing from serialized outcome", required.asText())
                    .isTrue();
        }
        assertThat(root.get("reportVersion").intValue()).isEqualTo(1);
        assertThat(root.get("duration").isIntegralNumber()).isTrue();
        assertThat(root.get("duration").longValue()).isGreaterThanOrEqualTo(0);

        JsonNode resultEnum = schema.get("properties").get("result").get("enum");
        assertThat(resultEnum.isArray()).isTrue();
        List<String> allowed = new ArrayList<>();
        resultEnum.forEach(n -> allowed.add(n.asText()));
        assertThat(allowed).contains(root.get("result").asText());
    }

    // ---------------------------------------------------------------- path 收集

    /** 递归收集 JSON 路径；数组成员统一记 {@code field[]}（数组项共享同一 schema）。 */
    private static void collectPaths(JsonNode node, String path, Set<String> out) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(field -> {
                String child = path + "." + field;
                out.add(child);
                collectPaths(node.get(field), child, out);
            });
        } else if (node.isArray()) {
            String itemPath = path + "[]";
            out.add(itemPath);
            for (JsonNode item : node) {
                collectPaths(item, itemPath, out);
            }
        }
    }

    // ---------------------------------------------------------------- schema 解析

    /**
     * 路径是否在 schema 中声明。支持 {@code $ref}（{@code #/definitions/...}）与
     * array items 解引用。
     */
    private static boolean isDeclared(JsonNode schemaDoc, String path) {
        String[] tokens = path.substring(2).split("\\.");
        JsonNode current = schemaDoc;
        for (String token : tokens) {
            boolean arrayElement = token.endsWith("[]");
            String name = arrayElement ? token.substring(0, token.length() - 2) : token;
            JsonNode properties = propertiesOf(schemaDoc, current);
            if (properties == null || !properties.has(name)) {
                return false;
            }
            JsonNode next = properties.get(name);
            if (arrayElement) {
                JsonNode items = deref(schemaDoc, next).get("items");
                if (items == null) {
                    return false;
                }
                next = deref(schemaDoc, items);
            }
            current = next;
        }
        return true;
    }

    /** 取节点的 properties（自动解一层 $ref）。 */
    private static JsonNode propertiesOf(JsonNode schemaDoc, JsonNode node) {
        JsonNode resolved = deref(schemaDoc, node);
        return resolved.has("properties") ? resolved.get("properties") : null;
    }

    /** 沿 $ref 链解引用（同文档内指针，如 #/definitions/testStep；外部引用不在门禁范围）。 */
    private static JsonNode deref(JsonNode schemaDoc, JsonNode node) {
        JsonNode current = node;
        int guard = 0;
        while (current != null && current.has("$ref") && guard++ < 16) {
            String pointer = current.get("$ref").asText();
            if (!pointer.startsWith("#/")) {
                return current;
            }
            JsonNode target = schemaDoc;
            for (String segment : pointer.substring(2).split("/")) {
                target = target == null ? null : target.get(segment);
            }
            if (target == null) {
                return current;
            }
            current = target;
        }
        return current;
    }

    @Test
    @DisplayName("reader 容错：JSON 含未知字段时反序列化不抛异常（additive-only 前向兼容）")
    void readerToleratesUnknownFields() throws Exception {
        // 模拟未来版本新增字段（additive-only）后，旧消费方仍能读旧 POJO
        String jsonWithExtra = JsonWriter.mapper().writeValueAsString(OutcomeFixtures.full())
                .replaceFirst("\\{", "{\"futureField\":\"nope\",");
        TestOutcome read = JsonWriter.mapper().readValue(jsonWithExtra, TestOutcome.class);
        assertThat(read).isNotNull();
        assertThat(read.getOutcomeId()).isEqualTo(OutcomeFixtures.full().getOutcomeId());
        assertThat(read.getReportVersion()).isEqualTo(1);
        assertThat(read.getTestSteps()).isNotEmpty();
    }

    private static JsonNode readSchema() throws Exception {
        try (InputStream in = SchemaContractTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(in).as("schema resource %s must exist on the test classpath", SCHEMA_RESOURCE)
                    .isNotNull();
            return JsonWriter.mapper().readTree(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}

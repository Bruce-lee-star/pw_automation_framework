package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 结构化录制骨架（设计 §4.3「录制骨架 → StepPlan」的输入协议，全仓首个定义）。
 *
 * <p><b>fail-closed 输入契约</b>（§9 P1 验收）：
 * <ul>
 *   <li>仅支持 {@value #SUPPORTED_SCHEMA_VERSION} 版 schema；</li>
 *   <li>根 / step 白名单外字段一律拒绝（无 {@code ignoreUnknown}）；</li>
 *   <li>steps 非空且不超过 {@value #MAX_STEPS}（防止无界输入）；</li>
 *   <li>origin 非空白且仅含受控字符（批准与否在 {@link RuleBasedIntentTranslator} 侧按注入集合裁决）；</li>
 *   <li>字段长度有上界，避免把整页 HTML / 大段自然语言塞进最小化上下文。</li>
 * </ul>
 *
 * <p><b>确定性规范化</b>：{@link #normalized()} 统一动作大小写、trim 文本、参数键排序，
 * 使「语义相同、字面不同」的两份骨架产出<b>相同</b> {@code planDigest()}，作为等价性比较的基准
 * （§9 P1：相同规范化输入可比较出等价计划；不以自然语言输出作为唯一验收真值）。</p>
 */
public record RecordedSkeleton(int schemaVersion, String origin, String pageRef, List<RecordedStep> steps) {

    /** 本协议唯一支持的 schema 版本。 */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    /** 单骨架最大步骤数（有界）。 */
    public static final int MAX_STEPS = 200;
    /** 单字段最大字符数（有界）。 */
    public static final int MAX_FIELD_LENGTH = 2048;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ORIGIN_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");
    private static final String[] ROOT_FIELDS = {"schemaVersion", "origin", "pageRef", "steps"};
    private static final String[] STEP_FIELDS = {"action", "target", "params"};

    public RecordedSkeleton {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(pageRef, "pageRef");
        Objects.requireNonNull(steps, "steps");
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IntentTranslationException(IntentTranslationException.Reason.UNSUPPORTED_SCHEMA,
                    "unsupported schemaVersion: " + schemaVersion);
        }
        // 构造即规范化：不可变对象自持有合法值（trim/lowercase 后再 fail-closed 校验，
        // 不削弱约束——路径分隔符、空白、控制字符等仍被拒）
        origin = normalizeOrigin(origin);
        pageRef = pageRef.trim();
        if (pageRef.isBlank() || pageRef.length() > MAX_FIELD_LENGTH) {
            throw new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT,
                    "pageRef must be non-blank and bounded");
        }
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IntentTranslationException(IntentTranslationException.Reason.BOUNDS_VIOLATION,
                    "steps size must be in [1," + MAX_STEPS + "]: " + steps.size());
        }
        List<RecordedStep> copy = new ArrayList<>(steps.size());
        for (RecordedStep s : steps) {
            copy.add(Objects.requireNonNull(s, "step"));
        }
        steps = List.copyOf(copy);
    }

    /**
     * 归一化并校验 {@code origin}：trim → lowercase → 字符集/长度 fail-closed。
     *
     * <p><b>为何公开</b>：录制侧转换器（如 pw-codegen 的 {@code RecordedSkeletonConverter}）需要把
     * 页面 URL 归一成合法 origin，若各模块各抄一份正则，批准口径会随版本漂移。此方法是
     * 「origin 合法性」的<b>唯一事实来源</b>，构造器与本方法行为逐字一致。</p>
     *
     * @param raw 原始来源标识（如 host / host:port）
     * @return 归一化后的 origin（可直接写入骨架）
     * @throws IntentTranslationException {@link IntentTranslationException.Reason#INVALID_INPUT}
     *                                     —— 空白、超长或含白名单外字符
     */
    public static String normalizeOrigin(String raw) {
        Objects.requireNonNull(raw, "origin");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || !ORIGIN_PATTERN.matcher(normalized).matches()) {
            throw new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT,
                    "origin must match [a-z0-9][a-z0-9._:-]{0,127}: " + normalized);
        }
        return normalized;
    }

    // ---- JSON 输入（fail-closed） ----

    /** 从 JSON 解析录制骨架；任何结构不合规均抛 {@link IntentTranslationException}。 */
    public static RecordedSkeleton parse(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw reject(IntentTranslationException.Reason.INVALID_INPUT, "root must be JSON object");
            }
            rejectUnknownFields(root, ROOT_FIELDS);
            int schema = requireInt(root, "schemaVersion");
            if (schema != SUPPORTED_SCHEMA_VERSION) {
                throw reject(IntentTranslationException.Reason.UNSUPPORTED_SCHEMA,
                        "unsupported schemaVersion: " + schema);
            }
            String origin = requireBoundedText(root, "origin");
            String pageRef = requireBoundedText(root, "pageRef");
            JsonNode stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray()) {
                throw reject(IntentTranslationException.Reason.INVALID_INPUT, "steps must be array");
            }
            if (stepsNode.isEmpty() || stepsNode.size() > MAX_STEPS) {
                throw reject(IntentTranslationException.Reason.BOUNDS_VIOLATION,
                        "steps size must be in [1," + MAX_STEPS + "]: " + stepsNode.size());
            }
            List<RecordedStep> steps = new ArrayList<>(stepsNode.size());
            for (JsonNode stepNode : stepsNode) {
                steps.add(parseStep(stepNode));
            }
            return new RecordedSkeleton(schema, origin, pageRef, steps);
        } catch (IOException e) {
            throw new IntentTranslationException(IntentTranslationException.Reason.INVALID_INPUT,
                    "RecordedSkeleton JSON parse failed: " + e.getMessage(), e);
        }
    }

    private static RecordedStep parseStep(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw reject(IntentTranslationException.Reason.INVALID_INPUT, "each step must be JSON object");
        }
        rejectUnknownFields(node, STEP_FIELDS);
        String action = requireBoundedText(node, "action");
        String target = null;
        if (node.has("target") && !node.get("target").isNull()) {
            target = requireBoundedText(node, "target");
        }
        Map<String, String> params = new LinkedHashMap<>();
        JsonNode paramsNode = node.get("params");
        if (paramsNode != null && !paramsNode.isNull()) {
            if (!paramsNode.isObject()) {
                throw reject(IntentTranslationException.Reason.INVALID_INPUT, "step.params must be object");
            }
            Iterator<Map.Entry<String, JsonNode>> it = paramsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > MAX_FIELD_LENGTH) {
                    throw reject(IntentTranslationException.Reason.INVALID_INPUT, "param key invalid");
                }
                JsonNode v = e.getValue();
                if (v == null || !v.isValueNode()) {
                    throw reject(IntentTranslationException.Reason.INVALID_INPUT,
                            "param value must be scalar: " + e.getKey());
                }
                String text = v.asText();
                if (text.length() > MAX_FIELD_LENGTH) {
                    throw reject(IntentTranslationException.Reason.INVALID_INPUT,
                            "param value exceeds bound: " + e.getKey());
                }
                params.put(e.getKey(), text);
            }
        }
        return new RecordedStep(action, target, params);
    }

    private static void rejectUnknownFields(JsonNode node, String... allowed) {
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            boolean ok = false;
            for (String a : allowed) {
                if (a.equals(key)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw reject(IntentTranslationException.Reason.UNKNOWN_FIELD, "unknown field: " + key);
            }
        }
    }

    private static int requireInt(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) {
            throw reject(IntentTranslationException.Reason.INVALID_INPUT,
                    "missing/invalid integer field: " + key);
        }
        return v.asInt();
    }

    private static String requireBoundedText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw reject(IntentTranslationException.Reason.INVALID_INPUT, "missing/blank text field: " + key);
        }
        String text = v.asText();
        if (text.length() > MAX_FIELD_LENGTH) {
            throw reject(IntentTranslationException.Reason.INVALID_INPUT, "field exceeds bound: " + key);
        }
        return text;
    }

    private static IntentTranslationException reject(IntentTranslationException.Reason reason, String message) {
        return new IntentTranslationException(reason, message);
    }

    // ---- JSON 输出（与 parse 严格对称） ----

    /**
     * 序列化为 JSON（录制骨架的落盘/交接形态）。
     *
     * <p><b>与 {@link #parse(String)} 严格对称</b>：输出只含根白名单字段
     * （{@code schemaVersion}/{@code origin}/{@code pageRef}/{@code steps}）与 step 白名单字段
     * （{@code action}/{@code target}/{@code params}），因此 {@code parse(toJson())} 必成功且
     * 不丢语义（见 {@code RecordedSkeletonTest#toJsonRoundTripsThroughParse}）。</p>
     *
     * <p><b>确定性</b>：字段顺序固定、{@code params} 按键排序、{@code target} 为 {@code null} 时
     * 不输出该字段——同一骨架恒产出同一字符串，可直接入 git / 比对差异，不会因 Map 迭代顺序抖动。</p>
     *
     * <p><b>fail-closed</b>：空白 action、超 {@value #MAX_FIELD_LENGTH} 的 target / 参数键值一律拒绝
     * （{@link IntentTranslationException.Reason#INVALID_INPUT}），不产出「自己都读不回来」的 JSON。</p>
     *
     * @return 紧凑单行 JSON（UTF-8 语义，不含任何额外字段）
     */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", schemaVersion);
        root.put("origin", origin);
        root.put("pageRef", pageRef);
        ArrayNode stepsNode = root.putArray("steps");
        for (RecordedStep step : steps) {
            stepsNode.add(toJsonNode(step));
        }
        return root.toString();
    }

    private static ObjectNode toJsonNode(RecordedStep step) {
        String action = step.action();
        if (action.isBlank() || action.length() > MAX_FIELD_LENGTH) {
            throw reject(IntentTranslationException.Reason.INVALID_INPUT,
                    "action must be non-blank and bounded: " + action);
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("action", action);
        String target = step.target();
        if (target != null) {
            if (target.length() > MAX_FIELD_LENGTH) {
                throw reject(IntentTranslationException.Reason.INVALID_INPUT,
                        "target exceeds bound " + MAX_FIELD_LENGTH + ": " + action);
            }
            node.put("target", target);
        }
        ObjectNode paramsNode = node.putObject("params");
        // TreeMap：参数键排序，保证同一语义骨架的 JSON 逐字节一致
        new TreeMap<>(step.params()).forEach((key, value) -> {
            if (key.isBlank() || key.length() > MAX_FIELD_LENGTH
                    || value.length() > MAX_FIELD_LENGTH) {
                throw reject(IntentTranslationException.Reason.INVALID_INPUT,
                        "param out of bounds: " + action + "/" + key);
            }
            paramsNode.put(key, value);
        });
        return node;
    }

    // ---- 确定性规范化 ----

    /**
     * 返回规范化副本：动作名大写、文本 trim、参数键排序、步骤顺序保持。
     * 语义等价的骨架经此处理后字段完全一致。
     */
    public RecordedSkeleton normalized() {
        List<RecordedStep> norm = new ArrayList<>(steps.size());
        for (RecordedStep s : steps) {
            String action = s.action().trim().toUpperCase(Locale.ROOT);
            String target = s.target() == null ? null : s.target().trim();
            Map<String, String> sorted = new TreeMap<>();
            s.params().forEach((k, v) -> sorted.put(k.trim(), v == null ? "" : v.trim()));
            norm.add(new RecordedStep(action, target, sorted));
        }
        return new RecordedSkeleton(schemaVersion, origin.trim().toLowerCase(Locale.ROOT),
                pageRef.trim(), norm);
    }

    /** 供审计/报告的稳定标识（不含任何参数值，避免 R1 泄漏）。 */
    public String originId() {
        return "skeleton-v" + schemaVersion + ":" + origin;
    }
}

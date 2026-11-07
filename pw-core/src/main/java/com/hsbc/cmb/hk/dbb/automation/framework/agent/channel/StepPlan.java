package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope.ParamScope;

/**
 * 版本化步骤计划（设计 §4.3 + §4.3.1）：受控命令 {@link Command} 的有序集合，
 * 是「参数范围授权（v3.7）」的运行期载体。
 *
 * <p><b>复用而非新建</b>：直接以 {@link List}&lt;{@link Command}&gt; 承载步骤，
 * 不另建 Step/Target/Params 类型（Command 已含 ActionType + LocatorSpec target + params）。
 * 解析采用手动 JsonNode 遍历——Command/LocatorSpec 保持纯领域类型（无 Jackson 注解），
 * 而 §4.3 的 fail-closed 约束（未知字段拒绝、未知 ActionType 拒绝、NAVIGATE 不带 target、
 * ASSERT_* 无写入型参数）在此统一落实。</p>
 *
 * <p><b>target 互换形态</b>：JSON 中 target 以 {@link LocatorSpec#canonical()} 字符串表达
 * （框架既有的快照索引键 / 命令目标），经 {@link LocatorSpec#parse(String)} 还原；
 * 这是比 §4.3 草案里「对象形状」更稳健、且与现有快照体系一致的真实互换格式。</p>
 *
 * <p><b>指纹</b>：{@link #planDigest()} 风格对齐 {@code ApprovalGrant.planDigestOf}
 * （SHA-256 取前 8 字节十六进制，前缀 {@code stepplan-v1}），把「被批准的内容」压成可比较指纹，
 * 供后续参数范围授权绑定（v3.7 下一层）。</p>
 *
 * <p><b>摘要体系边界（T8）</b>：本指纹是<b> StepPlan 执行域</b>指纹，与 pw-codegen 中
 * {@code LocatorCandidate.planDigestOf()}（前缀 {@code locator-v1}，覆盖定位器回注域的
 * targetRef/spec/expectedRole）是<b>两套互不相通</b>的体系。两者前缀不同、覆盖域不同、用途不同
 * （前者是执行链审批绑定校验，后者是定位器回注域的 {@code ApprovalGrant#covers()} 比对），
 * 切勿混用或相互派生。</p>
 *
 * <p><b>参数范围</b>：可选的 {@code scope} 字段（参数名 → 取值域约束）随资产分发，
 * 并被 {@link #planDigest()} 绑定——scope 变化即指纹变化，使参数取值域与「被批准的内容」对齐；
 * 运行期由 {@code ParamScopeGate} 在 execute(Command) 边界落实 fail-closed 门禁。
 * scope 可分两层声明：plan 级（根 {@code scope}）与 step 级（每步可选 {@code scope}），
 * 后者按参数名覆盖前者，经 {@link #resolvedScope(int)} 取最终 scope。</p>
 */
public final class StepPlan {

    private static final String DIGEST_PREFIX = "stepplan-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int schemaVersion;
    private final String planId;
    private final List<Command> steps;
    private final ParamScope scope;
    private final List<ParamScope> stepScopes;

    @JsonCreator
    public StepPlan(@JsonProperty("schemaVersion") int schemaVersion,
                    @JsonProperty("planId") String planId,
                    @JsonProperty("steps") List<Command> steps,
                    @JsonProperty("scope") ParamScope scope,
                    @JsonProperty("stepScopes") List<ParamScope> stepScopes) {
        this.schemaVersion = schemaVersion;
        this.planId = Objects.requireNonNull(planId, "planId");
        this.steps = List.copyOf(steps);
        this.scope = Objects.requireNonNullElse(scope, ParamScope.EMPTY);
        this.stepScopes = (stepScopes == null) ? List.of() : List.copyOf(stepScopes);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String planId() {
        return planId;
    }

    public List<Command> steps() {
        return steps;
    }

    public ParamScope scope() {
        return scope;
    }

    /** 该步的 step 级 scope（未声明则返回 {@link ParamScope#EMPTY}）。 */
    public ParamScope scopeForStep(int index) {
        if (index >= 0 && index < stepScopes.size()) {
            return stepScopes.get(index);
        }
        return ParamScope.EMPTY;
    }

    /** 该步的最终 scope：step 级覆盖 plan 级（step 同名约束优先）。 */
    public ParamScope resolvedScope(int index) {
        return scopeForStep(index).composeWith(scope());
    }

    /**
     * 从 JSON DSL 解析（fail-closed：未知字段 / 未知动作 / 结构不合规均抛 {@link IllegalArgumentException}）。
     */
    public static StepPlan parse(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                throw new IllegalArgumentException("StepPlan 根必须是 JSON 对象");
            }
            int schemaVersion = requireInt(root, "schemaVersion");
            String planId = requireText(root, "planId");
            JsonNode stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray()) {
                throw new IllegalArgumentException("StepPlan.steps 必须是数组");
            }
            List<Command> steps = new ArrayList<>();
            List<ParamScope> stepScopes = new ArrayList<>();
            for (JsonNode step : stepsNode) {
                steps.add(parseCommand(step));
                stepScopes.add(ParamScope.fromJson(step.get("scope")));
            }
            ParamScope scope = ParamScope.fromJson(root.get("scope"));
            rejectUnknownFields(root, "schemaVersion", "planId", "steps", "scope");
            return new StepPlan(schemaVersion, planId, steps, scope, stepScopes);
        } catch (IOException e) {
            throw new IllegalArgumentException("StepPlan 解析失败: " + e.getMessage(), e);
        }
    }

    private static Command parseCommand(JsonNode step) {
        if (!step.isObject()) {
            throw new IllegalArgumentException("StepPlan 的每个 step 必须是 JSON 对象");
        }
        String actionText = requireText(step, "action");
        Command.ActionType action;
        try {
            action = Command.ActionType.valueOf(actionText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知 ActionType: " + actionText);
        }
        LocatorSpec target = null;
        if (step.has("target") && !step.get("target").isNull()) {
            target = LocatorSpec.parse(step.get("target").asText());
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (step.has("params") && !step.get("params").isNull()) {
            JsonNode p = step.get("params");
            if (!p.isObject()) {
                throw new IllegalArgumentException("step.params 必须是 JSON 对象");
            }
            Iterator<Map.Entry<String, JsonNode>> it = p.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                params.put(e.getKey(), e.getValue().asText());
            }
        }
        // —— fail-closed 结构约束（§4.3）——
        if (action == Command.ActionType.NAVIGATE) {
            if (target != null) {
                throw new IllegalArgumentException("NAVIGATE 不允许带 target（无 locator）");
            }
        } else if (target == null) {
            throw new IllegalArgumentException(action + " 必须带 target");
        }
        if (action == Command.ActionType.ASSERT_VISIBLE || action == Command.ActionType.ASSERT_TEXT) {
            if (!params.isEmpty()) {
                throw new IllegalArgumentException(action + " 不允许带写入型参数");
            }
        }
        // 按 ActionType 校验参数键（§4.3）：外部输入（模型建议/手写计划）最常臆造参数键，
        // 在此处拒绝比让执行器拿到未知键更早、更可诊断；schema 与意图侧共用 ActionParamSchema。
        ActionParamSchema.Spec spec = ActionParamSchema.specOf(action);
        for (String key : params.keySet()) {
            if (!spec.allowedParams().contains(key)) {
                throw new IllegalArgumentException(action + " 不允许参数: " + key);
            }
        }
        for (String key : spec.requiredParams()) {
            String value = params.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(action + " 缺少必填参数: " + key);
            }
        }
        rejectUnknownFields(step, "action", "target", "params", "scope");
        return new Command(action, target, params);
    }

    private static void rejectUnknownFields(JsonNode node, String... allowed) {
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            boolean ok = false;
            for (String a : allowed) {
                if (a.equals(k)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new IllegalArgumentException("StepPlan 含未知字段: " + k);
            }
        }
    }

    private static int requireInt(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isNumber()) {
            throw new IllegalArgumentException("StepPlan 缺少数字字段: " + key);
        }
        return v.asInt();
    }

    private static String requireText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull() || v.asText().isEmpty()) {
            throw new IllegalArgumentException("StepPlan 缺少文本字段: " + key);
        }
        return v.asText();
    }

    /**
     * 内容指纹（SHA-256 前 8 字节十六进制），风格对齐 {@code ApprovalGrant.planDigestOf}。
     * 覆盖 schemaVersion + 每步 action / target 规范串 / 排序后的参数 / 逐步与 plan 级 scope——
     * 任一变化都意味着「被批准的内容变了」。<b>不含 {@code planId}</b>：planId 是运行时标识
     * （且两闭环路径刻意以 {@code plan-} / {@code linkage-} 前缀区分做可观测性），不属于被批准内容，
     * 故排除——使同一资产经两条闭环路径派生出的同构计划产出<b>相同指纹</b>，可直接比对对账。
     */
    public String planDigest() {
        StringBuilder payload = new StringBuilder(DIGEST_PREFIX).append('\n');
        payload.append(schemaVersion).append('\n');
        for (int i = 0; i < steps.size(); i++) {
            Command c = steps.get(i);
            payload.append(c.action()).append('\n');
            payload.append(c.targetRef()).append('\n');
            c.params().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> payload.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
            String ssc = stepScopes.get(i).canonical();
            if (!ssc.isEmpty()) {
                payload.append("stepScope:").append(i).append('{').append(ssc).append('}').append('\n');
            }
        }
        String sc = scope.canonical();
        if (!sc.isEmpty()) {
            payload.append("scope:").append(sc).append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256，无法计算 StepPlan 指纹", e);
        }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 步骤计划的参数取值域：参数名 → {@link ParamConstraint}。
 *
 * <p>随 {@code StepPlan} 资产分发，并被 {@code StepPlan.planDigest()} 绑定——
 * scope 一旦变化，指纹即变，天然与「被批准的内容」对齐。</p>
 *
 * <p><b>fail-closed 解析</b>：{@link #fromJson(JsonNode)} 对每个约束对象要求恰好声明
 * {@code allow} 或 {@code regex} 其一，二者互斥；约束结构不合规即拒。</p>
 */
public final class ParamScope {

    /** 空 scope（无约束）。 */
    public static final ParamScope EMPTY = new ParamScope(Map.of());

    private final Map<String, ParamConstraint> constraints;

    public ParamScope(Map<String, ParamConstraint> constraints) {
        this.constraints = Map.copyOf(Objects.requireNonNull(constraints, "constraints"));
    }

    /** 从 StepPlan JSON 的 {@code scope} 节点解析（null / 缺失 = 空 scope）。 */
    public static ParamScope fromJson(JsonNode scopeNode) {
        if (scopeNode == null || scopeNode.isNull()) {
            return EMPTY;
        }
        if (!scopeNode.isObject()) {
            throw new IllegalArgumentException("StepPlan.scope 必须是 JSON 对象");
        }
        Map<String, ParamConstraint> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = scopeNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            map.put(e.getKey(), parseConstraint(e.getKey(), e.getValue()));
        }
        return new ParamScope(map);
    }

    private static ParamConstraint parseConstraint(String paramKey, JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("scope." + paramKey + " 必须是约束对象");
        }
        JsonNode allow = node.get("allow");
        JsonNode regex = node.get("regex");
        boolean hasAllow = allow != null && !allow.isNull();
        boolean hasRegex = regex != null && !regex.isNull();
        if (hasAllow && hasRegex) {
            throw new IllegalArgumentException("scope." + paramKey + " 不能同时声明 allow 与 regex");
        }
        if (hasAllow) {
            if (!allow.isArray()) {
                throw new IllegalArgumentException("scope." + paramKey + ".allow 必须是数组");
            }
            String[] values = new String[allow.size()];
            for (int i = 0; i < allow.size(); i++) {
                values[i] = allow.get(i).asText();
            }
            return ParamConstraint.allowedValues(values);
        }
        if (hasRegex) {
            return ParamConstraint.matchingRegex(regex.asText());
        }
        throw new IllegalArgumentException("scope." + paramKey + " 必须声明 allow 或 regex");
    }

    public boolean isEmpty() {
        return constraints.isEmpty();
    }

    public Optional<ParamConstraint> constraintFor(String paramKey) {
        return Optional.ofNullable(constraints.get(paramKey));
    }

    public Set<String> paramKeys() {
        return constraints.keySet();
    }

    /** 稳定规范串（供 planDigest 绑定；按参数名排序，消除 JSON 顺序漂移）。 */
    public String canonical() {
        if (constraints.isEmpty()) {
            return "";
        }
        TreeMap<String, ParamConstraint> sorted = new TreeMap<>(constraints);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ParamConstraint> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('{').append(e.getValue().canonical()).append('}');
        }
        return sb.toString();
    }
}

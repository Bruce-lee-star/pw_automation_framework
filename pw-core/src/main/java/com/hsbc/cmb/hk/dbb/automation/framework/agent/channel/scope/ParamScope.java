package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import com.fasterxml.jackson.databind.JsonNode;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.Command;

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
 * {@code allow} / {@code regex} / {@code range(min,max)} 其一，三者互斥；约束结构不合规即拒。</p>
 *
 * <p><b>per-step 覆盖</b>：{@link #composeWith(ParamScope)} 把 step 级 scope 叠加到 plan 级 scope——
 * 同名参数以 step 级约束为准（覆盖），未覆盖参数沿用 plan 级。StepPlan 经
 * {@code resolvedScope(stepIndex)} 产出「step 覆盖 plan」后的最终 scope。</p>
 */
public final class ParamScope {

    /** 空 scope（无约束）。 */
    public static final ParamScope EMPTY = new ParamScope(Map.of());

    /**
     * 由资产写入型动作的「解析后取值」派生<b>逐步</b>参数范围：
     * FILL → {@code {"value":[v]}}，SELECT → {@code {"option":[v]}}；
     * 其它动作（CLICK/CHECK/UNCHECK）或空值 → {@link #EMPTY}。
     *
     * <p>这是「资产自身取值 → 治理域」的唯一映射点，被 {@code LinkageGraphStepPlans}
     * （core 资产验证路径）与 {@code StepPlanProducer}（analysis 生产 pipeline 路径）两条闭环
     * 构建路径共用，确保对同一资产产出一致的范围结构——plan 级仅声明导航 {@code url}，
     * 副作用参数在 step 级按资产取值精确授权，使治理指纹可比对、且 fail-closed 粒度一致。</p>
     */
    public static ParamScope forAssetWriteValue(Command.ActionType action, String value) {
        if (value == null || value.isEmpty()) {
            return EMPTY;
        }
        return switch (action) {
            case FILL -> new ParamScope(Map.of("value", ParamConstraint.allowedValues(value)));
            case SELECT -> new ParamScope(Map.of("option", ParamConstraint.allowedValues(value)));
            default -> EMPTY;
        };
    }

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
        JsonNode min = node.get("min");
        JsonNode max = node.get("max");
        boolean hasAllow = allow != null && !allow.isNull();
        boolean hasRegex = regex != null && !regex.isNull();
        boolean hasRange = (min != null && !min.isNull()) || (max != null && !max.isNull());
        int forms = (hasAllow ? 1 : 0) + (hasRegex ? 1 : 0) + (hasRange ? 1 : 0);
        if (forms > 1) {
            throw new IllegalArgumentException("scope." + paramKey + " 的 allow/regex/range 互斥，不可同时声明");
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
        if (hasRange) {
            if (min == null || min.isNull() || max == null || max.isNull()) {
                throw new IllegalArgumentException("scope." + paramKey + ".range 必须同时声明 min 与 max");
            }
            if (!min.isNumber() || !max.isNumber()) {
                throw new IllegalArgumentException("scope." + paramKey + ".range 必须是数字");
            }
            return ParamConstraint.inRange(min.asLong(), max.asLong());
        }
        throw new IllegalArgumentException("scope." + paramKey + " 必须声明 allow / regex / range 之一");
    }

    /**
     * 把本 scope 叠加到 {@code base} 之上：同名参数以本 scope 的约束覆盖 {@code base}，
     * 未覆盖参数沿用 {@code base}。用于 step 级 scope 覆盖 plan 级 scope。
     */
    public ParamScope composeWith(ParamScope base) {
        if (base == null || base.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return base;
        }
        Map<String, ParamConstraint> merged = new LinkedHashMap<>(base.constraints);
        merged.putAll(this.constraints); // 本 scope 同名覆盖
        return new ParamScope(merged);
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

package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 单参数的取值域约束（参数范围门禁 v3.7 的原子单元）。
 *
 * <p>两种互斥的约束形态（设计 §4.3.1 的 {@code AllowedValueSet | Regex | Range}）：
 * ① 显式白名单 {@code allow}（精确匹配，空集 = 拒绝一切取值）；
 * ② 正则 {@code regex}（{@link Pattern#matches} 全串匹配）；
 * ③ 数值区间 {@code range}（{@code inRange(min,max)}，闭区间，含端点）。
 * {@code allow} 已覆盖「枚举 / 闭集」语义，故不再单列 Enum 类型。</p>
 *
 * <p><b>fail-closed</b>：{@link #allows(String)} 在约束未满足时返回 {@code false}；
 * 构造即校验，非法约束（非法正则 / 区间 min&gt;max / 多形态混用）在解析期即拒。</p>
 *
 * <p><b>可序列化</b>：正则以源字符串 {@code regex} 存储（{@link Pattern} 懒编译），
 * 故本类型在「FIELD 可见 / GETTER 不可见」的 Jackson 配置下可无损往返——这是
 * {@code STEP_PLAN} 资产经 {@code AssetPipelineOrchestrator} 落库后再被生产侧读回的前提。</p>
 */
public final class ParamConstraint {

    private final Set<String> allowedValues;   // 非 null 当且仅当用户选了 allow
    private final String regex;                // 非 null 当且仅当用户选了 regex（源串，懒编译）
    private final Long min;                    // 非 null 当且仅当用户选了 range
    private final Long max;

    private transient Pattern compiled;         // regex 的懒编译结果，不参与序列化

    /** 字段构造（亦为 Jackson 反序列化入口，与 {@link ParamScope#fromJson} 的 allow/regex/range 形状一致）。 */
    @JsonCreator
    private ParamConstraint(@JsonProperty("allowedValues") Set<String> allowedValues,
                            @JsonProperty("regex") String regex,
                            @JsonProperty("min") Long min,
                            @JsonProperty("max") Long max) {
        this.allowedValues = allowedValues;
        this.regex = regex;
        this.min = min;
        this.max = max;
    }

    /** 显式白名单（精确匹配）。传空数组 = 拒绝一切取值（最严形态）。 */
    public static ParamConstraint allowedValues(String... values) {
        Set<String> set = new LinkedHashSet<>();
        for (String v : values) {
            set.add(Objects.requireNonNull(v, "allow 元素不可为 null"));
        }
        return new ParamConstraint(set, null, null, null);
    }

    /** 正则全串匹配约束（{@code Pattern.compile} 解析，非法正则即拒）。 */
    public static ParamConstraint matchingRegex(String regex) {
        Objects.requireNonNull(regex, "regex");
        try {
            Pattern.compile(regex); // 提前校验，非法正则即拒
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("ParamConstraint 非法正则: " + regex, e);
        }
        return new ParamConstraint(null, regex, null, null);
    }

    /** 数值闭区间约束（含端点）。{@code min > max} 即拒（区间方向非法）。 */
    public static ParamConstraint inRange(long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("ParamConstraint 区间非法: min > max (" + min + " > " + max + ")");
        }
        return new ParamConstraint(null, null, min, max);
    }

    /** 该取值是否被约束允许（fail-closed：任何不满足都返回 {@code false}）。 */
    public boolean allows(String value) {
        if (value == null) {
            return false;
        }
        if (allowedValues != null) {
            return allowedValues.contains(value);
        }
        if (regex != null) {
            return compiled().matcher(value).matches();
        }
        // 数值区间：非数字即拒
        try {
            long v = Long.parseLong(value.trim());
            return v >= min && v <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Pattern compiled() {
        if (compiled == null) {
            compiled = Pattern.compile(regex);
        }
        return compiled;
    }

    /** 是否正则形态（用于 canonical / 诊断）。 */
    @JsonIgnore
    public boolean isRegex() {
        return regex != null;
    }

    /** 是否数值区间形态。 */
    @JsonIgnore
    public boolean isRange() {
        return min != null;
    }

    /** 稳定规范串（供 {@link ParamScope#canonical()} 组装）。 */
    public String canonical() {
        if (allowedValues != null) {
            return "allow=" + String.join(",", allowedValues);
        }
        if (regex != null) {
            return "regex=" + regex;
        }
        return "range=" + min + ".." + max;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ParamConstraint)) {
            return false;
        }
        ParamConstraint that = (ParamConstraint) o;
        if (allowedValues != null) {
            return that.allowedValues != null && allowedValues.equals(that.allowedValues);
        }
        if (regex != null) {
            return that.regex != null && regex.equals(that.regex);
        }
        return that.min != null && Objects.equals(min, that.min) && Objects.equals(max, that.max);
    }

    @Override
    public int hashCode() {
        if (allowedValues != null) {
            return allowedValues.hashCode();
        }
        if (regex != null) {
            return regex.hashCode();
        }
        return Objects.hash(min, max);
    }
}

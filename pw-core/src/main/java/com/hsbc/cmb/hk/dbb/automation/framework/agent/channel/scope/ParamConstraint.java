package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

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
 */
public final class ParamConstraint {

    private final Set<String> allowedValues;   // 非 null 当且仅当用户选了 allow
    private final Pattern pattern;             // 非 null 当且仅当用户选了 regex
    private final Long min;                    // 非 null 当且仅当用户选了 range
    private final Long max;

    private ParamConstraint(Set<String> allowedValues, Pattern pattern, Long min, Long max) {
        this.allowedValues = allowedValues;
        this.pattern = pattern;
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
            return new ParamConstraint(null, Pattern.compile(regex), null, null);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("ParamConstraint 非法正则: " + regex, e);
        }
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
        if (pattern != null) {
            return pattern.matcher(value).matches();
        }
        // 数值区间：非数字即拒
        try {
            long v = Long.parseLong(value.trim());
            return v >= min && v <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 是否正则形态（用于 canonical / 诊断）。 */
    public boolean isRegex() {
        return pattern != null;
    }

    /** 是否数值区间形态。 */
    public boolean isRange() {
        return min != null;
    }

    /** 稳定规范串（供 {@link ParamScope#canonical()} 组装）。 */
    public String canonical() {
        if (allowedValues != null) {
            return "allow=" + String.join(",", allowedValues);
        }
        if (pattern != null) {
            return "regex=" + pattern.pattern();
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
        if (pattern != null) {
            return that.pattern != null && pattern.pattern().equals(that.pattern.pattern());
        }
        return that.min != null && Objects.equals(min, that.min) && Objects.equals(max, that.max);
    }

    @Override
    public int hashCode() {
        if (allowedValues != null) {
            return allowedValues.hashCode();
        }
        if (pattern != null) {
            return pattern.pattern().hashCode();
        }
        return Objects.hash(min, max);
    }
}

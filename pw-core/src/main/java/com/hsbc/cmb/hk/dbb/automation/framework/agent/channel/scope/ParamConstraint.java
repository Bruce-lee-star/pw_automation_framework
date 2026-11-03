package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.scope;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 单参数的取值域约束（参数范围门禁 v3.7 的原子单元）。
 *
 * <p>两种互斥的约束形态：① 显式白名单 {@code allow}（精确匹配，空集 = 拒绝一切取值）；
 * ② 正则 {@code regex}（{@link Pattern#matches} 全串匹配）。二者不可同时声明。</p>
 *
 * <p><b>fail-closed</b>：{@link #allows(String)} 在约束未满足时返回 {@code false}；
 * 构造即校验，非法约束（非法正则 / 同时声明两种）在解析期即拒。</p>
 */
public final class ParamConstraint {

    private final Set<String> allowedValues;   // 非 null 当且仅当用户选了 allow
    private final Pattern pattern;             // 非 null 当且仅当用户选了 regex

    private ParamConstraint(Set<String> allowedValues, Pattern pattern) {
        this.allowedValues = allowedValues;
        this.pattern = pattern;
    }

    /** 显式白名单（精确匹配）。传空数组 = 拒绝一切取值（最严形态）。 */
    public static ParamConstraint allowedValues(String... values) {
        Set<String> set = new LinkedHashSet<>();
        for (String v : values) {
            set.add(Objects.requireNonNull(v, "allow 元素不可为 null"));
        }
        return new ParamConstraint(set, null);
    }

    /** 正则全串匹配约束（{@code Pattern.compile} 解析，非法正则即拒）。 */
    public static ParamConstraint matchingRegex(String regex) {
        Objects.requireNonNull(regex, "regex");
        try {
            return new ParamConstraint(null, Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("ParamConstraint 非法正则: " + regex, e);
        }
    }

    /** 该取值是否被约束允许（fail-closed：任何不满足都返回 {@code false}）。 */
    public boolean allows(String value) {
        if (value == null) {
            return false;
        }
        if (allowedValues != null) {
            return allowedValues.contains(value);
        }
        return pattern.matcher(value).matches();
    }

    /** 是否正则形态（用于 canonical / 诊断）。 */
    public boolean isRegex() {
        return pattern != null;
    }

    /** 稳定规范串（供 {@link ParamScope#canonical()} 组装）。 */
    public String canonical() {
        if (allowedValues != null) {
            return "allow=" + String.join(",", allowedValues);
        }
        return "regex=" + pattern.pattern();
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
            return pattern == null && allowedValues.equals(that.allowedValues) && that.pattern == null;
        }
        return pattern != null && that.allowedValues == null
                && that.pattern != null && pattern.pattern().equals(that.pattern.pattern());
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowedValues, pattern == null ? null : pattern.pattern());
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import java.util.Objects;

/**
 * 类型安全的 per-scenario 上下文键。
 *
 * <p>用于 {@link TestContext} 中按类型存取状态，避免 String 键的类型擦除问题。
 * 以 {@code name} 作为唯一标识（equals/hashCode 仅基于 name）。
 *
 * @param <T> 值的类型
 */
public final class ContextKey<T> {

    private final String name;
    private final Class<T> type;

    private ContextKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "context key name");
        this.type = Objects.requireNonNull(type, "context key type");
    }

    public static <T> ContextKey<T> of(String name, Class<T> type) {
        return new ContextKey<>(name, type);
    }

    /**
     * 创建键，其中 {@code rawType} 是<b>参数化类型</b>的 {@code Class} token（如 {@code List.class}）。
     *
     * <p>由于 Java 泛型擦除，无法用 {@code Class<List<String>>} 这样的 token：{@code List.class}
     * 的静态类型只能是 {@code Class<List>}（raw），要表达 {@code ContextKey<List<String>>}
     * 必然有一次 unchecked 转换。<b>调用方不应自行写
     * {@code (Class<List<String>>) (Class<?>) List.class}</b> 这种双重 cast（既丑又有告警），
     * 统一走本方法把 unsafe 事实收敛到框架内部一处。</p>
     *
     * <p>运行期类型校验仍由 {@link #cast(Object)} 基于 {@code rawType} 完成；
     * 参数化元素类型仅用于编译期静态校验，运行期无法区分。</p>
     *
     * @param name    键名（唯一标识）
     * @param rawType 擦除后的原始类型 token（如 {@code List.class} / {@code Map.class}）
     * @param <T>     目标值类型（由调用点推导，例如 {@code List<String>}）
     */
    @SuppressWarnings("unchecked")
    public static <T> ContextKey<T> ofRaw(String name, Class<?> rawType) {
        return new ContextKey<>(name, (Class<T>) rawType);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    /** 从存储 Map 取值时做类型安全的强制转换（package-private，供实现类使用）。 */
    @SuppressWarnings("unchecked")
    T cast(Object value) {
        return value == null ? null : type.cast(value);
    }

    // 修复 CORE-P1-N4：同名但不同类型的键必须区分，否则 TestContext 中
    // ContextKey.of("x", String.class) 与 ContextKey.of("x", Integer.class) 会互相覆盖。
    @Override
    public boolean equals(Object o) {
        return o instanceof ContextKey<?> k && name.equals(k.name) && type.equals(k.type);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + type.hashCode();
    }

    @Override
    public String toString() {
        return "ContextKey[" + name + ":" + type.getSimpleName() + "]";
    }
}

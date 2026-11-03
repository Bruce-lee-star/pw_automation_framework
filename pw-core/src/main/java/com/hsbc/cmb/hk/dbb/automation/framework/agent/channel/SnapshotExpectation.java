package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 单元素期望（验证驱动最小构成块，设计 §4.2 联动断言首步）。
 *
 * <p>描述「快照中某个元素（按 {@code ref} = {@link LocatorSpec#canonical()} 定位）应当满足的字段条件」。
 * 每个字段均可选——未设置的字段不参与校验。被 {@link SnapshotVerifier} 消费，做<b>绝对态</b>字段断言。
 *
 * <p>与 {@code LinkageGraphVerifier} 的「状态变化（APPEAR/DISAPPEAR/ENABLE/…）」语义互补：
 * 本类校验「某元素此刻是否等于期望的绝对态」，后者校验「触发后是否发生指定变化」；两者不重叠。</p>
 *
 * <p>不可变；用 {@link Builder} 构造。</p>
 */
public final class SnapshotExpectation {

    private final String ref;
    private final Boolean exists;     // 元素是否应存在（默认 true）
    private final String role;        // 期望角色（精确相等）
    private final String name;        // 期望可访问名（精确相等）
    private final Boolean visible;    // 期望可见性
    private final Boolean enabled;    // 期望启用性
    private final String text;        // 期望可见文本（精确相等）
    private final String textMatches; // 期望可见文本匹配正则（与 text 互斥）

    private SnapshotExpectation(Builder b) {
        this.ref = Objects.requireNonNull(b.ref, "ref");
        this.exists = (b.exists == null) ? Boolean.TRUE : b.exists;
        this.role = b.role;
        this.name = b.name;
        this.visible = b.visible;
        this.enabled = b.enabled;
        this.text = b.text;
        this.textMatches = b.textMatches;
        if (this.text != null && this.textMatches != null) {
            throw new IllegalStateException("text 与 textMatches 互斥，不可同时设置");
        }
    }

    public String ref() { return ref; }
    public Boolean exists() { return exists; }
    public String role() { return role; }
    public String name() { return name; }
    public Boolean visible() { return visible; }
    public Boolean enabled() { return enabled; }
    public String text() { return text; }
    public String textMatches() { return textMatches; }
    public boolean hasTextRegex() { return textMatches != null; }

    public static Builder forRef(String ref) { return new Builder(ref); }
    public static Builder forRef(LocatorSpec spec) {
        return new Builder(Objects.requireNonNull(spec, "spec").canonical());
    }

    /** 不可变构造器。 */
    public static final class Builder {
        private final String ref;
        private Boolean exists;
        private String role;
        private String name;
        private Boolean visible;
        private Boolean enabled;
        private String text;
        private String textMatches;

        private Builder(String ref) { this.ref = ref; }

        public Builder exists(boolean v) { this.exists = v; return this; }
        public Builder role(String v) { this.role = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder visible(boolean v) { this.visible = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder text(String v) { this.text = v; return this; }
        public Builder textMatches(String regex) {
            Pattern.compile(Objects.requireNonNull(regex, "textMatches")); // 预先校验正则合法性
            this.textMatches = regex;
            return this;
        }
        public SnapshotExpectation build() { return new SnapshotExpectation(this); }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.util.Locale;
import java.util.Objects;

/**
 * 变更元素的「角色 + 名称」引用（如 {@code role=textbox;name=Username} → {@link #role()} {@code textbox}）。
 *
 * <p><b>为什么要带 role</b>：只按名字匹配时，{@code username} 会同时命中
 * {@code @RoleElement(role = BUTTON, key = "forgot_username")} 与
 * {@code @RoleElement(role = HEADING, key = "title_username_page")}——名字相同但控件不同。
 * 带上录制骨架里已有的 role 后，代码层落点可精确到「同名同角色」的声明。</p>
 *
 * <p>不可变、线程安全；{@code role} 可为空（人工用 {@code --keyword} 或 {@link ChangeSignal#of} 补充信号时
 * 没有角色信息，此时扫描侧退化为「只按名字匹配」）。两个字段构造时 trim + 转小写。</p>
 *
 * @param role ARIA 角色（如 {@code textbox}/{@code button}），空串表示未知
 * @param name 元素可访问名/NLS 键（不可为空）
 */
public record ElementRef(String role, String name) {

    public ElementRef {
        role = normalize(role);
        name = normalize(name);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** 只有名字、没有角色的引用（人工补充信号时使用）。 */
    public static ElementRef ofName(String name) {
        Objects.requireNonNull(name, "name");
        return new ElementRef("", name);
    }

    /** 是否携带角色信息。 */
    public boolean hasRole() {
        return !role.isEmpty();
    }

    /** 报告用格式：有角色时 {@code role:name}，否则只给名字。 */
    public String describe() {
        return hasRole() ? role + ":" + name : name;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

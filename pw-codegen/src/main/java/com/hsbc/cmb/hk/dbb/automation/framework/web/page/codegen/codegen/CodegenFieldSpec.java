package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;

/**
 * 一个生成字段的规划结果。
 *
 * <p><b>为何 Page / Step / Assert 必须共用这份结果</b>：三个 Tab 的产物都引用同一个页面元素，
 * 若各自命名字段，Tab1 的 {@code signIn} 与 Tab2 的 {@code signInBtn} 就会对不上，
 * 产物无法直接拼成可运行代码（V2 用共享的 {@code assignFields} 保证同一性，这里同理）。
 */
public final class CodegenFieldSpec {

    /** 字段名（三个 Tab 共用同一份）。 */
    public final String fieldName;
    /** 字段类型：{@code PageElement} 或一组元素的 {@code PageElementList}。 */
    public final String fieldType;
    /** 定位注解：{@code @RoleElement(...)} 或回退的 {@code @Element(...)}。 */
    public final String annotation;
    /** 产物内的提示注释（如 CJK 无英文源），可为 null。 */
    public final String comment;
    /** Step 用：动作动词（{@code click} / {@code fill} / {@code check}），用于拼方法名。 */
    public final String verb;
    /** Step 用：对该元素最自然的动作表达式（不含分号），如 {@code click()} / {@code fill("")}。 */
    public final String action;
    /** Assert 用：断言表达式（不含分号），如 {@code isVisible()} / {@code get(0).isChecked()}。 */
    public final String assertion;
    /** 是否一组元素（{@code PageElementList}）。 */
    public final boolean isList;
    /** 是否由 role 定位（false 表示已回退 CSS/XPath）。 */
    public final boolean roleLocatable;
    public final CodegenEntry entry;
    /**
     * Step / Assert 取单项时的组内序号（0 基；非一组恒 {@code 0}）。
     *
     * <p><b>两种成组来源的序号语义不同</b>：多次拾取 / 面板标记的组，其 {@code index} 只是组内成员序号，
     * 产物沿用首项 {@code get(0)}；而「目标本身是 ARIA 集合里的一项」的自动成组（
     * {@link CodegenEntry#autoList}），{@code index} 是<b>用户实际点的那一项</b>——必须原样带出，
     * 否则回放会点错选项（密保问题下拉点第 5 项却回放成第 1 项）。
     */
    public final int listIndex;
    /**
     * Assert 用：录制到的<b>最终输入值</b>；{@code null} 表示该元素在录制期间没有任何输入。
     *
     * <p>输入类元素的断言期望值必须来自这里，<b>不能</b>用 {@link CodegenEntry#name}
     * （那是 AX 可访问名：label / placeholder / aria-label，不是用户输入的内容）。
     */
    public final String recordedValue;

    /** 兼容构造：一组元素取首项 {@code get(0)}（历史行为）。 */
    public CodegenFieldSpec(String fieldName, String fieldType, String annotation, String comment,
                            String verb, String action, String assertion, boolean isList,
                            boolean roleLocatable, CodegenEntry entry, String recordedValue) {
        this(fieldName, fieldType, annotation, comment, verb, action, assertion, isList,
                roleLocatable, entry, recordedValue, 0);
    }

    /**
     * @param listIndex 一组元素时取单项的组内序号（0 基，见 {@link #listIndex}）；非一组忽略
     */
    public CodegenFieldSpec(String fieldName, String fieldType, String annotation, String comment,
                            String verb, String action, String assertion, boolean isList,
                            boolean roleLocatable, CodegenEntry entry, String recordedValue, int listIndex) {
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.annotation = annotation;
        this.comment = comment;
        this.verb = verb;
        this.action = action;
        this.assertion = assertion;
        this.isList = isList;
        this.roleLocatable = roleLocatable;
        this.entry = entry;
        this.recordedValue = recordedValue;
        this.listIndex = isList ? Math.max(0, listIndex) : 0;
    }

    /**
     * 取单项前缀：一组元素返回 {@code get(n).}（n 见 {@link #listIndex}），单元素返回空串。
     *
     * <p>Step 动作、Assert 断言、以及「按本次操作重算动作」的调用方都必须走这里，
     * 避免各处各写一份 {@code "get(0)."} 把自动成组的序号丢掉。
     */
    public String listTarget() {
        return isList ? "get(" + listIndex + ")." : "";
    }
}

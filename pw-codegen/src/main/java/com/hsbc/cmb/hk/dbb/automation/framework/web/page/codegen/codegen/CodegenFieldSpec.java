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

    public CodegenFieldSpec(String fieldName, String fieldType, String annotation, String comment,
                            String verb, String action, String assertion, boolean isList,
                            boolean roleLocatable, CodegenEntry entry) {
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
    }
}

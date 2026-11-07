package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import java.util.Locale;
import java.util.Objects;

/**
 * 选择器里的一个「定位锚点」：{@code tag} / {@code id} / {@code class} / {@code attr} / {@code text}
 * 加比较算子与取值（如 {@code #userName} → {@code id = userName}；
 * {@code [data-i18n='button_next']} → {@code attr data-i18n = button_next}）。
 *
 * <p>锚点是「同一元素」的判定单位：两个选择器只要有一个锚点一致即视为指向同一处。
 * 只有<b>稳定</b>的锚点会被产出（随机值在 {@link SelectorAnalyzer} 里被剔除并转成建议）。</p>
 *
 * @param kind     锚点类型：{@code tag}/{@code id}/{@code class}/{@code attr}/{@code text}
 * @param name     属性名（{@code attr} 用；{@code tag}/{@code id}/{@code class}/{@code text} 为空）
 * @param operator 比较算子：{@code =}（精确）、{@code ^=}（开头）、{@code $=}（结尾）、
 *                 {@code *=}（包含）；XPath 的 {@code contains()}/{@code starts-with()} 归一为同一套
 * @param value    取值（已 trim；带随机值的取值不会成为锚点）
 */
public record SelectorAnchor(String kind, String name, String operator, String value) {

    public static final String OP_EQ = "=";
    public static final String OP_PREFIX = "^=";
    public static final String OP_SUFFIX = "$=";
    public static final String OP_CONTAINS = "*=";

    public SelectorAnchor {
        Objects.requireNonNull(kind, "kind");
        name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        operator = operator == null || operator.isBlank() ? OP_EQ : operator.trim();
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("anchor value must not be blank");
        }
    }

    public static SelectorAnchor id(String id) {
        return new SelectorAnchor("id", "", OP_EQ, id);
    }

    public static SelectorAnchor cssClass(String cssClass) {
        return new SelectorAnchor("class", "", OP_EQ, cssClass);
    }

    public static SelectorAnchor attr(String name, String operator, String value) {
        return new SelectorAnchor("attr", name, operator, value);
    }

    public static SelectorAnchor text(String operator, String value) {
        return new SelectorAnchor("text", "", operator, value);
    }

    /** 同一槽位（类型 + 属性名）才可比。 */
    public boolean sameSlot(SelectorAnchor other) {
        return other != null && kind.equals(other.kind) && name.equals(other.name);
    }

    /** 报告用：{@code attr[data-i18n="button_next"]} / {@code id="userName"}。 */
    public String describe() {
        if ("attr".equals(kind)) {
            return "attr[" + name + operator + "\"" + value + "\"]";
        }
        return kind + operator + "\"" + value + "\"";
    }

    @Override
    public String toString() {
        return describe();
    }
}

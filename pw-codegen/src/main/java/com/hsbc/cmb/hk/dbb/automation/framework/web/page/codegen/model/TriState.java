package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model;

/**
 * 三态布尔：{@link #TRUE} / {@link #FALSE} / {@link #UNKNOWN}。
 *
 * <h2>为什么不用 {@code Boolean}</h2>
 * <p>codegen 里多处判断天然是三态——「元素此刻是否还在拾取时那一步」这件事，在下列情况下<b>没有答案</b>：
 * 没有 extractor（子 frame 元素不参与复核）、CDP 计数失败、页面正在导航、注入脚本未就绪。
 * 用 {@code Boolean} 表达会把"不知道"和"false"混为一谈（调用方一旦写 {@code Boolean.TRUE.equals(x)}
 * 就悄悄把 UNKNOWN 当成 FALSE），而用裸 {@code boolean} 又无法表达"不知道"。
 *
 * <p>另外，{@code Boolean} 返回类型显式 {@code null} 会触发 SpotBugs
 * {@code NP_BOOLEAN_RETURN_NULL}（High）——该模式在静态检查里被视为缺陷而非设计。
 * 用枚举把三态写进类型，调用方必须显式处理 {@link #UNKNOWN}，编译器就是护栏。
 *
 * <p><b>语义边界</b>：{@link #UNKNOWN} 必须由调用方按"保守"方向处理（保留现有判断，而不是当成
 * {@link #FALSE}）。典型用例见 {@code RoleVerificationPolicy#fallBackToCssAfterMismatch}：
 * UNKNOWN 时保留 role 并标 TODO，绝不把可靠定位器退化成结构 CSS。
 */
public enum TriState {

    /** 明确成立。 */
    TRUE,

    /** 明确不成立。 */
    FALSE,

    /** 无法判定（无证据 / 探测失败 / 环境不可用）。调用方不得当成 {@link #FALSE}。 */
    UNKNOWN;

    /** 是否明确成立（{@link #UNKNOWN} 不算）。 */
    public boolean isTrue() {
        return this == TRUE;
    }

    /** 是否明确不成立（{@link #UNKNOWN} 不算）。 */
    public boolean isFalse() {
        return this == FALSE;
    }

    /** 是否无法判定。 */
    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    /**
     * {@code Boolean} → 三态适配（{@code null} ⇒ {@link #UNKNOWN}）。
     *
     * <p>用于和既有返回 {@code Boolean} 的 API（如 Playwright 的 {@code Locator#isVisible()}）对接，
     * 避免在调用点重复写三元判断。
     */
    public static TriState of(Boolean value) {
        if (value == null) return UNKNOWN;
        return value ? TRUE : FALSE;
    }
}

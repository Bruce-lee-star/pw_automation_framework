package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

/**
 * 一次<b>结构性观测</b>的结果（浏览器侧事实，不含判断）。
 *
 * <p>之所以把"观测"与"判断"分开，是因为两者出错的方式完全不同：
 * 观测出错是"没看清"（页面没加载、选择器写错、探针炸了），判断出错是"看错了"（把 2 个当成 1 个）。
 * 混在一起时，任何一条失败都只剩一句"验证不通过"，排查时无从下手。</p>
 *
 * <p><b>fail-closed</b>：{@link #unobserved} 用 {@code matches = -1} 明确表达"没观测到"，
 * 与"观测到 0 个"区分开。前者是探针没跑起来，后者是页面真的没有——两者都不通过，但原因不同，
 * 处理方式也不同（重跑 vs 改定位器）。</p>
 *
 * @param matches      命中元素数；{@code -1} 表示未观测
 * @param visible      是否可见（仅 {@code matches == 1} 时有意义）
 * @param enabled      是否可操作（未 disabled / 未被遮罩）
 * @param expectedName 定位符声明的期望可访问名（{@code nameKey} 已按当前语言解析；模板原样保留）
 * @param actualName   页面上实际读到的可访问名
 * @param detail       观测细节（tag、data-i18n、失败原因等），供审计与排查
 */
public record StructuralObservation(int matches, boolean visible, boolean enabled,
                                    String expectedName, String actualName, String detail) {

    /** 探针未能完成观测（页面未就绪 / 定位符非法 / 浏览器异常）。 */
    public static StructuralObservation unobserved(String detail) {
        return new StructuralObservation(-1, false, false, null, null, detail);
    }

    public boolean observed() {
        return matches >= 0;
    }
}

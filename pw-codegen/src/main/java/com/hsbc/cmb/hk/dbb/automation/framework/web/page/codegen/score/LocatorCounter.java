package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.score;

import java.util.Collection;
import java.util.List;

/**
 * 定位器计数入口（依赖注入，使 {@link CodegenScorer} 可在无浏览器环境下纯单测）。
 *
 * <p>真实实现走 Playwright：CSS 用 {@code page.locator(css).count()}，角色用
 * {@code page.getByRole(role, {name, exact:true}).count()}（与框架 {@code @RoleElement} 运行期语义一致）；
 * 单测用确定性桩替代。
 *
 * <p><b>作用域与批量</b>：{@link #forFrame} 让实现方能按「元素所属 frame + shadow 宿主链」计数（页级
 * {@code page.locator} 只看主 frame，对 iframe 内元素恒 0 命中，会把未校验当成唯一）；{@link #prefetch}
 * 让实现方把一批候选选择器一次性下发（一次协议往返），进而让后续 {@link #count} 命中缓存。
 * 两者都有默认实现，桩不受影响。
 */
@FunctionalInterface
public interface LocatorCounter {

    /**
     * 返回给定 CSS 选择器在当前页面匹配的元素个数。
     *
     * @param cssSelector 待校验的 CSS 定位器
     * @return 匹配个数（0 表示不存在 / 1 表示唯一 / &gt;1 表示需父级嵌套降级）
     */
    int count(String cssSelector);

    /**
     * 返回「角色 + 可访问名」在当前页面匹配的元素个数。
     *
     * <p><b>这是与 Playwright 引擎对齐的最终校验</b>：候选 role/name 可能来自浏览器 AX 树，
     * 而 {@code getByRole} 用的是 Playwright 自己的 ARIA 实现（{@code roleUtils}），两者在边缘元素上
     * （如 {@code <select>} 的 popupButton、只在显式 {@code role="generic"} 时才成立的 generic）存在差异。
     * 用真实引擎计数复核，才能保证产出「能编译且跑得通」的 {@code @RoleElement}。
     *
     * <p>默认返回 1（视为唯一可用）：纯 CSS 桩实现无需改动，也让"校验不可用"不致阻断拾取。
     *
     * @param role 角色（任意大小写/连字符形式，实现方需自行归一化到 {@code AriaRole}）
     * @param name 可访问名（与生成代码一致，按 {@code exact=true} 匹配）
     */
    default int countByRole(String role, String name) {
        return 1;
    }

    /**
     * 返回<b>绑定了 frame / shadow 作用域</b>的计数视图。
     *
     * <p>作用域必须与元素实际所在的文档一致：页级定位器对 iframe 内元素恒 0 命中，若直接沿用
     * {@code count=0 → 视为唯一} 的历史语义，等于「从未校验」。实现方返回的视图应当是一个轻量对象
     * （每次拾取一个新实例，内部缓存不跨拾取复用，避免页面变化后读到陈旧计数）。
     *
     * <p>默认返回 {@code this}（不解作用域），桩与历史实现行为不变。
     *
     * @param frameId     元素所属 frameId；{@code null} 表示主 frame
     * @param shadowHosts 元素所在的 open shadow 宿主链（outermost-first），主文档元素为空列表
     */
    default LocatorCounter forFrame(String frameId, List<String> shadowHosts) {
        return this;
    }

    /**
     * 预热一批候选选择器的计数（实现方可合并为一次协议往返；桩无需实现）。
     *
     * <p>仅影响性能，<b>不得</b>影响 {@link #count} 的返回值语义：未预热的选择器仍须按需计数。
     */
    default void prefetch(Collection<String> selectors) {
        // 默认不预热：逐次 count 的语义不受影响
    }
}

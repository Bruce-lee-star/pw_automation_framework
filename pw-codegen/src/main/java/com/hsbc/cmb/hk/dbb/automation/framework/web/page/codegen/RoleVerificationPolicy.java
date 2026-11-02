package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

/**
 * role 复核的判定规则（纯函数，无 IO）：把「Playwright 严格模式 + 元素可能是一组」的语义收敛到一处。
 *
 * <h2>为什么不能一律要求"恰好 1 个命中"</h2>
 * <p>Playwright 的严格模式有两种合法形态：
 * <ul>
 *   <li><b>单个元素</b>：定位器必须恰好命中 1 个（{@code page.getByRole(...).click()} 命中多个即 strict violation）；</li>
 *   <li><b>一组元素</b>：定位器<b>命中多个才是正确的</b>——它会被生成成 {@code PageElementList}，运行期按
 *       {@code locator().nth(i)} / {@code all()} 取单项。此时要求 {@code hits == 1} 会把本可用的 role 定位器
 *       误杀成结构 CSS，并让组退化成单元素。</li>
 * </ul>
 *
 * <h2>为什么要求"命中数 == 组内拾取数"（而不是 {@code >= 2}）</h2>
 * <p>一组元素的索引语义是"用户拾取的第 i 个"。若定位器命中数与拾取数不一致，说明组内被混入了别的元素：
 * <ul>
 *   <li><b>少于</b>：组内有元素不被该 role/name 命中（典型：不可见元素被 {@code getByRole} 的
 *       {@code includeHidden=false} 排除、或该元素根本不是这个 role）⇒ 后续 {@code get(i)} 会错位；</li>
 *   <li><b>多于</b>：页面上还有用户没拾取的同类元素 ⇒ {@code get(i)} 取到的不是用户看到的那个。</li>
 * </ul>
 * 两种都属"组被污染"，索引不可信，必须整体回退（不能只回退其中一条，否则同组内策略不一致，
 * 字段规划只取首条注解时会生成自相矛盾的产物）。
 *
 * <p>不可判定（{@code hits < 0}，CDP/页面异常）一律放行：环境抖动不该把可靠产物退化成脆弱 CSS。
 */
final class RoleVerificationPolicy {

    private RoleVerificationPolicy() {
    }

    /**
     * 是否保留 role 策略。
     *
     * @param hits     引擎命中数；{@code -1} 表示不可判定
     * @param expected 期望命中数：单元素为 {@code 1}，一组元素为组内拾取到的元素数
     * @return {@code true} 表示定位器与"单个/一组"的语义一致
     */
    static boolean keepRole(int hits, int expected) {
        if (hits < 0) return true;
        return hits == Math.max(expected, 1);
    }
}

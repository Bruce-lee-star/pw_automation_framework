package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.TriState;

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

    /**
     * 复核<b>不通过</b>时，该不该把定位器改写成 CSS。
     *
     * <p><b>现场（2026-11-15）</b>：HSBC 重置密码是同一个 document 内的多步向导。用户点 Continue 走到下一步后，
     * 拾取时的那个 Continue 被 {@code display:none} 隐藏；等到生成时复核 {@code getByRole(BUTTON, "Continue")}
     * 命中 0，于是一条<b>本来完全可用</b>的 role 定位器被改写成
     * {@code div:nth-of-type(5) div:has(hr[aria-hidden='true']) > div:nth-of-type(3) > button[class*='float-right']}。
     * 也就是说：后置复核把"页面已经翻页"误判成"定位器不可用"。
     *
     * <p>故命中 0 时还要看<b>拾取时的元素此刻是否仍在且可见</b>：
     * <ul>
     *   <li>状态未变（仍可见）⇒ 0 命中属实（名字口径不符 / ARIA 隐藏）⇒ 回退 CSS；</li>
     *   <li>状态已变（元素消失或被隐藏）／不可判定 ⇒ 现在的 DOM 不是拾取时的 DOM，计数说明不了运行期
     *       （运行期回到那一步时元素可见且唯一）⇒ <b>保留 role</b> 并标记未校验（产物带 TODO(review)）；</li>
     *   <li>命中 {@code >1} ⇒ 是真正的歧义（同一状态下确实多个匹配，运行期 strict violation），必须回退 CSS。</li>
     * </ul>
     *
     * @param hits            引擎命中数
     * @param pickStateIntact 拾取时的元素此刻是否仍在且可见；{@link TriState#UNKNOWN} = 不可判定
     * @return {@code true}=回退 CSS；{@code false}=保留 role（按未校验处理）
     */
    static boolean fallBackToCssAfterMismatch(int hits, TriState pickStateIntact) {
        if (hits > 0) return true;             // 真的多命中：歧义成立
        return pickStateIntact.isTrue();       // 0 命中：只有确认"状态未变"才回退
    }
}

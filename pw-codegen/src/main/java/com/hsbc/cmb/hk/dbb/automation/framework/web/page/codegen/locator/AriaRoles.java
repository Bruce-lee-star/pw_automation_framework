package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.channel.LocatorRoleVocabulary;

/**
 * ARIA 角色判定的 <b>codegen 侧适配入口</b>：{@code role} 字符串 ⇄ Playwright {@code AriaRole} 常量名。
 *
 * <p><b>T11 起本类不再持有词表</b>：唯一事实来源已下沉到 pw-core 的
 * {@link LocatorRoleVocabulary}（纯字符串、零 Playwright 依赖），因为执行侧
 * {@code LocatorResolver} 在 pw-web-ui、不能反向依赖 codegen，而两侧必须对同一个
 * {@code role=button;name=…} 得到同一个 {@code AriaRole}。历史上本类用反射维护一份
 * {@code SUPPORTED} + 别名表，执行侧却用裸 {@code AriaRole.valueOf(toUpperCase)}：
 * {@code image} / {@code menuitem-checkbox} 在生成侧映射成功、执行侧直接抛异常，
 * 属于「同一元素两侧不一致」的静默漂移。现两侧共享同一规范，由
 * {@code LocatorSpecGetByRoleContractTest} 做可证伪的跨模块对账。</p>
 *
 * <p><b>为什么必须集中判定</b>：生成 {@code @RoleElement(role = AriaRole.X)} 的前提是 X 必须是
 * Playwright 里真实存在的常量，否则产物编译不过；而 role 值来源有两处——CDP Accessibility 域的
 * 权威 AX role（如 {@code image}、{@code textbox}）与页面侧兜底推断（如 {@code label}、
 * {@code generic}）。历史上"未知 role 兜底成 {@code GENERIC}"会生成
 * {@code @RoleElement(role = AriaRole.GENERIC, key = "password")}：能编译，但运行期按
 * {@code getByRole(GENERIC, name)} <b>定位不到该元素</b>——比回退 {@code @Element(css)} 更糟
 * （静默失效）。</p>
 *
 * <h3>与 Playwright 引擎的对齐依据（1.62.1 源码核对，冻结版本见 core 规范）</h3>
 * <ul>
 *   <li>引擎侧白名单是 {@code packages/injected/src/roleUtils.ts} 的 {@code validRoles}（83 项），
 *       Java 客户端 1.62.0 为 82 项：两者仅差 {@code mark}（1.62.1 新增，Java 客户端尚无该常量）；
 *       升级客户端时契约测试会先变红，须同步更新 core 冻结词表与基线文件；</li>
 *   <li>引擎匹配是 {@code getAriaRole(element) !== options.role}，而 {@code getAriaRole} 对没有
 *       隐式角色的标签（{@code <div>}、{@code <span>}、{@code <label>}）返回 {@code null}——
 *       即 {@code getByRole('generic')} 对普通 div 永远不命中，这正是必须排除 generic 的原因；</li>
 *   <li>{@code <label>} 无隐式角色 ⇒ 只能归一到其关联控件后按控件角色定位；</li>
 *   <li>{@code input[type=password]} 在 {@code kImplicitRoleByTagName.INPUT} 里落到
 *       {@code 'textbox'} 兜底分支，故密码框按 textbox 定位与引擎一致。</li>
 * </ul>
 *
 * <p>因此本类只回答两个问题（答案来自 {@link LocatorRoleVocabulary}）：
 * <ul>
 *   <li>{@link #ariaConstant(String)}：能否映射到确切的常量（不能就返回 {@code null}）；</li>
 *   <li>{@link #isLocatable(String)}：该角色是否<b>值得</b>用角色定位。</li>
 * </ul>
 * 任何"不能"的结论都让下游回退 CSS 定位器，绝不静默降级成另一种角色。</p>
 */
public final class AriaRoles {

    private AriaRoles() {
    }

    /** 规范化（大写 + 去分隔符 + 别名展开），委托 core 唯一规范；同包测试可调用。 */
    static String normalize(String raw) {
        return LocatorRoleVocabulary.normalize(raw);
    }

    /**
     * 映射到确切的 Playwright 常量名（如 {@code "textbox"} → {@code "TEXTBOX"}）。
     *
     * @return {@code null} 表示没有对应常量——调用方<b>必须</b>回退 CSS 定位，不得臆造角色
     */
    public static String ariaConstant(String raw) {
        return LocatorRoleVocabulary.ariaConstant(raw);
    }

    /**
     * 该角色是否值得用角色定位：可映射到常量，且不是 generic/none/presentation。
     *
     * <p>注意：本方法<b>不</b>校验可访问名；调用方仍需要求 name 非空。</p>
     */
    public static boolean isLocatable(String raw) {
        return LocatorRoleVocabulary.isLocatable(raw);
    }
}

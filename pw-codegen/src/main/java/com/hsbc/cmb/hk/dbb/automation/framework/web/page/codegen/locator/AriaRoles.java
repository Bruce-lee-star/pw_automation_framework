package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator;

import com.microsoft.playwright.options.AriaRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ARIA 角色判定：{@code role} 字符串 ⇄ Playwright {@link AriaRole} 常量名。
 *
 * <p><b>为什么必须集中判定</b>：生成 {@code @RoleElement(role = AriaRole.X)} 的前提是 X 必须是
 * Playwright {@link AriaRole} 里真实存在的常量，否则产物编译不过；而 role 值来源有两处——
 * CDP Accessibility 域的权威 AX role（如 {@code image}、{@code textbox}）与页面侧兜底推断
 * （如 {@code label}、{@code generic}）。历史上"未知 role 兜底成 {@code GENERIC}"会生成
 * {@code @RoleElement(role = AriaRole.GENERIC, key = "password")}：能编译，但运行期按
 * {@code getByRole(GENERIC, name)} <b>定位不到该元素</b>——比回退 {@code @Element(css)} 更糟（静默失效）。
 *
 * <h3>与 Playwright 引擎的对齐依据（1.62.1 源码核对）</h3>
 * <ul>
 *   <li>引擎侧白名单是 {@code packages/injected/src/roleUtils.ts} 的 {@code validRoles}（83 项），
 *       本类用 Java 客户端 {@link AriaRole#values()}（1.62.0 为 82 项）作为白名单：两者仅差
 *       {@code mark}（1.62.1 新增，Java 客户端尚无该常量）。差异只会让 {@code mark} 回退 CSS，
 *       升级客户端后自动生效，<b>不会</b>产出编译不过的 {@code AriaRole.MARK}；</li>
 *   <li>引擎匹配是 {@code getAriaRole(element) !== options.role}（{@code roleSelectorEngine.ts}），
 *       而 {@code getAriaRole} 对没有隐式角色的标签（{@code <div>}、{@code <span>}、{@code <label>}）
 *       返回 {@code null} —— 即 <b>{@code getByRole('generic')} 对普通 div 永远不命中</b>
 *       （只有显式 {@code role="generic"} 才成立），这正是必须排除 generic 的原因；</li>
 *   <li>{@code <label>} 无隐式角色 ⇒ 只能归一到其关联控件（见 {@code pickTarget}）后按控件角色定位；</li>
 *   <li>{@code input[type=password]} 在 {@code kImplicitRoleByTagName.INPUT} 里落到 {@code 'textbox'}
 *       兜底分支，故密码框按 textbox 定位与引擎一致。</li>
 * </ul>
 *
 * <p>因此本类只回答两个问题：
 * <ul>
 *   <li>{@link #ariaConstant(String)}：能否映射到确切的 {@link AriaRole} 常量（不能就返回 {@code null}）；</li>
 *   <li>{@link #isLocatable(String)}：该角色是否<b>值得</b>用角色定位（能映射且不是 generic/none/presentation
 *       这类无语义角色）。</li>
 * </ul>
 * 任何"不能"的结论都让下游回退 CSS 定位器，绝不静默降级成另一种角色。
 */
public final class AriaRoles {

    private static final Logger log = LoggerFactory.getLogger(AriaRoles.class);

    /** Playwright {@link AriaRole} 的全部常量名（大写）；反射失败时为空集合（此时一律视为"不能映射"）。 */
    private static final Set<String> SUPPORTED;

    static {
        Set<String> names = new HashSet<>();
        try {
            for (AriaRole r : AriaRole.values()) names.add(r.name());
        } catch (Exception | LinkageError e) {
            // playwright 不在 classpath（极端裁剪场景）：保持空集合 ⇒ 全部回退 CSS 定位，不生成错误注解。
            log.debug("[codegen] AriaRole unavailable: {}", e.toString());
        }
        SUPPORTED = Collections.unmodifiableSet(names);
    }

    /**
     * 无语义 / 非控件角色：能映射到常量，但按 role 定位没有业务意义（而且极易误命中或恒不命中）。
     *
     * <p>{@code generic} 正是本次问题的核心：页面里 div/span/label 全被推断成 generic，
     * 若仍生成 {@code @RoleElement(role = AriaRole.GENERIC)}，运行期几乎必然定位不到。
     */
    private static final Set<String> NOT_LOCATABLE = Set.of("GENERIC", "NONE", "PRESENTATION");

    /** AX role → {@link AriaRole} 常量名的别名（大小写/连字符已在 {@link #normalize} 统一）。 */
    private static final Map<String, String> ALIASES = Map.of(
            // Chrome AX 对 <img> 报 "image"，Playwright 常量名是 IMG
            "IMAGE", "IMG",
            // AX 里的容器/文本节点：语义上等价于 generic，交由 NOT_LOCATABLE 判定
            "GENERICCONTAINER", "GENERIC",
            "STATICTEXT", "GENERIC",
            "INLINETEXTBOX", "GENERIC",
            "LABELTEXT", "GENERIC",
            "LINEBREAK", "GENERIC",
            "LISTMARKER", "GENERIC");

    private AriaRoles() {
    }

    /**
     * 规范化：大写 + 去掉分隔符（ARIA role 的 {@code menuitemcheckbox} / {@code menu-item-checkbox} 与
     * {@code AriaRole.MENUITEMCHECKBOX} 都是同一个角色）+ 别名展开。
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String up = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return ALIASES.getOrDefault(up, up);
    }

    /**
     * 映射到确切的 {@link AriaRole} 常量名（如 {@code "textbox"} → {@code "TEXTBOX"}）。
     *
     * @return {@code null} 表示 Playwright 没有对应常量——调用方<b>必须</b>回退 CSS 定位，不得臆造角色
     */
    public static String ariaConstant(String raw) {
        String key = normalize(raw);
        if (key.isEmpty() || !SUPPORTED.contains(key)) return null;
        return key;
    }

    /**
     * 该角色是否值得用角色定位：可映射到 {@link AriaRole}，且不是 generic/none/presentation。
     *
     * <p>注意：本方法<b>不</b>校验可访问名；调用方仍需要求 name 非空（name 为空时按 role 定位会命中多个元素）。
     */
    public static boolean isLocatable(String raw) {
        String key = ariaConstant(raw);
        return key != null && !NOT_LOCATABLE.contains(key);
    }
}

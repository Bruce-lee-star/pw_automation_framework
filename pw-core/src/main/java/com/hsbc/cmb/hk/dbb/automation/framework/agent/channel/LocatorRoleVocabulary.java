package com.hsbc.cmb.hk.dbb.automation.framework.agent.channel;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link LocatorSpec} 的 ROLE 表示 ↔ Playwright {@code AriaRole} 常量名之间的<b>唯一字符串规范</b>
 * （整改 T11 / 设计 §4.4）。
 *
 * <p><b>为什么放在 pw-core</b>：定位表示是跨模块资产（候选库、录制骨架、StepPlan、ARIA 快照索引键
 * 都承载 {@link LocatorSpec#canonical()}），而 pw-core 按 R6 依赖方向<b>不得</b>依赖 Playwright。
 * 因此这里只冻结「常量名字符串」，不出现任何 {@code com.microsoft.playwright} 类型；
 * 生成侧（pw-codegen）与执行侧（pw-web-ui）各自把字符串交给本版本的 {@code AriaRole.valueOf}。
 * 词表是否仍与真实枚举一致，由 pw-codegen 的跨模块契约测试逐项对账（Playwright 升级增删常量即构建红）。</p>
 *
 * <h3>唯一口径（三条生产路径必须全部经此）</h3>
 * <ol>
 *   <li><b>写入侧</b>：codegen 录制骨架 / 候选库把 canonical 的 {@code role=} 段写为
 *       {@link #canonicalRole(String)} 的结果（规范常量名的小写，如 {@code img}、
 *       {@code menuitemcheckbox}），不写 CDP AX 原始词（{@code image}、{@code menu-item-checkbox}）。</li>
 *   <li><b>生成侧</b>：{@code @RoleElement(role = AriaRole.X, name/key = ...)} 的 X 取
 *       {@link #ariaConstant(String)}；为 {@code null} 或 {@link #isLocatable(String)} 为假时
 *       回退 {@code @Element(css)}，<b>绝不</b>降级成 {@code AriaRole.GENERIC}（能编译但恒不命中）。</li>
 *   <li><b>执行侧</b>：{@code page.getByRole(AriaRole.valueOf(requireAriaConstant(role)), opts)}。
 *       历史资产里的别名/连字符仍可归一（幂等），未知角色显式失败而非静默猜角色。</li>
 * </ol>
 *
 * <h3>映射范围（不得越界）</h3>
 * <p>本契约只覆盖 {@code role}、{@code name}/{@code nameKey}、{@code nth} 三个维度——
 * 这也是 {@link LocatorSpec} 能无损承载的全部信息。{@code @RoleElement} 另有的
 * {@code exact/level/disabled/pressed/expanded/checked/selected} 等语义属性<b>不在</b> core 表示内：
 * 需要这些维度时必须走注解产物路径，禁止塞进 canonical 后静默丢弃（那会让生成产物与执行期
 * 定位到不同元素且无从对账）。</p>
 *
 * <p><b>与其它 {@code role=} 串的边界</b>：本契约只锁 {@link LocatorSpec#canonical()} 的
 * {@code role=button;name=X} 形式；{@code RoleLocatorFactory.describe()} 的
 * {@code role=BUTTON[name:X,level:2]} 与 {@code PageElement} 的选择器描述串属于日志/诊断文本，
 * 不参与映射，也不受本类约束。</p>
 *
 * <p><b>Playwright 升级流程</b>：修改 {@link #FROZEN_CONSTANT_NAMES} 与
 * {@link #BASELINE_PLAYWRIGHT_VERSION} 前，先跑 pw-codegen 的
 * {@code LocatorSpecGetByRoleContractTest}；契约失败表示枚举已漂移，必须同步更新冻结基线文件
 * 并复核别名表，不能直接放宽断言。</p>
 *
 * <p>无状态、不可变、线程安全。</p>
 */
public final class LocatorRoleVocabulary {

    /** 冻结词表对应的 Playwright Java 客户端版本（根 pom {@code playwright.version}）。 */
    public static final String BASELINE_PLAYWRIGHT_VERSION = "1.62.0";

    /** Playwright 1.62.0 {@code AriaRole.values()} 的全量常量名，按枚举声明序冻结。 */
    private static final String[] FROZEN_CONSTANT_NAMES = {
            "ALERT", "ALERTDIALOG", "APPLICATION", "ARTICLE", "BANNER", "BLOCKQUOTE",
            "BUTTON", "CAPTION", "CELL", "CHECKBOX", "CODE", "COLUMNHEADER",
            "COMBOBOX", "COMPLEMENTARY", "CONTENTINFO", "DEFINITION", "DELETION", "DIALOG",
            "DIRECTORY", "DOCUMENT", "EMPHASIS", "FEED", "FIGURE", "FORM",
            "GENERIC", "GRID", "GRIDCELL", "GROUP", "HEADING", "IMG",
            "INSERTION", "LINK", "LIST", "LISTBOX", "LISTITEM", "LOG",
            "MAIN", "MARQUEE", "MATH", "METER", "MENU", "MENUBAR",
            "MENUITEM", "MENUITEMCHECKBOX", "MENUITEMRADIO", "NAVIGATION", "NONE", "NOTE",
            "OPTION", "PARAGRAPH", "PRESENTATION", "PROGRESSBAR", "RADIO", "RADIOGROUP",
            "REGION", "ROW", "ROWGROUP", "ROWHEADER", "SCROLLBAR", "SEARCH",
            "SEARCHBOX", "SEPARATOR", "SLIDER", "SPINBUTTON", "STATUS", "STRONG",
            "SUBSCRIPT", "SUPERSCRIPT", "SWITCH", "TAB", "TABLE", "TABLIST",
            "TABPANEL", "TERM", "TEXTBOX", "TIME", "TIMER", "TOOLBAR",
            "TOOLTIP", "TREE", "TREEGRID", "TREEITEM"
    };

    private static final Set<String> CONSTANTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(FROZEN_CONSTANT_NAMES)));

    /** 能映射到常量但按 role 定位无业务意义（div/span/label 的推断角色绝大多数落在这里）。 */
    private static final Set<String> NOT_LOCATABLE = Set.of("GENERIC", "NONE", "PRESENTATION");

    /** CDP AX role → Playwright 常量名的别名（大小写/连字符已在 {@link #normalize} 统一）。 */
    private static final Map<String, String> ALIASES = Map.of(
            "IMAGE", "IMG",
            "GENERICCONTAINER", "GENERIC",
            "STATICTEXT", "GENERIC",
            "INLINETEXTBOX", "GENERIC",
            "LABELTEXT", "GENERIC",
            "LINEBREAK", "GENERIC",
            "LISTMARKER", "GENERIC");

    private LocatorRoleVocabulary() {
    }

    /** 冻结词表的只读视图（契约测试与诊断用；不可修改）。 */
    public static Set<String> constants() {
        return CONSTANTS;
    }

    /**
     * 规范化：大写 + 去掉分隔符 + 展开别名。
     *
     * <p>{@code menu-item-checkbox} / {@code menuitemcheckbox} / {@code MENUITEMCHECKBOX}
     * 三者同义；{@code image} 归一到 Playwright 的 {@code IMG}。返回空串表示空白输入。</p>
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String up = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return ALIASES.getOrDefault(up, up);
    }

    /**
     * 映射到确切的 Playwright 常量名（如 {@code "textbox"} → {@code "TEXTBOX"}）。
     *
     * @return {@code null} 表示该 role 无法映射——<b>生成侧</b>必须回退 CSS，不得臆造角色
     */
    public static String ariaConstant(String raw) {
        String key = normalize(raw);
        return key.isEmpty() || !CONSTANTS.contains(key) ? null : key;
    }

    /**
     * 执行侧入口：未知/空白 role 一律 fail-closed 抛异常。
     *
     * <p>与 {@link #ariaConstant(String)} 的 {@code null} 语义刻意不同：生成侧「不能映射」是正常
     * 降级信号，执行侧收到无法映射的资产则说明资产已损坏，静默换成另一种角色会定位到错误元素。</p>
     *
     * @throws IllegalArgumentException role 不在冻结词表内
     */
    public static String requireAriaConstant(String raw) {
        String key = normalize(raw);
        if (key.isEmpty() || !CONSTANTS.contains(key)) {
            throw new IllegalArgumentException("Unsupported ARIA role for getByRole: '" + raw
                    + "' (normalized='" + key + "'); expected one of the " + CONSTANTS.size()
                    + " AriaRole constants frozen against Playwright " + BASELINE_PLAYWRIGHT_VERSION);
        }
        return key;
    }

    /**
     * canonical 的 {@code role=} 段应写入的形式：规范常量名的小写。
     *
     * @return {@code null} 表示不可映射（调用方回退 CSS）
     */
    public static String canonicalRole(String raw) {
        String constant = ariaConstant(raw);
        return constant == null ? null : constant.toLowerCase(Locale.ROOT);
    }

    /** 该角色是否<b>值得</b>用角色定位：可映射且不是 generic/none/presentation。 */
    public static boolean isLocatable(String raw) {
        String key = ariaConstant(raw);
        return key != null && !NOT_LOCATABLE.contains(key);
    }

    /** 是否为冻结词表内的常量名（严格匹配，不做规范化）。 */
    public static boolean isConstant(String constant) {
        return constant != null && CONSTANTS.contains(constant);
    }
}

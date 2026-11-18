package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.AriaRoles;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 字段规划（Page / Step / Assert 三个 Tab 的<b>唯一命名与定位来源</b>）。
 *
 * <p>把"去重 -> List 分组 -> 命名 -> 定位注解 -> 动作/断言表达式"收在一处，三个生成器都消费同一份
 * {@link Plan}，从根本上保证 Tab1(Page)、Tab2(Step)、Tab3(Assert) 引用的是完全相同的字段名。
 *
 * <h3>定位策略</h3>
 * <ol>
 *   <li><b>无 NLS 或反查不到 key ⇒ {@code name=}</b>（产物不依赖 NLS 表即可运行）；</li>
 *   <li><b>role 可定位</b>（role 能映射到确切的 {@code AriaRole} 常量、非 generic/none/presentation，且有可读
 *       name）⇒ {@code @RoleElement(role=…, name=/key=)}；未知角色<b>绝不</b>降级成 {@code AriaRole.GENERIC}
 *       （那种注解能编译但运行期定位不到元素），一律走下一条；</li>
 *   <li><b>role 定位不了</b> ⇒ 回退 {@code @Element("<cssSelector>")}（CSS 与 XPath 统一走 value）；
 *       连 CSS 都缺失的条目直接跳过，不产出无效定位器。</li>
 * </ol>
 */
public final class CodegenFieldPlanner {

    private CodegenFieldPlanner() {}

    /** 一次规划结果：字段清单 + import 取舍所需的标志 + 类级注解。 */
    public static final class Plan {
        public final List<CodegenFieldSpec> fields;
        public final boolean hasAriaRole;
        public final boolean hasRole;
        public final boolean hasElement;
        public final boolean hasList;
        /** 类级 {@code @RoleFile(...)}（含结尾换行）；无 NLS 或纯 CSS 页面时为 ""。 */
        public final String classAnnotation;

        Plan(List<CodegenFieldSpec> fields, boolean hasAriaRole, boolean hasRole,
             boolean hasElement, boolean hasList, String classAnnotation) {
            this.fields = fields;
            this.hasAriaRole = hasAriaRole;
            this.hasRole = hasRole;
            this.hasElement = hasElement;
            this.hasList = hasList;
            this.classAnnotation = classAnnotation;
        }
    }

    public static Plan plan(Collection<CodegenEntry> entries, String[] nlsFiles) {
        return plan(entries, nlsFiles, Map.of());
    }

    /**
     * 字段规划（含录制回填值）。
     *
     * @param fillValues 字段键（= {@code sigKey}）→ 录制时输入的最终值；用于 Step 类回填真实 fill 值
     */
    public static Plan plan(Collection<CodegenEntry> entries, String[] nlsFiles, Map<String, String> fillValues) {
        return plan(entries, nlsFiles, fillValues, Set.of());
    }

    /**
     * 字段规划（含录制回填值 + <b>文本语义定位白名单</b>）。
     *
     * @param textSemanticSigs 已通过「NLS 文本定位唯一」校验的 {@code sigKey} 集合。命中且没有可用
     *                         ARIA role 时，生成 key-only {@code @RoleElement(key = "...")}（role 缺省 =
     *                         文本语义策略），而不是被迫退化成 CSS —— 这正是 label 这类"在 AriaRole 枚举里
     *                         没有对应常量、但 NLS 文案恰好唯一"的元素被误降级的原因
     *                         （2026-11-11 用户反馈：能匹配 NLS 却直接生成 CSS）。
     *                         <p><b>必须由调用方用真实引擎校验过</b>（{@code getByText(text, exact).count() == 1}），
     *                         本方法不做计数、不信任未校验的入参。
     */
    public static Plan plan(Collection<CodegenEntry> entries, String[] nlsFiles,
                            Map<String, String> fillValues, Set<String> textSemanticSigs) {
        boolean allowNlsKey = (nlsFiles != null && nlsFiles.length > 0);
        Set<String> textSemantic = textSemanticSigs == null ? Set.of() : textSemanticSigs;

        // 1) 精确去重（同元素重复点选只留首条），保持首现顺序
        Map<String, CodegenEntry> bySig = new LinkedHashMap<>();
        for (CodegenEntry e : entries) {
            if (e != null && !bySig.containsKey(e.sigKey)) bySig.put(e.sigKey, e);
        }

        // 2) List 分组：base-sig + 定位器签名相同、且序号互异（≥2 条）⇒ 一组元素。
        //    仅靠 base-sig 会把「不同容器里同 role+name 的按钮」误判成一组，导致后一个字段被跳过。
        Set<String> listGroups = listGroupKeys(bySig.values());

        List<CodegenFieldSpec> fields = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        Set<String> emittedListGroups = new HashSet<>();
        boolean hasAriaRole = false;
        boolean hasRole = false;
        boolean hasElement = false;
        boolean hasList = false;

        for (CodegenEntry e : bySig.values()) {
            String groupKey = listGroupKey(e);

            // 角色可定位 = ①拾取阶段判定为角色策略 ②role 能映射到确切的 AriaRole 常量
            // （非 generic/none/presentation）③有可读 name。任一条不满足就回退 @Element(css)：
            // 绝不把未知角色（如 label）降级成 AriaRole.GENERIC —— 那种产物能编译但运行期定位不到。
            boolean roleStrategy = e.isRoleStrategy();
            String ariaConst = AriaRoles.ariaConstant(e.role);
            boolean roleLocatable = roleStrategy && ariaConst != null && AriaRoles.isLocatable(e.role)
                    && e.name != null && !e.name.isBlank();
            boolean hasNlsKey = allowNlsKey && e.nlsKey != null && !e.nlsKey.isEmpty();
            // 多语言环境（配置了 nls 包）下，反查不到 key 就没有跨语言稳定的标识：
            // 此时禁止用 name（可访问名）定位——它随语言变化，产物换个语言就跑不通。
            if (roleLocatable && allowNlsKey && !hasNlsKey) {
                roleLocatable = false;
            }

            // 文本语义定位：无可用 ARIA role + 命中 NLS key + 主文档 + 调用方已用真实引擎校验唯一。
            boolean textSemanticLocatable = allowNlsKey && hasNlsKey && !roleLocatable
                    && e.framePath.isEmpty() && e.shadowHosts.isEmpty()
                    && textSemantic.contains(e.sigKey);
            // 语义定位（role 或文本）：用于 List 组语义校验/字段命名，二者都优于 CSS 兜底
            boolean semantic = roleLocatable || textSemanticLocatable;

            // 【唯一取数入口】按「产物里实际采用的定位引擎」选计数：
            // role → roleHits（真实 getByRole 复核值）；其余（CSS / 文本语义）→ matchCount。
            // 绝不直接读 matchCount —— role 策略下它只是兜底 CSS 的计数，与 getByRole 无关
            // （CSS 命中 5、role 命中 1 时会被误判成一组 ⇒ PageElementList + get(0)，组语义静默降级）。
            // role 未复核时返回负数 ⇒ 不判 List，下方还会产出 TODO，避免"没跑过校验却静默当成唯一"。
            int finalCount = e.effectiveMatchCount(roleLocatable);
            // 自动成组（见 CodegenEntry#autoList）优先于计数判定：整组定位器在拾取瞬间可能因懒渲染数到
            // 0/1 个，但「目标是 ARIA 集合里的一项」这个语义本身就该出 PageElementList。
            boolean isList = e.autoList || listGroups.contains(groupKey) || finalCount > 1;
            if (isList) {
                hasList = true;
                // 一组元素只合成【一个】PageElementList 字段（组内序号由运行期 x.get(i) 取单项）。
                // 自动成组是例外：它的「一组」由整组定位器决定，合并（按分组键去重）会让同一集合里
                // 第二次点击的动作整条消失——宁可多一个同定位器字段，也不能丢用户点过的那一步。
                if (!e.autoList && !emittedListGroups.add(groupKey)) continue;
            }
            // 取单项的组内序号：自动成组 = 用户实际点的那一项；其余沿用首项。
            int listIndex = listIndexOf(e, isList);

            String annotation;
            String comment = null;
            if (!e.framePath.isEmpty()) {
                // 跨 frame（含跨源 iframe）：role 无法跨 frame，统一用 @Element + frame 穿透前缀 + 内层 CSS，
                // 由框架 LocatorFactory.bySelector 解析为 frameLocator 链（保证产物可运行）。
                String inner = (e.cssSelector == null || e.cssSelector.isBlank() || "html".equals(e.cssSelector))
                        ? null : e.cssSelector;
                if (inner == null) {
                    // P1-05：内层无 CSS 时不能整条 continue——那会让字段声明/动作/TODO 一起消失，
                    // 面板上表现为「点了没反应」。改为用 frame 内 body 作可编译占位 + 显式 TODO。
                    hasElement = true;
                    annotation = "@Element(" + toJavaStringLiteral(framePiercing(e.framePath, "body")) + ")";
                    comment = "    // TODO(review): cross-frame element had no inner CSS at pick time - "
                            + "replace the 'body' placeholder with a real selector inside the iframe";
                } else {
                    hasElement = true;
                    annotation = "@Element(" + toJavaStringLiteral(framePiercing(e.framePath, inner)) + ")";
                }
            } else if (roleLocatable) {
                hasRole = true;
                hasAriaRole = true;
                StringBuilder ann = new StringBuilder("@RoleElement(role = AriaRole.").append(ariaConst);
                if (hasNlsKey) {
                    ann.append(", key = \"").append(escapeJava(e.nlsKey)).append("\"");
                } else {
                    ann.append(", name = \"").append(escapeJava(e.name)).append("\"");
                }
                ann.append(")");
                annotation = ann.toString();
                if (!allowNlsKey && containsCjk(e.name)) {
                    comment = "    // TODO(review): CJK element name has no English source "
                            + "(role-based placeholder used) - provide an English NLS key or rename for clarity";
                } else if (!allowNlsKey && containsDynamicDigit(e.name)) {
                    // M2：单语言/无 NLS 环境下 name 会被写死进注解；含数字的文案（"3 items"、
                    // "Balance: 1,234.56"）数字一变定位即失效。不擅自改写 name（会破坏语义匹配），
                    // 但必须显式标注，让人工复核时优先绑 NLS key 或改用 CSS。
                    comment = "    // TODO(review): element name contains digits - value may change at runtime; "
                            + "prefer an NLS key or a CSS locator";
                }
            } else if (textSemanticLocatable) {
                // role 缺省 = 框架的文本语义策略：运行期按 key 解析当前语言文案后 getByText 定位。
                // hasAriaRole 保持 false（不需要 AriaRole 常量 import）；hasRole 置 true 以输出 @RoleFile。
                hasRole = true;
                annotation = "@RoleElement(key = \"" + escapeJava(e.nlsKey) + "\")";
            } else {
                if (e.cssSelector == null || e.cssSelector.isBlank() || "html".equals(e.cssSelector)) {
                    continue; // 连 CSS 都没有的定位器无法生成，跳过（不污染产物）
                }
                hasElement = true;
                annotation = "@Element(" + toJavaStringLiteral(e.cssSelector) + ")";
            }

            // role 策略但未复核 / 复核不可判定：产物必须显式标注。
            // 这正是评审指出的"绕过 CodegenLauncher 复核链直接导出"路径的最后一道防线——
            // 即使生成侧没跑复核，也不会把"不知道命中几个"静默伪装成"恰好 1 个"。
            boolean roleUnverified = roleLocatable && e.roleHits < 0;
            if (roleUnverified && comment == null) {
                comment = "    // TODO(review): role locator was NOT verified by the Playwright engine "
                        + "before generation - run getByRole(role, name) uniqueness check before replay";
            }

            // 一组元素若拿不到 role 定位器（复核未通过 / 跨 frame），只能退化成组内首条的唯一 CSS：
            // 严格模式下不会报错，但这个 PageElementList 实际只命中 1 个元素（组语义丢失）。
            // 产物里显式标出来，避免评审时误以为它是"整组"。
            //  仅对「多次拾取合成的组」提示可能只命中 1 个；finalCount>1 的组本来就命中多个，不适用。
            //  自动成组的定位器本来就是整组 CSS，不存在「退化成组内首条」的问题，也不适用。
            if (isList && !e.autoList && !semantic && finalCount <= 1 && comment == null) {
                comment = "    // TODO(review): group could not use a role locator (verification failed or "
                        + "cross-frame); this @Element matches only one element of the group - review the selector";
            }

            String fieldName = toFieldName(baseFieldName(e, semantic), usedNames);

            String verb = verbFor(e);
            String fillValue = (fillValues != null) ? fillValues.get(e.sigKey) : null;
            comment = dropdownComment(e, verb, fillValue, comment);
            if (comment == null && finalCount <= 0) {
                // 0 命中 / 计数不可判定：产物必须在显眼处提示人工核对，而不是静默给出死定位器。
                comment = "    // TODO(review): locator matched " + finalCount
                        + " element(s) at pick time - verify the selector before replay";
            } else if (comment == null && isList && !e.autoList) {
                comment = "    // TODO(review): PageElementList - generated action targets get(0) only;"
                        + " add the remaining items manually";
            } else if (comment == null && isList) {
                // 自动成组：序号来自用户点的那一项，产物注释写清 get(n) 的来历，评审时不必回看录像。
                comment = "    // PageElementList: the recorded action targets get(" + listIndex
                        + ") - the item picked during recording";
            }
            // P1-06：shadow DOM 内的元素，产物注解不带 shadow 链、框架侧 LocatorFactory 也不解析 shadow。
            // 无论落到 role / 文本语义 / CSS 哪种定位，都必须在字段上显式标注，避免静默生成脆弱定位器。
            // 用前置而非覆盖：保留上面更具体的 TODO（如跨 frame 无内层 CSS）。
            if (!e.shadowHosts.isEmpty()
                    && (comment == null || !comment.contains("shadow DOM"))) {
                String shadowTodo = "    // TODO(review): element is inside shadow DOM ("
                        + String.join(" :: ", e.shadowHosts)
                        + ") - shadow chain is not encoded in the locator; verify at replay\n";
                comment = shadowTodo + (comment == null ? "" : comment);
            }
            fields.add(new CodegenFieldSpec(fieldName,
                    isList ? "PageElementList" : "PageElement",
                    annotation, comment,
                    verb, actionFor(e, isList, listIndex, fillValue), assertionFor(e, isList, listIndex),
                    isList, semantic, e, fillValue, listIndex));
        }

        String classAnnotation = (hasRole && allowNlsKey) ? buildClassAnnotation(nlsFiles) : "";
        return new Plan(fields, hasAriaRole, hasRole, hasElement, hasList, classAnnotation);
    }

    // ------------------------------------------------------------------
    // 方法名派生（Step / Assert 共用同一主干，保证 performXxx / assertXxx 成对）
    // ------------------------------------------------------------------

    /**
     * 由页面类名派生的方法名主干：{@code LogonPage → Logon}，类名不足以去后缀时原样返回。
     *
     * <p>一次 {@code Start→Stop} 会话只产出一个 Step 方法与一个 Assert 方法，二者共用本主干，
     * 于是产物天然成对（{@code performLogon()} / {@code assertLogon()}），业务侧一眼能看出配对的调用。
     */
    static String classNameBase(CodegenTarget target) {
        String cls = target.pageClassName;
        String base = (cls != null && cls.length() > 4 && cls.endsWith("Page"))
                ? cls.substring(0, cls.length() - 4) : cls;
        if (base == null || base.isBlank()) base = "Page";
        return base;
    }

    // ------------------------------------------------------------------
    // Step / Assert 表达式派生（按 role 选最自然的操作）
    // ------------------------------------------------------------------

    /**
     * Step 方法名的动词部分：按 role 派生（click/fill/check）。
     * 与 {@link #actionFor} 同源，避免"方法名叫 fillXxx、里面却是 click()"的错位。
     */
    static String verbFor(CodegenEntry e) {
        // 入参校验前置：过去方法尾部还留着一处 `e != null` 冗余判空（SpotBugs
        // RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE），说明入口契约从未明确。
        // 统一为语义化失败，避免后续分支里出现"半路才判空"的错觉。
        java.util.Objects.requireNonNull(e, "entry must not be null");
        String role = e.role == null ? "" : e.role.toLowerCase(Locale.ROOT);
        switch (role) {
            case "combobox":
            case "listbox":
                // 原生 <select> ⇒ selectByValue；自定义下拉（div role=combobox）⇒ 只能展开后点选项。
                // 两者都绝不能生成 fill()：Playwright 的 fill 只接受 input/textarea/contenteditable，
                // 对 <select> 与自定义下拉直接抛 "Element is not an <input> or <textarea>"（回放必挂）。
                return isNativeSelect(e) ? "select" : "click";
            case "textbox":
            case "searchbox":
            case "spinbutton":
                return "fill";
            case "checkbox":
                // 读拾取时刻的真实勾选态：录「取消勾选」（checked=false）必须生成 uncheck()，
                // 否则产物会把用户的取消动作回放成勾选，终态与录制相反。
                return e.checked ? "check" : "uncheck";
            case "radio":
                // 点击 radio 必然选中（同组旧选项自动取消），不存在「取消选中」的操作语义
                return "check";
            default:
                return "click";
        }
    }

    /** 原生 {@code <select>}：只有它有「选项列表」语义，可用 selectByValue 一次性选中。 */
    static boolean isNativeSelect(CodegenEntry e) {
        return e != null && "select".equals(e.tag);
    }

    /**
     * 一组元素时 Step / Assert 指向的组内序号（0 基）；单元素恒 {@code 0}。
     *
     * <p>只有<b>自动成组</b>（{@link CodegenEntry#autoList}）才保留拾取时的序号——那是用户实际点的那一项
     * （密保问题下拉第 5 项）；多次拾取 / 面板标记合成的组，其 {@code index} 只是成员序号，
     * 产物沿用首项 {@code get(0)}。
     */
    static int listIndexOf(CodegenEntry e, boolean isList) {
        if (!isList || e == null || !e.autoList) return 0;
        // P2-01：负序号是数据异常（真实录制里 autoList 的 index 来自 nth 序号，恒 >= 0），
        // 绝不能生成 get(-1)（运行期必抛）；归零到 get(0) 是唯一安全的编译期 fallback。
        // 正常路径下 e.index 已非负，Math.max 只在异常载荷时兜底。
        return Math.max(0, e.index);
    }

    /** 取单项前缀：一组元素 {@code get(n).}，单元素空串。 */
    static String listTarget(boolean isList, int listIndex) {
        // P2-01：listIndex 由 listIndexOf 保证非负；这里再兜一层，杜绝任何路径生成 get(-1)。
        return isList ? "get(" + Math.max(0, listIndex) + ")." : "";
    }

    /**
     * Step 动作：按 role 派生（click/fill/check），一组元素取 {@code get(n)} 单项
     * （n 见 {@link #listIndexOf(CodegenEntry, boolean)}）。
     * 输入框优先回填录制时输入的最终值（令产物开箱即用）；未录制到值才退化为 {@code ""} 占位 + TODO。
     *
     * @param fillValue 该字段录制到的最终输入值（key=sigKey），可为 null
     */
    static String actionFor(CodegenEntry e, boolean isList, int listIndex, String fillValue) {
        String target = listTarget(isList, listIndex);
        String verb = verbFor(e);
        if ("fill".equals(verb)) {
            String v = (fillValue != null) ? fillValue : "";
            return target + "fill(\"" + escapeJava(v) + "\")";
        }
        if ("select".equals(verb)) {
            // 录制值来自 <option value>；没有录制值时不能生成 selectByValue("")（必然匹配不到选项），
            // 退化为 click() 并在字段注释里标注需人工补值。
            if (fillValue != null && !fillValue.isEmpty()) {
                return target + "selectByValue(\"" + escapeJava(fillValue) + "\")";
            }
            return target + "click()";
        }
        return target + verb + "()";
    }

    /** Assert 表达式：复选框/单选看勾选态，其余看可见性（{@code PageElement} 均提供）。 */
    static String assertionFor(CodegenEntry e, boolean isList, int listIndex) {
        String target = listTarget(isList, listIndex);
        String role = e.role == null ? "" : e.role.toLowerCase(Locale.ROOT);
        return target + (("checkbox".equals(role) || "radio".equals(role)) ? "isChecked()" : "isVisible()");
    }

    /**
     * 下拉类元素的可读性收尾：两种降级必须在产物里显式标注，否则用户会以为代码可用。
     *
     * <p>① 自定义下拉（div role=combobox）：产物只能 {@code click()} 展开，选值需人工补；
     * ② 原生 {@code <select>} 但没录到选项值：{@code selectByValue} 需要人工填。
     *
     * @param comment 已有的提示（CJK / 组降级）；非空时保持原样，不覆盖
     */
    private static String dropdownComment(CodegenEntry e, String verb, String fillValue, String comment) {
        if (comment != null) return comment;
        String role = e.role == null ? "" : e.role.toLowerCase(Locale.ROOT);
        boolean dropdown = "combobox".equals(role) || "listbox".equals(role);
        if (!dropdown) return null;
        if ("click".equals(verb)) {
            return "    // TODO(review): custom dropdown - click() only expands it; "
                    + "add the option click manually (no native <select> for selectByValue)";
        }
        if ("select".equals(verb) && (fillValue == null || fillValue.isEmpty())) {
            return "    // TODO(review): no option value was recorded - fill selectByValue(\"...\") manually";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // List 分组（对齐 V2 listGroupBaseSigs）
    // ------------------------------------------------------------------

    /**
     * 去掉签名末尾的 {@code #<数字>}（组内序号），得到「定位器级」基础签名。
     *
     * <p>{@code public} 供 Inspector 的生成前复核复用：判定"该条目属于一组"必须与字段规划<b>同一口径</b>，
     * 否则复核会按单元素语义（必须恰好 1 命中）误杀本应作为 {@code PageElementList} 的 role 定位器。
     */
    public static String baseSigOf(String sigKey) {
        if (sigKey == null) return "";
        int h = sigKey.lastIndexOf('#');
        if (h > 0 && h < sigKey.length() - 1) {
            boolean digits = true;
            for (int i = h + 1; i < sigKey.length(); i++) {
                if (!Character.isDigit(sigKey.charAt(i))) { digits = false; break; }
            }
            if (digits) return sigKey.substring(0, h);
        }
        return sigKey;
    }

    /**
     * 一组元素的 base-sig 集合：同一 base-sig 下有 ≥2 个互异的有效序号。
     *
     * <p>{@code public} 供 Inspector 的生成前复核复用（见 {@link #baseSigOf(String)} 的说明）。
     *
     * <p><b>自动成组（{@link CodegenEntry#autoList}）不参与本判据</b>：它的「一组」来自整组定位器，
     * 与 {@code role|name} 基座无关。若仍按 base-sig 合并，「两个不同下拉里名字都叫 Yes 的选项」
     * 会被并成一个字段、共用第一个下拉的定位器，第二个动作直接消失——序号语义也不同
     * （自动成组的是用户点的那一项，不是组成员号）。
     */
    public static Set<String> listGroupBaseSigs(Collection<CodegenEntry> entries) {
        Set<String> keys = listGroupKeys(entries);
        Set<String> bases = new LinkedHashSet<>();
        for (CodegenEntry e : entries) {
            if (e == null || e.autoList) continue;
            if (keys.contains(listGroupKey(e))) bases.add(baseSigOf(e.sigKey));
        }
        return bases;
    }

    /**
     * 成组的分组键集合（见 {@link #listGroupKey(CodegenEntry)}）：同一分组键下有 ≥2 个互异的有效序号。
     *
     * <p>供 {@link #plan} 精确判定「当前条目属于哪一组」。只按 base-sig 判定会把「不同容器里同
     * role+name 的按钮」误并成一组，后一个字段因此在 {@code emittedListGroups} 处被跳过。
     */
    public static Set<String> listGroupKeys(Collection<CodegenEntry> entries) {
        Map<String, Set<Integer>> idxByGroup = new LinkedHashMap<>();
        for (CodegenEntry e : entries) {
            if (e == null || e.autoList) continue;
            idxByGroup.computeIfAbsent(listGroupKey(e), k -> new LinkedHashSet<>()).add(e.index);
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, Set<Integer>> en : idxByGroup.entrySet()) {
            Set<Integer> idxs = en.getValue();
            if (idxs.size() < 2) continue;                 // 同一元素被重复点选 ⇒ 不成组
            boolean usable = true;
            for (Integer i : idxs) {
                if (i == null || i < 0) { usable = false; break; }
            }
            if (usable) keys.add(en.getKey());
        }
        return keys;
    }

    /**
     * List 分组键 = base-sig + 定位器签名（定位引擎 + 选择器）。
     *
     * <p>真·同一组的成员共用同一「定位引擎 + 选择器」，仅 {@code index} 互异。不同容器里同 role+name、
     * 但一个回退 CSS、一个保留 role 的按钮定位器不同，分组键也不同，不会被误并成一个 List。
     */
    public static String listGroupKey(CodegenEntry e) {
        String strategy = e.strategy == null ? "" : e.strategy;
        String selector = e.selector == null ? "" : e.selector;
        return baseSigOf(e.sigKey) + "|@|" + strategy + '|' + selector;
    }

    // ------------------------------------------------------------------
    // 类级 @RoleFile
    // ------------------------------------------------------------------

    static String buildClassAnnotation(String[] nlsFiles) {
        if (nlsFiles.length == 1) {
            return "@RoleFile(\"" + escapeJava(nlsFiles[0]) + "\")\n";
        }
        StringBuilder sb = new StringBuilder("@RoleFile({");
        for (int i = 0; i < nlsFiles.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(escapeJava(nlsFiles[i])).append('"');
        }
        sb.append("})\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 字段命名（camelCase + 保留字护栏）
    // ------------------------------------------------------------------

    /**
     * 选择字段名的基础来源（已转成 ASCII lowerCamelCase）。
     *
     * <p><b>产物中的标识符不允许出现中文</b>：Java 语法允许中文标识符，但不可维护——无法输入/搜索、
     * 跨 IDE 与编码风险、code review 不可读。而中文业务页面的可访问名天然是中文，所以这里按
     * 「转得出 ASCII 就用」逐级顺延：
     * <ol>
     *   <li>{@code name}（可读名，通常是 label 文本）；</li>
     *   <li>{@code nlsKey}（跨语言稳定，且天然是 {@code otp_radio_label} 这类 ASCII）；</li>
     *   <li>角色（仅角色可定位时；如 {@code button}/{@code textbox}）；</li>
     *   <li>选择器标签（{@code #save → save}、{@code #container > div → containerDiv}）；</li>
     *   <li>兜底 {@code element}。</li>
     * </ol>
     *
     * <p>注意：<b>不引入拼音依赖</b>——中文名整段丢弃（只保留其中夹杂的英文/数字），
     * 由后续来源提供有意义的 ASCII 名。
     */
    /** 语义名转成标识符后的最大长度；超过即视为容器元素聚合出的整页文案，不可用语义名。 */
    private static final int MAX_NAME_IDENTIFIER = 60;

    static String baseFieldName(CodegenEntry e, boolean roleLocatable) {
        if (e == null) return "element";
        // 容器元素（main/document/generic）的可访问名会聚合所有后代文本（含 <style> CSS 块与整页文案），
        // 经 toIdentifier 后仍可能长达上千字符，绝不能拿来当 Java 标识符。超过阈值即视为「不可用语义名」，
        // 顺延到 nlsKey / 角色 / 选择器标签，避免生成 logonPage.logOnToBusinessInternetBankingUsername… 这类巨名。
        String fromName = toIdentifier(e.name);
        if (!fromName.isEmpty() && fromName.length() <= MAX_NAME_IDENTIFIER) return withKindSuffix(fromName, e);
        String fromKey = toIdentifier(e.nlsKey);
        if (!fromKey.isEmpty()) return withKindSuffix(fromKey, e);
        if (roleLocatable) {
            String fromRole = toIdentifier(e.role);
            if (!fromRole.isEmpty()) return withKindSuffix(fromRole, e);
        }
        String fromSelector = toIdentifier(selectorLabel(e.cssSelector));
        if (!fromSelector.isEmpty()) return withKindSuffix(fromSelector, e);
        return "element";
    }

    /**
     * 元素种类后缀（用户 2026-11-15 命名规范）：{@code xxxBtn} / {@code xxxInput} / {@code xxxLink} /
     * {@code xxxLabel} / {@code xxxRadio} / {@code xxxCheckbox}，标题按层级 {@code xxxTitle} / {@code xxxSubtitle}。
     *
     * <h3>为什么按「角色优先、标签兜底」判定</h3>
     * <p>同一个 {@code <input>} 可能是按钮（{@code type=submit}）、单选框、复选框或文本框，只看标签无法区分；
     * 而 AX 角色（radio / checkbox / textbox / button …）正是运行期 {@code getByRole} 用的同一套语义，
     * 按它命名能让字段名与产物里的定位方式一一对应。角色缺失（历史构造 / 非标准元素）时才退回标签判定。
     *
     * <h3>映射表</h3>
     * <ul>
     *   <li>{@code button}（含 {@code <button>} / {@code input[type=submit|button]}）⇒ {@code Btn}</li>
     *   <li>{@code link}（{@code <a href>}）⇒ {@code Link}</li>
     *   <li>{@code textbox} / {@code searchbox} / {@code spinbutton} / {@code <textarea>} ⇒ {@code Input}</li>
     *   <li>{@code radio} ⇒ {@code Radio}；{@code checkbox} / {@code switch} ⇒ {@code Checkbox}</li>
     *   <li>{@code combobox} / {@code listbox} / {@code <select>} ⇒ {@code Select}</li>
     *   <li>{@code label} ⇒ {@code Label}</li>
     *   <li>{@code heading} / {@code <h1>}~{@code <h6>} ⇒ {@code Title}（h1/h2）/{@code Subtitle}（h3~h6）</li>
     * </ul>
     *
     * <p>其余元素（普通 div / span / img / 容器）不加后缀：它们的字段名本身就来自业务文案，
     * 硬加 {@code Div}/{@code Span} 只会制造噪声。
     */
    static String kindSuffix(CodegenEntry e) {
        if (e == null) return "";
        String role = e.role == null ? "" : e.role.toLowerCase(Locale.ROOT);
        String tag = e.tag == null ? "" : e.tag.toLowerCase(Locale.ROOT);
        switch (role) {
            case "button":
                return "Btn";
            case "link":
                return "Link";
            case "radio":
                return "Radio";
            case "checkbox":
            case "switch":
                return "Checkbox";
            case "textbox":
            case "searchbox":
            case "spinbutton":
                return "Input";
            case "combobox":
            case "listbox":
                return "Select";
            case "label":
                return "Label";
            case "heading":
                return headingSuffix(tag);
            default:
                break;
        }
        switch (tag) {
            case "button":
                return "Btn";
            case "a":
                return "Link";
            case "input":
            case "textarea":
                return "Input";
            case "select":
                return "Select";
            case "label":
                return "Label";
            default:
                break;
        }
        return tag.matches("h[1-6]") ? headingSuffix(tag) : "";
    }

    /** 标题后缀：h1/h2 是页面/区块标题（{@code Title}），h3~h6 是子标题（{@code Subtitle}）。 */
    private static String headingSuffix(String tag) {
        switch (tag) {
            case "h3":
            case "h4":
            case "h5":
            case "h6":
                return "Subtitle";
            default:
                return "Title";
        }
    }

    /**
     * 把种类后缀拼到语义名后面，并挡住三类「重复/无意义」拼接：
     * <ol>
     *   <li>名字本身就是角色名（{@code button} / {@code radio} / {@code label} …，来自角色兜底档）⇒ 不再叠加；</li>
     *   <li>名字已以该后缀结尾（{@code privacyLink}）⇒ 不再叠加；</li>
     *   <li>兜底名 {@code element} ⇒ 保持中性。</li>
     * </ol>
     */
    static String withKindSuffix(String base, CodegenEntry e) {
        if (base == null || base.isEmpty()) return base;
        String suffix = kindSuffix(e);
        if (suffix.isEmpty() || GENERIC_BASE_NAMES.contains(base.toLowerCase(Locale.ROOT))) return base;
        if (base.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) return base;
        return base + suffix;
    }

    /** 「本身就是角色名」的兜底标识符：与后缀叠加会得到 {@code buttonBtn} / {@code textboxInput} 这类噪声。 */
    private static final Set<String> GENERIC_BASE_NAMES = Collections.unmodifiableSet(Set.of(
            "element", "button", "link", "radio", "checkbox", "switch", "textbox", "searchbox",
            "spinbutton", "combobox", "listbox", "label", "heading", "title", "subtitle",
            "input", "select", "textarea"));

    /**
     * 生成最终字段名（唯一化）。
     *
     * <p>{@code baseName} 约定已由 {@link #baseFieldName} / {@link #toIdentifier} 归一化为 ASCII
     * lowerCamelCase，这里<b>只做字符白名单与首字符兜底、不重跑大小写归一化</b>：否则会把
     * {@code signIn} 压成 {@code signin}（驼峰信息丢失、可读性下降），本方法也失去幂等性。
     */
    static String toFieldName(String baseName, Set<String> used) {
        String candidate = safeFieldName(sanitizeIdentifier(baseName));
        String unique = candidate;
        int n = 2;
        while (used.contains(unique)) unique = candidate + (n++);
        used.add(unique);
        return unique;
    }

    /**
     * 标识符兜底：只保留 {@code [A-Za-z0-9_$]}、首字符小写化、数字/非标识符起始时加 {@code field} 前缀。
     *
     * <p>刻意<b>不改变其余字符大小写</b>，保证对 {@link #toIdentifier} 的产物幂等。
     */
    private static String sanitizeIdentifier(String s) {
        if (s == null) return "element";
        String t = s.replaceAll("[^A-Za-z0-9_$]", "");
        if (t.isEmpty()) return "element";
        char c0 = t.charAt(0);
        if (c0 >= 'A' && c0 <= 'Z') return Character.toLowerCase(c0) + t.substring(1);
        if (!Character.isJavaIdentifierStart(c0)) return "field" + t;
        return t;
    }

    private static final Set<String> JAVA_RESERVED = Collections.unmodifiableSet(Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_"));

    static String safeFieldName(String candidate) {
        if (candidate == null || candidate.isEmpty()) return candidate;
        return JAVA_RESERVED.contains(candidate) ? candidate + "Field" : candidate;
    }

    /**
     * 把任意可读名转为合法 lowerCamelCase Java 标识符（<b>纯 ASCII</b>）。
     *
     * <p>只保留 ASCII 字母数字，其余字符（空格/标点/中文/日文/带音标字符…）一律作词边界丢弃。
     * 因此纯中文名会返回空串，由 {@link #baseFieldName} 顺延到 nlsKey / 角色 / 选择器标签——
     * 这是"不引入拼音依赖"前提下消除中文标识符的关键。
     *
     * <p>中英混合（{@code "Login 登录"}）会保留 {@code login}，仍有意义。
     */
    static String toIdentifier(String name) {
        if (name == null || name.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 128 && Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upperNext = false;
            } else {
                upperNext = true;
            }
        }
        String s = sb.toString();
        if (s.isEmpty()) return "";
        if (!Character.isJavaIdentifierStart(s.charAt(0))) s = "field" + s;
        return s;
    }

    /**
     * 字段名转 PascalCase（拼方法名用）。
     *
     * <p>只保留 ASCII：字段名本身已由 {@link #baseFieldName} 保证纯 ASCII，这里再兜一层，
     * 确保方法名（{@code clickSave()}）永不出现中文。
     */
    static String pascal(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return "Element";
        String s = fieldName.replaceAll("[^A-Za-z0-9_$]", "");
        if (s.isEmpty()) return "Element";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String selectorLabel(String selector) {
        if (selector == null) return "element";
        if (selector.startsWith("xpath=")) return xpathLabel(selector.substring("xpath=".length()));
        if (selector.startsWith("#")) return selector.substring(1);
        int eq = selector.indexOf("=\"");
        if (eq >= 0) {
            int end = selector.indexOf('"', eq + 2);
            if (end > eq + 2) return selector.substring(eq + 2, end);
        }
        return selector;
    }

    /**
     * 从绝对 XPath 里提取可读的字段名片段：优先属性谓词（{@code @id='x'}），否则取最后一段标签名。
     * 直接把整条 {@code /html/body/div[1]} 当名字会得到 {@code xpathHtmlBodyDiv1} 这类无意义字段名。
     */
    private static String xpathLabel(String expr) {
        if (expr == null || expr.isBlank()) return "element";
        int at = expr.lastIndexOf('@');
        if (at >= 0) {
            int eq = expr.indexOf('=', at);
            if (eq > at) {
                String tail = expr.substring(eq + 1).replaceFirst("^['\"]", "");
                int end = tail.indexOf('\'');
                int end2 = tail.indexOf('"');
                int cut = (end < 0) ? end2 : (end2 < 0 ? end : Math.min(end, end2));
                if (cut > 0) tail = tail.substring(0, cut);
                if (!tail.isBlank()) return tail;
            }
        }
        String[] segments = expr.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String seg = segments[i].replaceAll("\\[.*$", "").trim();
            if (!seg.isEmpty() && !"*".equals(seg)) return seg;
        }
        return "element";
    }

    // ------------------------------------------------------------------
    // 字符串转义
    // ------------------------------------------------------------------

    /**
     * Java 字符串字面量转义：所有 C0 控制字符 + 反斜杠 / 双引号 + Unicode 行分隔符。
     *
     * <p>public 供 {@code CodegenLauncher} 在「按本次操作值生成动作」时复用，
     * 避免同一套转义规则出现两份实现（两份必然漂移）。
     *
     * <p>P2-06：历史实现只处理 5 类字符，{@code \b}/{@code \f} 及其它 C0 控制字符原样进字面量，
     * 轻则源码不可读、重则生成非法 Java 字面量（如 U+0000 被某些编译器当作源文件终止）。这里统一转义。
     */
    public static String escapeJava(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\u0000': sb.append("\\0"); break;
                case '\u001A': sb.append("\\Z"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20 || c == 0x7F) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * 包成合法 Java 字符串字面量：CSS 属性选择器内的双引号换单引号，避免反斜杠转义。
     *
     * <p>codegen 产出的 CSS 选择器由注入侧按 id / class / role 等结构化信息拼装，不会出现
     * 属性值内嵌引号的情形（如 {@code [title="it's"]}），故沿用换单引号的既有口径即可。
     */
    static String toJavaStringLiteral(String s) {
        if (s == null) return "\"\"";
        s = s.replace("\"", "'");
        return "\"" + escapeJava(s) + "\"";
    }

    /**
     * 拼接跨 frame 穿透定位器字符串：{@code frame=SELa :: SELb :: <内层 css>}。
     * 由框架 {@code LocatorFactory.bySelector} 解析为 {@code page.frameLocator(SELa).frameLocator(SELb).locator(<内层 css>)}。
     */
    static String framePiercing(java.util.List<String> framePath, String inner) {
        StringBuilder sb = new StringBuilder("frame=");
        sb.append(String.join(" :: ", framePath));
        sb.append(" :: ").append(inner);
        return sb.toString();
    }

    static boolean containsCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x4E00 && s.charAt(i) <= 0x9FFF) return true;
        }
        return false;
    }

    /** name 是否含数字（≥1 位，含千分位/小数）——用于 M2 的动态文案 TODO 标注。 */
    static boolean containsDynamicDigit(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }
}

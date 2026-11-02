package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.AriaRoles;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.LocatorStrategy;
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
 * <p>把"去重 → List 分组 → 命名 → 定位注解 → 动作/断言表达式"收在一处，三个生成器都消费同一份
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
        boolean allowNlsKey = (nlsFiles != null && nlsFiles.length > 0);

        // 1) 精确去重（同元素重复点选只留首条），保持首现顺序
        Map<String, CodegenEntry> bySig = new LinkedHashMap<>();
        for (CodegenEntry e : entries) {
            if (e != null && !bySig.containsKey(e.sigKey)) bySig.put(e.sigKey, e);
        }

        // 2) List 分组：base-sig 相同且序号互异（≥2 条）⇒ 一组元素
        Set<String> listBases = listGroupBaseSigs(bySig.values());

        List<CodegenFieldSpec> fields = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        Set<String> emittedListBases = new HashSet<>();
        boolean hasAriaRole = false;
        boolean hasRole = false;
        boolean hasElement = false;
        boolean hasList = false;

        for (CodegenEntry e : bySig.values()) {
            String baseSig = baseSigOf(e.sigKey);
            boolean isList = listBases.contains(baseSig);
            if (isList) {
                hasList = true;
                // 一组元素只合成【一个】PageElementList 字段（组内序号由运行期 x.get(i) 取单项）
                if (!emittedListBases.add(baseSig)) continue;
            }

            // 角色可定位 = ①拾取阶段判定为角色策略（已用真实 Playwright 引擎按唯一命中复核过）
            // ②role 能映射到确切的 AriaRole 常量（非 generic/none/presentation）③有可读 name。
            // 任一条不满足就回退 @Element(css)：绝不把未知角色（如 label）降级成 AriaRole.GENERIC ——
            // 那种产物能编译但运行期按 getByRole 定位不到元素，属于静默失效。
            String ariaConst = AriaRoles.ariaConstant(e.role);
            boolean roleStrategy = LocatorStrategy.ROLE.equals(e.strategy)
                    || LocatorStrategy.ROLE_WITH_NAME.equals(e.strategy);
            boolean roleLocatable = roleStrategy && ariaConst != null && AriaRoles.isLocatable(e.role)
                    && e.name != null && !e.name.isBlank();
            boolean hasNlsKey = allowNlsKey && e.nlsKey != null && !e.nlsKey.isEmpty();
            // 多语言环境（配置了 nls 包）下，反查不到 key 就没有跨语言稳定的标识：
            // 此时禁止用 name（可访问名）定位——它随语言变化，产物换个语言就跑不通。
            // 直接回退 @Element(css/xpath)，由 id/name 属性/class/token/结构/XPath 承担。
            if (roleLocatable && allowNlsKey && !hasNlsKey) {
                roleLocatable = false;
            }

            String annotation;
            String comment = null;
            if (!e.framePath.isEmpty()) {
                // 跨 frame（含跨源 iframe）：role 无法跨 frame，统一用 @Element + frame 穿透前缀 + 内层 CSS，
                // 由框架 LocatorFactory.bySelector 解析为 frameLocator 链（保证产物可运行）。
                String inner = (e.cssSelector == null || e.cssSelector.isBlank() || "html".equals(e.cssSelector))
                        ? null : e.cssSelector;
                if (inner == null) {
                    continue; // 内层无 CSS 无法生成穿透定位器，跳过
                }
                hasElement = true;
                annotation = "@Element(" + toJavaStringLiteral(framePiercing(e.framePath, inner)) + ")";
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
                            + "(role-based placeholder used) — provide an English NLS key or rename for clarity";
                }
            } else {
                if (e.cssSelector == null || e.cssSelector.isBlank() || "html".equals(e.cssSelector)) {
                    continue; // 连 CSS 都没有的定位器无法生成，跳过（不污染产物）
                }
                hasElement = true;
                annotation = "@Element(" + toJavaStringLiteral(e.cssSelector) + ")";
            }

            // 一组元素若拿不到 role 定位器（复核未通过 / 跨 frame），只能退化成组内首条的唯一 CSS：
            // 严格模式下不会报错，但这个 PageElementList 实际只命中 1 个元素（组语义丢失）。
            // 产物里显式标出来，避免评审时误以为它是"整组"。
            if (isList && !roleLocatable && comment == null) {
                comment = "    // TODO(review): group could not use a role locator (verification failed or "
                        + "cross-frame); this @Element matches only one element of the group — review the selector";
            }

            String fieldName = toFieldName(baseFieldName(e, roleLocatable), usedNames);

            String verb = verbFor(e);
            String fillValue = (fillValues != null) ? fillValues.get(e.sigKey) : null;
            fields.add(new CodegenFieldSpec(fieldName,
                    isList ? "PageElementList" : "PageElement",
                    annotation, comment,
                    verb, actionFor(e, isList, fillValue), assertionFor(e, isList),
                    isList, roleLocatable, e));
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
        String role = e.role == null ? "" : e.role.toLowerCase(Locale.ROOT);
        switch (role) {
            case "textbox":
            case "searchbox":
            case "spinbutton":
            case "combobox":
            case "listbox":
                return "fill";
            case "checkbox":
            case "radio":
                return "check";
            default:
                return "click";
        }
    }

    /**
     * Step 动作：按 role 派生（click/fill/check），一组元素取 {@code get(0)} 单项。
     * 输入框优先回填录制时输入的最终值（令产物开箱即用）；未录制到值才退化为 {@code ""} 占位 + TODO。
     *
     * @param fillValue 该字段录制到的最终输入值（key=sigKey），可为 null
     */
    static String actionFor(CodegenEntry e, boolean isList, String fillValue) {
        String target = isList ? "get(0)." : "";
        String verb = verbFor(e);
        if ("fill".equals(verb)) {
            String v = (fillValue != null) ? fillValue : "";
            return target + "fill(\"" + escapeJava(v) + "\")";
        }
        return target + verb + "()";
    }

    /** Assert 表达式：复选框/单选看勾选态，其余看可见性（{@code PageElement} 均提供）。 */
    static String assertionFor(CodegenEntry e, boolean isList) {
        String target = isList ? "get(0)." : "";
        String role = e.role == null ? "" : e.role.toLowerCase(Locale.ROOT);
        return target + (("checkbox".equals(role) || "radio".equals(role)) ? "isChecked()" : "isVisible()");
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
     */
    public static Set<String> listGroupBaseSigs(Collection<CodegenEntry> entries) {
        Map<String, Set<Integer>> idxByBase = new LinkedHashMap<>();
        for (CodegenEntry e : entries) {
            if (e == null) continue;
            idxByBase.computeIfAbsent(baseSigOf(e.sigKey), k -> new LinkedHashSet<>()).add(e.index);
        }
        Set<String> bases = new LinkedHashSet<>();
        for (Map.Entry<String, Set<Integer>> en : idxByBase.entrySet()) {
            Set<Integer> idxs = en.getValue();
            if (idxs.size() < 2) continue;                 // 同一元素被重复点选 ⇒ 不成组
            boolean usable = true;
            for (Integer i : idxs) {
                if (i == null || i < 0) { usable = false; break; }
            }
            if (usable) bases.add(en.getKey());
        }
        return bases;
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
        if (!fromName.isEmpty() && fromName.length() <= MAX_NAME_IDENTIFIER) return fromName;
        String fromKey = toIdentifier(e.nlsKey);
        if (!fromKey.isEmpty()) return fromKey;
        if (roleLocatable) {
            String fromRole = toIdentifier(e.role);
            if (!fromRole.isEmpty()) return fromRole;
        }
        String fromSelector = toIdentifier(selectorLabel(e.cssSelector));
        if (!fromSelector.isEmpty()) return fromSelector;
        return "element";
    }

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

    /** Java 字符串字面量转义：反斜杠 / 双引号 / 换行 / 回车 / 制表，其余原样。 */
    static String escapeJava(String s) {
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
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 包成合法 Java 字符串字面量（CSS 属性选择器内的双引号换单引号，避免反斜杠转义）。 */
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
}

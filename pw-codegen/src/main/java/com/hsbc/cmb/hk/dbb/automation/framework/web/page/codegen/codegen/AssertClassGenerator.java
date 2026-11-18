package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 生成 Assert 类源码（Tab3）：<b>一次 Start→Pause 会话 = 一个 {@code @Step} 方法</b>。
 *
 * <h2>与 Step 严格同构</h2>
 * <p>{@code step1..N} ↔ {@code assert1..N} 一一对应（同一会话），方法体内同样<b>按需实例化本会话
 * 涉及的页面类</b>；字段名由 {@link CodegenFieldPlanner} 统一规划，与 Page 类同源。
 *
 * <h2>断言风格：hamcrest（失败即显示字符差异）</h2>
 * <ul>
 *   <li>有可读 name 的普通元素 ⇒ {@code assertThat(page.field.getText(), is("Sign in"))}；</li>
 *   <li>输入框/下拉 ⇒ {@code getValue()}，期望值取<b>录制到的最终输入值</b>（没有录制值则退化为可见性
 *       + TODO，<b>绝不</b>用可访问名 label/placeholder 冒充输入值）；</li>
 *   <li>checkbox / radio ⇒ {@code assertThat(page.field.isChecked(), is(true|false))}，
 *       期望值取拾取时刻的真实勾选态；</li>
 *   <li>无 name 的元素 ⇒ 退化为可见性 {@code assertThat(..., isVisible(), is(true))}；</li>
 *   <li>命中 NLS key ⇒ 走 {@code NLS.getTextByKey(key)}，语言无关。</li>
 * </ul>
 * 一组元素（{@code PageElementList}）取与 Step 相同的 {@code get(n)} 单项断言（见
 * {@link CodegenFieldSpec#listTarget()}）；自动成组时 n 是用户实际点的那一项，Step 与 Assert 必须指向同一项。
 *
 * <p>模板：{@code codegen/templates/assert-class-sessions.ftl}。
 */
public final class AssertClassGenerator {

    private static final String TEMPLATE = "assert-class-sessions.ftl";

    private AssertClassGenerator() {
    }

    /**
     * @param header   命名来源（本会话第一个页面）：决定 Assert 类名与包名
     * @param sessions 会话序列（一次 Start→Pause 一项）
     * @param nlsFiles NLS bundle 路径（null/空表示无 NLS）
     */
    public static String generateSessions(CodegenTarget header, List<MultiPageCodegen.SessionUnit> sessions,
                                          String[] nlsFiles) {
        if (header == null) {
            throw new IllegalArgumentException("codegen target must not be null");
        }
        List<String> imports = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // 与 Step 一致：Page 对象声明为类字段，不在每个断言方法内重复实例化。
        List<String> fields = new ArrayList<>();
        Set<String> fieldClasses = new LinkedHashSet<>();
        List<Map<String, Object>> methods = new ArrayList<>(sessions.size());
        boolean hasNlsAssert = false;

        for (MultiPageCodegen.SessionUnit s : sessions) {
            for (CodegenTarget page : s.pages) {
                if (seen.add(page.pageClassName)) {
                    imports.add(page.packageName + "." + page.pageClassName);
                }
                if (fieldClasses.add(page.pageClassName)) {
                    fields.add("    private final " + page.pageClassName + " " + page.pageVar
                            + " = PageObjectFactory.getPage(" + page.pageClassName + ".class);");
                }
            }

            List<String> body = new ArrayList<>();
            for (MultiPageCodegen.PageEntries pe : s.asserts) {
                for (CodegenEntry entry : pe.entries) {
                    CodegenFieldSpec spec = specOf(pe.plan, entry);
                    if (spec == null) continue;
                    boolean[] nls = new boolean[1];
                    body.add(assertLine(pe.target, spec, entry, pe.sessionFillValues, nlsFiles, nls));
                    hasNlsAssert |= nls[0];
                }
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", "assert" + s.index);
            m.put("bodyLines", body);
            methods.add(m);
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("generatedMarker", CodegenMarkers.GENERATED_MARKER);
        model.put("assertPackageName", header.assertPackageName);
        model.put("assertClassName", header.assertClassName);
        model.put("imports", imports);
        model.put("fields", fields);
        model.put("methods", methods);
        model.put("hasNlsAssert", hasNlsAssert);
        model.put("nlsBindExpr", nlsBindExpr(nlsFiles));
        return CodegenTemplates.render(TEMPLATE, model);
    }

    /**
     * 按 sigKey 取字段规划（Page/Step/Assert 共用同一份 plan，故字段名严格一致）。
     *
     * <p>从「引用相等」改为「sigKey 相等」（P0-2）：断言 entry 现在取本会话末态对象，与 plan.fields
     * 里的全局 entry 不是同一引用（checked / fill 值不同）。sigKey 是三份产物共用的字段身份，用它匹配
     * 才能保证字段名 / assertion / listIndex 仍与 Page、Step 严格一致。
     */
    private static CodegenFieldSpec specOf(CodegenFieldPlanner.Plan plan, CodegenEntry entry) {
        if (plan == null || plan.fields == null || entry == null || entry.sigKey == null) return null;
        for (CodegenFieldSpec f : plan.fields) {
            if (f.entry != null && entry.sigKey.equals(f.entry.sigKey)) return f;
        }
        return null;
    }

    /** 单条 action 的断言行；{@code nls[0]} 置位表示该行用到 NLS（决定模板导包）。 */
    private static String assertLine(CodegenTarget target, CodegenFieldSpec f, CodegenEntry entry,
                                     Map<String, String> sessionFillValues, String[] nlsFiles, boolean[] nls) {
        String pageVar = target == null ? "" : target.pageVar;
        // 与 Step 同源取组内序号：自动成组的 index 是用户实际点的那一项，
        // 断言必须指向同一个 get(n)，否则 Step 点第 5 项、Assert 却断言第 1 项。
        String targetExpr = pageVar + "." + f.fieldName + "." + f.listTarget();

        if (f.assertion.endsWith("isChecked()")) {
            // checkbox/radio：断言勾选态（无"字符"语义，布尔足够）。
            // 期望值取【本会话】录制时刻的真实状态（P0-2）：末态 entry 已经过会话内修正，
            // 取消勾选录出来必须是 is(false)，恒 is(true) 会在回放时失败。
            String expected = (entry != null && entry.checked) ? "true" : "false";
            return "        assertThat(" + targetExpr + "isChecked(), is(" + expected + "));";
        }

        // 输入类元素：期望值必须取「本会话录制到的最终输入值」（P0-2）。
        // 优先用会话内 fill 值（多步录制里每步的值不同），再回落 plan 的全局 recordedValue；
        // 两者都没有时才退化为可见性 + TODO。entry.name 是 AX 可访问名（label / placeholder /
        // aria-label），拿它跟 getValue() 比对，断言从第一次运行就是红的（评审 P0-2）。
        if (isValueRole(entry)) {
            String recorded = (sessionFillValues != null && entry.sigKey != null)
                    ? sessionFillValues.get(entry.sigKey) : null;
            if (recorded == null) recorded = f.recordedValue;
            if (recorded != null) {
                return "        assertThat(" + targetExpr + "getValue(), is(\""
                        + CodegenFieldPlanner.escapeJava(recorded) + "\"));";
            }
            return "        assertThat(" + targetExpr + "isVisible(), is(true));"
                    + "  // TODO(review): no recorded value for this input - value assertion omitted";
        }

        String name = entry.name == null ? "" : entry.name;
        boolean hasNls = nlsFiles != null && nlsFiles.length > 0;
        String nlsKey = (hasNls && entry.nlsKey != null && !entry.nlsKey.isEmpty()) ? entry.nlsKey : null;
        if (nlsKey != null) {
            nls[0] = true;
            String getter = isValueRole(entry) ? "getValue()" : "getText()";
            String nlsExpr = nlsValueExpr(nlsKey);
            String stripped = safeVisibleText(nlsKey, nlsFiles);
            if (stripped != null && !name.isBlank() && stripped.equals(name.trim())) {
                return "        assertThat(" + targetExpr + getter + ", equalTo(" + nlsExpr + "));";
            }
            // P1-02：方向必须是「页面文本包含词典文本」。页面常带动态前后缀（如 "Continue (3)"），
            // 词典文本才是稳定的期望子串。反写 assertThat(NLS, containsString(pageText)) 会假绿/假红：
            // 页面文本更短时永远通过、更长时永远失败（评审 P1-02）。
            return "        assertThat(" + targetExpr + getter + ", containsString(" + nlsExpr + "));";
        }
        if (!name.isBlank()) {
            String getter = isValueRole(entry) ? "getValue()" : "getText()";
            return "        assertThat(" + targetExpr + getter + ", is(\""
                    + CodegenFieldPlanner.escapeJava(name) + "\"));";
        }
        // 毫无文本可取：退化为可见性（无 name 的容器/装饰节点）。
        return "        assertThat(" + targetExpr + "isVisible(), is(true));";
    }

    /**
     * 真正有 {@code value} 语义的元素才取 {@code getValue()}（P1-03）：
     * textbox/searchbox/spinbutton，以及<b>原生</b> {@code <select>}（role=combobox/listbox 且 tag=select）。
     *
     * <p>div 自绘下拉（role=combobox/listbox 但 tag≠select）没有 value 属性，{@code getValue()} 恒为空串，
     * 断言从第一次运行就是红的；这类元素必须走 {@code getText()}。
     */
    private static boolean isValueRole(CodegenEntry entry) {
        if (entry == null) return false;
        String r = entry.role == null ? "" : entry.role.toLowerCase(Locale.ROOT);
        if (r.equals("textbox") || r.equals("searchbox") || r.equals("spinbutton")) return true;
        if (r.equals("combobox") || r.equals("listbox")) return "select".equals(entry.tag);
        return false;
    }

    /** 运行期按 key 取可见文本的调用：直接用已 bind 的 {@code NLS} 字段。 */
    private static String nlsValueExpr(String nlsKey) {
        return "NLS.getTextByKey(\"" + CodegenFieldPlanner.escapeJava(nlsKey) + "\")";
    }

    /** 生成 {@code NLSUtils.bind("f1", "f2")} 表达式：bind 一次性抽为生成类的 NLS 字段。 */
    private static String nlsBindExpr(String[] nlsFiles) {
        if (nlsFiles == null || nlsFiles.length == 0) return "NLSUtils.bind()";
        StringBuilder b = new StringBuilder("NLSUtils.bind(");
        for (int i = 0; i < nlsFiles.length; i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(CodegenFieldPlanner.escapeJava(nlsFiles[i])).append('"');
        }
        b.append(")");
        return b.toString();
    }

    /** 生成期解析 key 对应的可见文本，用于决定 {@code equalTo}/{@code containsString}。 */
    private static String safeVisibleText(String nlsKey, String[] nlsFiles) {
        try {
            return NLSUtils.bind(nlsFiles).getTextByKey(nlsKey);
        } catch (Exception e) {
            return null;
        }
    }
}

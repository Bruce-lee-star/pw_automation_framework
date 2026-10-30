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
 *   <li>有可读 name 的普通元素 ⇒ {@code assertThat(page.field.getText(), is("Sign in"))}
 *       （输入框/下拉取 {@code getValue()}）；</li>
 *   <li>checkbox / radio ⇒ {@code assertThat(page.field.isChecked(), is(true))}；</li>
 *   <li>无 name 的元素 ⇒ 退化为可见性 {@code assertThat(..., isVisible(), is(true))}；</li>
 *   <li>命中 NLS key ⇒ 走 {@code NLS.visibleText(key)}，语言无关。</li>
 * </ul>
 * 一组元素（{@code PageElementList}）取 {@code get(0)} 单项断言（与 Step 同源）。
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
        List<Map<String, Object>> methods = new ArrayList<>(sessions.size());
        boolean hasNlsAssert = false;

        for (MultiPageCodegen.SessionUnit s : sessions) {
            for (CodegenTarget page : s.pages) {
                if (seen.add(page.pageClassName)) {
                    imports.add(page.packageName + "." + page.pageClassName);
                }
            }

            List<String> body = new ArrayList<>();
            for (CodegenTarget page : s.pages) {
                body.add("        " + page.pageClassName + " " + page.pageVar
                        + " = PageObjectFactory.getPage(" + page.pageClassName + ".class);");
            }
            if (!s.pages.isEmpty() && !s.asserts.isEmpty()) {
                body.add("");
            }
            for (MultiPageCodegen.PageEntries pe : s.asserts) {
                for (CodegenEntry entry : pe.entries) {
                    CodegenFieldSpec spec = specOf(pe.plan, entry);
                    if (spec == null) continue;
                    boolean[] nls = new boolean[1];
                    body.add(assertLine(pe.target, spec, entry, nlsFiles, nls));
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
        model.put("methods", methods);
        model.put("hasNlsAssert", hasNlsAssert);
        model.put("nlsBindExpr", nlsBindExpr(nlsFiles));
        return CodegenTemplates.render(TEMPLATE, model);
    }

    /** 按 entry 身份取字段规划（Page/Step/Assert 共用同一份 plan，故字段名严格一致）。 */
    private static CodegenFieldSpec specOf(CodegenFieldPlanner.Plan plan, CodegenEntry entry) {
        if (plan == null || plan.fields == null) return null;
        for (CodegenFieldSpec f : plan.fields) {
            if (f.entry == entry) return f;
        }
        return null;
    }

    /** 单条 action 的断言行；{@code nls[0]} 置位表示该行用到 NLS（决定模板导包）。 */
    private static String assertLine(CodegenTarget target, CodegenFieldSpec f, CodegenEntry entry,
                                     String[] nlsFiles, boolean[] nls) {
        String pageVar = target == null ? "" : target.pageVar;
        String targetExpr = pageVar + "." + f.fieldName + (f.isList ? ".get(0)." : ".");

        if (f.assertion.endsWith("isChecked()")) {
            // checkbox/radio：断言勾选态（无"字符"语义，布尔足够）。
            return "        assertThat(" + targetExpr + "isChecked(), is(true));";
        }

        String name = entry.name == null ? "" : entry.name;
        boolean hasNls = nlsFiles != null && nlsFiles.length > 0;
        String nlsKey = (hasNls && entry.nlsKey != null && !entry.nlsKey.isEmpty()) ? entry.nlsKey : null;
        if (nlsKey != null) {
            nls[0] = true;
            String getter = isValueRole(entry.role) ? "getValue()" : "getText()";
            String nlsExpr = nlsValueExpr(nlsKey);
            String stripped = safeVisibleText(nlsKey, nlsFiles);
            if (stripped != null && !name.isBlank() && stripped.equals(name.trim())) {
                return "        assertThat(" + targetExpr + getter + ", equalTo(" + nlsExpr + "));";
            }
            return "        assertThat(" + nlsExpr + ", containsString(" + targetExpr + getter + "));";
        }
        if (!name.isBlank()) {
            String getter = isValueRole(entry.role) ? "getValue()" : "getText()";
            return "        assertThat(" + targetExpr + getter + ", is(\""
                    + CodegenFieldPlanner.escapeJava(name) + "\"));";
        }
        // 毫无文本可取：退化为可见性（无 name 的容器/装饰节点）。
        return "        assertThat(" + targetExpr + "isVisible(), is(true));";
    }

    /** 取值型角色（输入框/下拉）取 {@code getValue()}，其余取 {@code getText()}。 */
    private static boolean isValueRole(String role) {
        String r = role == null ? "" : role.toLowerCase(Locale.ROOT);
        return r.equals("textbox") || r.equals("searchbox") || r.equals("spinbutton")
                || r.equals("combobox") || r.equals("listbox");
    }

    /** 运行期取 key 对应可见文本的调用：直接用已 bind 的 {@code NLS} 字段。 */
    private static String nlsValueExpr(String nlsKey) {
        return "NLS.visibleText(\"" + CodegenFieldPlanner.escapeJava(nlsKey) + "\")";
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
            return NLSUtils.bind(nlsFiles).visibleText(nlsKey);
        } catch (Exception e) {
            return null;
        }
    }
}

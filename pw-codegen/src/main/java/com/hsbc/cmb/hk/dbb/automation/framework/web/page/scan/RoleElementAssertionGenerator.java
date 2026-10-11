package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.util.LinkedHashMap;
import java.util.List;

public final class RoleElementAssertionGenerator {

    private RoleElementAssertionGenerator() {}

    public static final String ASSERTIONS_SUBPACKAGE = ".assertions";

    private static final String ASSERT_THAT_IMPORT = "import static org.hamcrest.MatcherAssert.assertThat;";
    private static final String EQUAL_TO_IMPORT = "import static org.hamcrest.Matchers.equalTo;";
    private static final String PAGE_OBJECT_FACTORY =
            "com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory";

    public static String assertStatement(String pageVar, String field) {
        return "assertThat(" + pageVar + "." + field + ".isVisible(), equalTo(true))";
    }

    public static String assertStatement(String pageVar, String field, RoleEntry e) {
        String op = (e == null) ? null : RoleElementStepGenerator.operationFor(e);
        String value = (e == null) ? null : e.getValue();
        if (op != null && op.startsWith("fill(") && value != null && !value.isEmpty()) {
            return "assertThat(" + pageVar + "." + field + ".inputValue(), equalTo(\""
                    + RoleElementStepGenerator.escapeJava(value) + "\"))";
        }
        return assertStatement(pageVar, field);
    }

    public static String assertionsClassNameOf(String pageClassName) {
        return RolePickerConstants.ASSERT_CLASS_NAME;
    }

    public static String pageVarOf(String pageClassName) {
        if (pageClassName == null || pageClassName.isEmpty()) {
            return "page";
        }
        return Character.toLowerCase(pageClassName.charAt(0)) + pageClassName.substring(1);
    }

    public static String renderClass(String packageName, String pageClassName,
                                     List<List<String>> stepAssertLines) {
        LinkedHashMap<String, String> only = new LinkedHashMap<>();
        only.put(pageClassName, pageVarOf(pageClassName));
        return renderClass(packageName, pageClassName, stepAssertLines, only);
    }

    public static String renderClass(String packageName, String pageClassName,
                                     List<List<String>> stepAssertLines,
                                     java.util.Map<String, String> pageVars) {
        return renderClass(packageName, pageClassName, stepAssertLines, pageVars, null);
    }

    public static String renderClass(String packageName, String pageClassName,
                                     List<List<String>> stepAssertLines,
                                     java.util.Map<String, String> pageVars,
                                     List<Integer> panelNos) {
        String className = assertionsClassNameOf(pageClassName);
        StringBuilder out = new StringBuilder();
        out.append("package ").append(packageName).append(ASSERTIONS_SUBPACKAGE).append(";\n\n");
        out.append("import net.serenitybdd.annotations.Step;\n\n");
        java.util.Map<String, String> vars = (pageVars == null || pageVars.isEmpty())
                ? java.util.Collections.singletonMap(pageClassName, pageVarOf(pageClassName))
                : pageVars;
        for (String cn : vars.keySet()) {
            out.append("import ").append(packageName).append('.').append(cn).append(";\n");
        }
        out.append("import ").append(PAGE_OBJECT_FACTORY).append(";\n\n");
        out.append(ASSERT_THAT_IMPORT).append('\n');
        out.append(EQUAL_TO_IMPORT).append("\n\n");
        out.append("public class ").append(className).append(" {\n\n");

        for (java.util.Map.Entry<String, String> v : vars.entrySet()) {
            out.append("    private final ").append(v.getKey()).append(' ').append(v.getValue())
                    .append(" = PageObjectFactory.getPage(").append(v.getKey()).append(".class);\n\n");
        }
        if (stepAssertLines != null && !stepAssertLines.isEmpty()) {
            for (int i = 0; i < stepAssertLines.size(); i++) {
                List<String> lines = stepAssertLines.get(i);
                out.append("    @Step\n");
                out.append("    public void assertStep").append(i + 1).append("() {\n");
                if (lines != null && !lines.isEmpty()) {
                    for (String line : lines) {
                        out.append("        ").append(line).append(";\n");
                    }
                }
                out.append("    }\n\n");
            }
        }
        out.append("}\n");
        return out.toString();
    }
}

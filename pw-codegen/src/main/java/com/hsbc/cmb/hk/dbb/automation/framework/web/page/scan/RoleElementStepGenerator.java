package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

public final class RoleElementStepGenerator {

    private static java.util.Map<String, java.util.List<Integer>> roundNosOfStep(ViewNotes notes, String pc, int si) {
        if (notes == null || notes.stepRoundNosByPage == null || pc == null) return null;
        java.util.List<java.util.Map<String, java.util.List<Integer>>> list = notes.stepRoundNosByPage.get(pc);
        if (list == null || si < 0 || si >= list.size()) return null;
        return list.get(si);
    }

    private static java.util.List<Integer> nosInRound(RoleEntry e,
                                                      java.util.Map<String, java.util.List<Integer>> roundNos) {
        if (e == null) return null;
        if (roundNos == null || roundNos.isEmpty()) return pickNosOf(e);
        String k = RoleElementPageGenerator.groupIdentityOf(e);
        if (k != null && !k.isEmpty()) {
            java.util.List<Integer> v = roundNos.get(k);
            if (v != null && !v.isEmpty()) return v;
        }

        return pickNosOf(e);
    }

    private static java.util.List<Integer> pickNosOf(RoleEntry e) {
        if (e == null) return java.util.Collections.emptyList();
        java.util.List<Integer> live = e.getPickNos();
        if (live != null && !live.isEmpty()) return live;
        java.util.List<Integer> committed = e.getCommittedPickNos();
        return (committed != null) ? committed : java.util.Collections.emptyList();
    }

    private RoleElementStepGenerator() {}

    public static final class ViewNotes {

        public final LinkedHashMap<String, List<java.util.Map<String, java.util.List<Integer>>>> stepRoundNosByPage;

        public ViewNotes(LinkedHashMap<String, List<java.util.Map<String, java.util.List<Integer>>>> stepRoundNosByPage) {
            this.stepRoundNosByPage = stepRoundNosByPage;
        }
    }

    static String operationFor(RoleEntry e) {

        if (e.isRightClick()) {
            return "rightClick()";
        }

        if (e.isFileInput()) {
            return "setInputFiles(\"<path-to-upload-file>\")";
        }

        if (e.isRoleStrategy()) {
            String chkR = (e.getRole() == null ? "" : e.getRole()).toLowerCase();
            if ("checkbox".equals(chkR) || "radio".equals(chkR)) {
                if (e.getSetCheckedTarget() != null) {
                    return "setChecked(" + e.getSetCheckedTarget() + ")";
                }
                if (e.getChecked() != null) {
                    return e.getChecked() ? "check()" : "uncheck()";
                }
                return "check()";
            }
        }

        if (e.isHover()) {
            return "hover()";
        }

        if (e.getDragDstKey() != null) {
            return "__DRAGTO__";
        }

        if (e.isDblClick()) {
            return "doubleClick()";
        }
        if (e.isRoleStrategy()) {
            String r = (e.getRole() == null ? "" : e.getRole()).toLowerCase();
            switch (r) {
                case "textbox":
                case "searchbox":
                case "spinbutton":

                    return "fill(\"" + escapeJava(e.getValue() == null ? "" : e.getValue()) + "\")";
                case "checkbox":
                case "radio":

                    if (e.getSetCheckedTarget() != null) {
                        return "setChecked(" + e.getSetCheckedTarget() + ")";
                    }
                    if (e.getChecked() != null) {
                        return e.getChecked() ? "check()" : "uncheck()";
                    }
                    return "check()";
                case "combobox":
                case "listbox":

                    if (e.isSelect() && e.getOptionText() != null) {
                        return "selectByVisibleText(\"" + escapeJava(e.getOptionText()) + "\")";
                    }
                    return "click()";
                default:
                    return "click()";
            }
        }

        if (RolePickerConstants.STRATEGY_PLACEHOLDER.equals(e.getStrategy())) {

            return "fill(\"" + escapeJava(e.getValue() == null ? "" : e.getValue()) + "\")";
        }
        return "click()";
    }

    static String escapeJava(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    public static String generate(List<List<RoleEntry>> steps, List<RoleEntry> allEntries,
                                  String packageName, String pageClassName, String stepClassName) {
        List<RoleElementPageGenerator.GeneratedField> specs =
                RoleElementPageGenerator.assignFields(allEntries);
        Map<String, String> keyToField = new LinkedHashMap<>();
        for (RoleElementPageGenerator.GeneratedField s : specs) {
            keyToField.put(RoleElementPageGenerator.locatorKey(s.entry), s.fieldName);
        }

        String pageVar = decapitalize(pageClassName);
        String stepPkg = packageName + ".steps";

        StringBuilder methods = new StringBuilder();
        int[] npIdx = {0};
        if (steps == null || steps.isEmpty()) {

        } else {
            int stepIdx = 0;
            for (List<RoleEntry> rawStep : steps) {
                stepIdx++;

                List<java.util.Map.Entry<Integer, RoleEntry>> seqEntries = new java.util.ArrayList<>();
                for (RoleEntry e : rawStep) {
                    if (e == null) continue;
                    java.util.List<Integer> nos = pickNosOf(e);
                    if (nos != null && !nos.isEmpty()) {

                        for (int no : nos) {
                            if (no > 0) {
                                seqEntries.add(java.util.Map.entry(no, e));
                            }
                        }
                    } else {

                        int seq = e.getSeq();
                        seqEntries.add(java.util.Map.entry(seq > 0 ? seq : Integer.MAX_VALUE, e));
                    }
                }

                seqEntries.sort(java.util.Comparator.comparingInt(java.util.Map.Entry<Integer, RoleEntry>::getKey));

                List<RoleEntry> step = new java.util.ArrayList<>();
                for (java.util.Map.Entry<Integer, RoleEntry> entry : seqEntries) {
                    step.add(entry.getValue());
                }
                boolean sawPopup = false;
                methods.append("    @Step\n");
                methods.append("    public void step").append(stepIdx).append("() {\n");
                if (step == null || step.isEmpty()) {

                } else {
                    List<String> lastFp = null;
                    for (RoleEntry e : step) {
                        String field = keyToField.get(RoleElementPageGenerator.locatorKey(e));
                        if (field == null) continue;

                        List<String> fp = e.getFramePath();
                        if (!sameFramePath( fp, lastFp)) {

                            int common = commonPrefixLen(fp, lastFp);
                            boolean deepen = lastFp != null && fp != null && !fp.isEmpty()
                                    && common == lastFp.size() && fp.size() > lastFp.size();
                            boolean backToMain = (fp == null || fp.isEmpty());
                            if (lastFp != null && !deepen && backToMain) {

                                methods.append("        ").append(pageVar)
                                        .append(".switchToDefaultContent();\n");
                            }
                            if (fp != null && !fp.isEmpty()) {
                                int start = deepen ? common : 0;
                                for (int i = start; i < fp.size(); i++) {
                                    String fsel = fp.get(i);
                                    String name = frameNameOf(fsel);
                                    if (name != null) {
                                        methods.append("        ").append(pageVar).append(".switchToFrame(\"")
                                                .append(escapeJavaString(name)).append("\");\n");
                                    } else {
                                        methods.append("        ").append(pageVar).append(".switchToFrame(\"")
                                                .append(escapeJavaString(fsel)).append("\");\n");
                                    }
                                }
                            }
                            lastFp = (fp == null) ? null : new ArrayList<>(fp);
                        }

                        List<String> shadowPath = e.getShadowPath();
                        if (shadowPath != null && !shadowPath.isEmpty()) {
                            for (String host : shadowPath) {
                                methods.append("        ").append(pageVar).append(".switchToShadow(\"")
                                        .append(escapeJavaString(host)).append("\");\n");
                            }
                        }

                        String target = pageVar + "." + field;

                        int __ix1 = (e.getIndex() >= 0) ? e.getIndex() : RolePickerPickParser.parseIndexFromSig(e.getSigKey());
                        if (__ix1 >= 0) {
                            target += e.isList() ? ".get(" + __ix1 + ")" : ".nth(" + __ix1 + ")";
                        }

                        String op = target + "." + operationFor(e);

                        int __repeat = 1;
                        java.util.List<Integer> __pnos = pickNosOf(e);
                        if (__pnos != null && __pnos.size() > 1) __repeat = __pnos.size();

                        boolean unresolvedDrag = false;
                        if (op.endsWith(".__DRAGTO__")) {
                            String dstField = keyToField.get(e.getDragDstKey());

                            String dstRef = (dstField != null) ? pageVar + "." + dstField : null;
                            unresolvedDrag = (dstRef == null);
                            __repeat = 1;
                            op = dragToStatement(target, dstRef);
                        } else if (e.getPressKey() != null) {

                            op += ";\n        " + target + ".press(\"" + escapeJava(e.getPressKey()) + "\")";
                        }
                        if (e.isDialog() && !unresolvedDrag) {
                            String dlgType = (e.getDialogType() == null) ? "alert" : e.getDialogType();
                            String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert" : "dismissAlert";

                            op = pageVar + "." + dlgMethod + "(() -> " + lambdaBody(op) + ")\n        ";
                        }
                        if (e.isDownload() && !unresolvedDrag) {

                            if (e.isPopup()) {
                                methods.append("        ").append(pageVar).append(".waitForDownload(() -> {\n")
                                        .append("            ").append(pageVar).append(".waitForNewPage(() ->\n")
                                        .append("                    ").append(lambdaBody(op)).append(");\n")
                                        .append("        });\n");
                            } else {
                                methods.append("        ").append(pageVar).append(".waitForDownload(() ->\n")
                                        .append("                ").append(lambdaBody(op)).append(");\n");
                            }
                        } else if (e.isPopup() && !unresolvedDrag) {

                            String npVar = nextNewPageVar(npIdx);
                            sawPopup = true;
                            methods.append("        Page ").append(npVar).append(" = ").append(pageVar)
                                    .append(".waitForNewPage(() ->\n")
                                    .append("                ").append(lambdaBody(op)).append(");\n");
                        } else if (!unresolvedDrag) {
                            for (int __r = 0; __r < __repeat; __r++) {
                                methods.append("        ").append(op).append(";\n");
                            }
                        }

                        if (shadowPath != null && !shadowPath.isEmpty()) {
                            methods.append("        ").append(pageVar)
                                    .append(".switchToDefaultShadow();\n");
                        }
                    }
                }
                if (sawPopup) {

                    methods.append("        ").append(pageVar)
                            .append(".closeCurrentPage();\n");
                }
                methods.append("    }\n\n");
            }
        }

        return "package " + stepPkg + ";\n\n"
                + "import net.serenitybdd.annotations.Step;\n"
                + "import com.microsoft.playwright.Page;\n\n"
                + "import " + packageName + "." + pageClassName + ";\n"
                + "import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;\n\n"
                + "public class " + stepClassName + " {\n\n"
                + "    private final " + pageClassName + " " + pageVar
                + " = PageObjectFactory.getPage(" + pageClassName + ".class);\n\n"
                + methods
                + "}\n";
    }

    public static String generate(List<List<RoleEntry>> steps, List<RoleEntry> allEntries,
                                  String packageName, String pageClassName) {
        return generate(steps, allEntries, packageName, pageClassName, packageName);
    }

    public static String generateMulti(LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage,
                                        LinkedHashMap<String, List<RoleEntry>> entriesByPage,
                                        LinkedHashMap<String, List<String>> opsByPage,
                                        String packageName, String stepClassName) {
        if (opsByPage == null) opsByPage = new LinkedHashMap<>();

        LinkedHashSet<String> allPages = new LinkedHashSet<>();
        allPages.addAll(entriesByPage.keySet());
        allPages.addAll(opsByPage.keySet());
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            allPages.add(en.getKey());
            for (List<RoleEntry> step : en.getValue()) {
                if (step == null) continue;
                for (RoleEntry e : step) {
                    String pc = (e.getPageClass() == null || e.getPageClass().isEmpty())
                            ? en.getKey() : e.getPageClass();
                    allPages.add(pc);
                }
            }
        }

        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            en.setValue(mergeCloseOnlySteps(en.getValue()));
        }

        Map<String, Map<String, String>> pageFields = new LinkedHashMap<>();
        Map<String, String> pageVar = new LinkedHashMap<>();
        Map<String, String> pageVarClass = new LinkedHashMap<>();
        StringBuilder fields = new StringBuilder();
        Set<String> usedVars = new HashSet<>();

        LinkedHashSet<String> allInstances = new LinkedHashSet<>();
        for (String className : allPages) {
            List<RoleEntry> pes = entriesByPage.get(className);
            if (pes != null) for (RoleEntry e : pes) allInstances.add(className + "#" + e.getPageInstanceId());
            else allInstances.add(className + "#1");
        }
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            for (List<RoleEntry> step : en.getValue()) {
                if (step == null) continue;
                for (RoleEntry e : step) {
                    String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? en.getKey() : e.getPageClass();
                    allInstances.add(pc + "#" + e.getPageInstanceId());
                }
            }
        }
        for (String instKey : allInstances) {
            int hash = instKey.lastIndexOf('#');
            String className = instKey.substring(0, hash);
            int instId = Integer.parseInt(instKey.substring(hash + 1));

            List<RoleEntry> allPageEntries = entriesByPage.get(className);
            List<RoleEntry> pageEntries = new ArrayList<>();
            if (allPageEntries != null) {
                for (RoleEntry e : allPageEntries) {
                    if (e.getPageInstanceId() == instId) pageEntries.add(e);
                }
            }
            List<RoleElementPageGenerator.GeneratedField> specs = RoleElementPageGenerator.assignFields(pageEntries);
            Map<String, String> keyToField = new LinkedHashMap<>();
            for (RoleElementPageGenerator.GeneratedField f : specs) {
                keyToField.put(RoleElementPageGenerator.locatorKey(f.entry), f.fieldName);
            }
            pageFields.put(instKey, keyToField);

            String base = decapitalize(className) + (instId > 1 ? instId : "");
            String uniqueVar = base;
            int v = 2;
            while (usedVars.contains(uniqueVar)) uniqueVar = base + (v++);
            usedVars.add(uniqueVar);
            pageVar.put(instKey, uniqueVar);
            pageVarClass.put(instKey, className);

            fields.append("    private final ").append(className).append(" ").append(uniqueVar)
                    .append(" = PageObjectFactory.getPage(").append(className).append(".class);\n\n");
        }

        StringBuilder methods = new StringBuilder();
        int[] npIdx = {0};
        int stepIdx = 0;
        boolean any = false;
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            String stepPageClass = en.getKey();
            for (List<RoleEntry> rawStep : en.getValue()) {
                List<List<RoleEntry>> __steps = en.getValue();
                int __stepPos = __steps.indexOf(rawStep);
                stepIdx++;
                any = true;

                List<RoleEntry> step = new java.util.ArrayList<>(rawStep);
                final List<RoleEntry> src = rawStep;

                step.sort(java.util.Comparator.comparingInt((RoleEntry e) -> {
                    if (e == null) return Integer.MAX_VALUE;
                    java.util.List<Integer> nos = pickNosOf(e);
                    if (nos != null && !nos.isEmpty() && nos.get(0) != null && nos.get(0) > 0) {
                        return nos.get(0);
                    }
                    int s = e.getSeq();
                    return (s > 0) ? s : Integer.MAX_VALUE;
                }).thenComparingInt(e -> src.indexOf(e)));
                StringBuilder m = new StringBuilder();
                m.append("    @Step\n");
                m.append("    public void step").append(stepIdx).append("() {\n");
                boolean sawPopup = false;
                boolean renderedCloseOp = false;
                String popupTargetVar = null;
                String activeVar = null;

                Map<String, Integer> popupLastIdx = new LinkedHashMap<>();
                Map<String, String> popupInstUrl = new LinkedHashMap<>();
                if (step == null || step.isEmpty()) {

                } else {
                    List<String> lastFp = null;
                    int __idx = -1;
                    for (RoleEntry e : step) {
                        __idx++;
                        String pc = (e.getPageClass() == null || e.getPageClass().isEmpty())
                                ? stepPageClass : e.getPageClass();

                        String instKey = pc + "#" + e.getPageInstanceId();
                        String var = pageVar.get(instKey);
                        if (var == null) var = pageVar.get(pc + "#1");
                        if (var == null) var = pageVar.get(stepPageClass + "#" + e.getPageInstanceId());
                        if (var == null) var = pageVar.get(stepPageClass + "#1");
                        if (var == null) continue;

                        if (activeVar == null) activeVar = var;
                        if (popupTargetVar != null && activeVar.equals(popupTargetVar) && !var.equals(popupTargetVar)) {

                            m.append("        ").append(popupTargetVar)
                                    .append(".closeCurrentPage();\n");
                            renderedCloseOp = true;
                            activeVar = null;
                            popupTargetVar = null;
                        }
                        if (e.isCloseOp()) {

                            String cv = (popupTargetVar != null) ? popupTargetVar : var;
                            m.append("        ").append(cv)
                                    .append(".closeCurrentPage();\n");
                            renderedCloseOp = true;
                            continue;
                        }
                        Map<String, String> kf = pageFields.get(instKey);
                        if (kf == null) kf = pageFields.get(pc + "#1");
                        String field = (kf == null) ? null : kf.get(RoleElementPageGenerator.locatorKey(e));
                        if (field == null) continue;

                        List<String> fp = e.getFramePath();
                        if (!sameFramePath(fp, lastFp)) {

                            int common = commonPrefixLen(fp, lastFp);
                            boolean deepen = lastFp != null && fp != null && !fp.isEmpty()
                                    && common == lastFp.size() && fp.size() > lastFp.size();
                            boolean backToMain = (fp == null || fp.isEmpty());
                            if (lastFp != null && !deepen && backToMain) {

                                m.append("        ").append(var)
                                        .append(".switchToDefaultContent();\n");
                            }
                            if (fp != null && !fp.isEmpty()) {
                                int start = deepen ? common : 0;
                                for (int i = start; i < fp.size(); i++) {
                                    String fsel = fp.get(i);
                                    String name = frameNameOf(fsel);
                                    if (name != null) {

                                        m.append("        ").append(var).append(".switchToFrame(\"")
                                                .append(escapeJavaString(name)).append("\");\n");
                                    } else {

                                        m.append("        ").append(var).append(".switchToFrame(\"")
                                                .append(escapeJavaString(fsel)).append("\");\n");
                                    }
                                }
                            }
                            lastFp = (fp == null) ? null : new ArrayList<>(fp);
                        }

                        List<String> shadowPath = e.getShadowPath();
                        if (shadowPath != null && !shadowPath.isEmpty()) {
                            for (String host : shadowPath) {
                                m.append("        ").append(var).append(".switchToShadow(\"")
                                        .append(escapeJavaString(host)).append("\");\n");
                            }
                        }
                        String target = var + "." + field;

                        int __ix2 = (e.getIndex() >= 0) ? e.getIndex() : RolePickerPickParser.parseIndexFromSig(e.getSigKey());
                        if (e.isList()) { if (__ix2 >= 0) target += ".get(" + __ix2 + ")"; }
                        else if (e.getCount() > 1 && __ix2 >= 0) target += ".nth(" + __ix2 + ")";

                        String op = target + "." + operationFor(e);

                        int __repeat = 1;
                        java.util.List<Integer> __pnos = pickNosOf(e);
                        if (__pnos != null && __pnos.size() > 1) __repeat = __pnos.size();

                        boolean unresolvedDrag = false;
                        if (op.endsWith(".__DRAGTO__")) {
                            String dstRef = findFieldRefAcrossPages(e.getDragDstKey(), pageFields, pageVar);

                            unresolvedDrag = (dstRef == null);
                            __repeat = 1;
                            op = dragToStatement(target, dstRef);
                        } else if (e.getPressKey() != null) {

                            op += ";\n        " + target + ".press(\"" + escapeJava(e.getPressKey()) + "\")";
                        }
                        if (e.isDialog() && !unresolvedDrag) {
                            String dlgType = (e.getDialogType() == null) ? "alert" : e.getDialogType();
                            String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert" : "dismissAlert";

                            op = var + "." + dlgMethod + "(() -> " + lambdaBody(op) + ")\n        ";
                        }
                        if (e.isDownload() && !unresolvedDrag) {
                            if (e.isPopup()) {
                            sawPopup = true; if (popupTargetVar == null) popupTargetVar = var;
                            m.append("        ").append(var).append(".waitForDownload(() -> {\n")
                                    .append("            ").append(var).append(".waitForNewPage(() ->\n")
                                    .append("                    ").append(lambdaBody(op)).append(");\n")
                                    .append("        });\n");
                            } else {
                                m.append("        ").append(var).append(".waitForDownload(() ->\n")
                                        .append("                ").append(lambdaBody(op)).append(");\n");
                            }
                        } else if (e.isPopup() && !unresolvedDrag) {

                            sawPopup = true;
                            lastFp = null;
                            String npVar = nextNewPageVar(npIdx);
                            String popupTarget = inferPopupTargetVar(step, var, pageVar, opsByPage);
                            if (popupTarget != null) {
                                m.append("        Page ").append(npVar).append(" = ").append(popupTarget)
                                        .append(".waitForNewPage(() ->\n")
                                        .append("                ").append(lambdaBody(op)).append(");\n");
                                if (popupTargetVar == null) popupTargetVar = popupTarget;
                                activeVar = popupTarget;
                            } else {
                                m.append("        Page ").append(npVar).append(" = ").append(var)
                                        .append(".waitForNewPage(() ->\n")
                                        .append("                ").append(lambdaBody(op)).append(");\n");
                                if (popupTargetVar == null) popupTargetVar = var;
                            }
                        } else if (!unresolvedDrag) {
                            for (int __r = 0; __r < __repeat; __r++) {
                                m.append("        ").append(op).append(";\n");
                            }
                        }

                        if (shadowPath != null && !shadowPath.isEmpty()) {
                            m.append("        ").append(var)
                                    .append(".switchToDefaultShadow();\n");
                        }

                        if (popupTargetVar != null && activeVar != null
                                && activeVar.equals(popupTargetVar)) {
                            popupLastIdx.put(popupTargetVar, __idx);
                            String u = e.getUrl();
                            if (u != null && !u.isEmpty()) popupInstUrl.put(popupTargetVar, u);
                        }
                    }
                }

                if (sawPopup && popupTargetVar != null && !renderedCloseOp
                        && !popupLastIdx.isEmpty()) {

                    Set<String> laterUrls = new LinkedHashSet<>();
                    for (int __j = __stepPos + 1; __j < __steps.size(); __j++) {
                        List<RoleEntry> other = __steps.get(__j);
                        if (other == null) continue;
                        for (RoleEntry re : other) {
                            String ru = re.getUrl();
                            if (ru != null && !ru.isEmpty()) laterUrls.add(ru);
                        }
                    }

                    List<Map.Entry<String, Integer>> pend = new ArrayList<>(popupLastIdx.entrySet());
                    pend.sort((a, b) -> b.getValue().compareTo(a.getValue()));
                    for (Map.Entry<String, Integer> entry : pend) {
                        String pv = entry.getKey();
                        String pu = popupInstUrl.get(pv);

                        if (pu != null && !pu.isEmpty() && laterUrls.contains(pu)) continue;
                        int insertAt = entry.getValue() + 1;
                        String line = "        " + pv + ".closeCurrentPage();\n";
                        if (insertAt >= step.size()) m.append(line);
                        else {

                            m.append(line);
                        }
                    }
                } else if (sawPopup && popupTargetVar != null && !renderedCloseOp) {

                    m.append("        ").append(popupTargetVar)
                            .append(".closeCurrentPage();\n");
                }
                m.append("    }\n\n");
                methods.append(m);
            }
        }
        if (!any) {

        }

        StringBuilder imports = new StringBuilder("import net.serenitybdd.annotations.Step;\n"
                + "import com.microsoft.playwright.Page;\n\n");
        for (String className : allPages) {
            imports.append("import ").append(packageName).append(".").append(className).append(";\n");
        }
        imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;\n\n");
        return "package " + packageName + ".steps;\n\n"
                + imports
                + "public class " + stepClassName + " {\n\n"
                + fields
                + methods
                + "}\n";
    }

    private static boolean sameFramePath(List<String> a, List<String> b) {
        if (a == null) a = java.util.Collections.emptyList();
        if (b == null) b = java.util.Collections.emptyList();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i), y = b.get(i);
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    private static int commonPrefixLen(List<String> a, List<String> b) {
        if (a == null) a = java.util.Collections.emptyList();
        if (b == null) b = java.util.Collections.emptyList();
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n) {
            String x = a.get(i), y = b.get(i);
            if (x == null ? y != null : !x.equals(y)) break;
            i++;
        }
        return i;
    }

    private static String escapeJavaString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String frameNameOf(String fsel) {
        if (fsel == null) return null;
        Matcher m;
        m = FRAME_NAME.matcher(fsel);
        if (m.find()) return m.group(1);
        m = FRAME_ID.matcher(fsel);
        if (m.find()) return m.group(1);
        return null;
    }
    private static final java.util.regex.Pattern FRAME_NAME =
            java.util.regex.Pattern.compile("(?:iframe|frame)\\s*\\[\\s*name\\s*=\\s*[\"']([^\"']+)[\"']\\s*\\]");
    private static final java.util.regex.Pattern FRAME_ID =
            java.util.regex.Pattern.compile("#([\\w-]+)");

    static String dragToStatement(String target, String dstRef) {
        if (dstRef == null) {
            return "";
        }
        return target + ".dragTo(" + dstRef + ")";
    }

    static String lambdaBody(String op) {
        if (op == null) {
            return "";
        }
        if (op.indexOf(';') < 0) {
            return op;
        }
        StringBuilder sb = new StringBuilder("{\n");
        for (String line : op.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty()) {
                sb.append("            ").append(t).append('\n');
            }
        }
        String body = sb.toString().stripTrailing();
        if (!body.endsWith(";")) {
            body = body + ";";
        }
        return body + "\n        }";
    }

    private static String findFieldRefAcrossPages(String dragDstKey,
                                                  Map<String, Map<String, String>> pageFields,
                                                  Map<String, String> pageVar) {
        if (dragDstKey == null) return null;
        for (Map.Entry<String, Map<String, String>> en : pageFields.entrySet()) {
            String field = en.getValue().get(dragDstKey);
            if (field != null) {
                String pv = pageVar.get(en.getKey());
                return (pv != null ? pv : "page") + "." + field;
            }
        }
        return null;
    }

    public static LinkedHashMap<String, String> generatePerPage(
            LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage,
            LinkedHashMap<String, List<RoleEntry>> entriesByPage,
            LinkedHashMap<String, List<String>> opsByPage,
            String packageName, String stepClassName) {
        return generatePerPage(stepsByPage, entriesByPage, opsByPage, null, packageName, stepClassName);
    }

    public static LinkedHashMap<String, String> generatePerPage(
            LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage,
            LinkedHashMap<String, List<RoleEntry>> entriesByPage,
            LinkedHashMap<String, List<String>> opsByPage,
            ViewNotes notes,
            String packageName, String stepClassName) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (opsByPage == null) opsByPage = new LinkedHashMap<>();

        LinkedHashSet<String> allPages = new LinkedHashSet<>();
        allPages.addAll(entriesByPage.keySet());
        allPages.addAll(opsByPage.keySet());
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            allPages.add(en.getKey());
            for (List<RoleEntry> step : en.getValue()) {
                if (step == null) continue;
                for (RoleEntry e : step) {
                    String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? en.getKey() : e.getPageClass();
                    allPages.add(pc);
                }
            }
        }
        if (allPages.isEmpty()) return out;


        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
                en.setValue(mergeCloseOnlySteps(en.getValue()));
        }

        Map<String, Map<String, String>> pageFields = new LinkedHashMap<>();
        Map<String, String> pageVar = new LinkedHashMap<>();
        StringBuilder fields = new StringBuilder();
        Set<String> usedVars = new HashSet<>();
        for (String className : allPages) {
            List<RoleEntry> pageEntries = entriesByPage.get(className);
            if (pageEntries == null) pageEntries = new ArrayList<>();
            List<RoleElementPageGenerator.GeneratedField> specs = RoleElementPageGenerator.assignFields(pageEntries);
            Map<String, String> keyToField = new LinkedHashMap<>();
            for (RoleElementPageGenerator.GeneratedField s : specs) {
                keyToField.put(RoleElementPageGenerator.locatorKey(s.entry), s.fieldName);
            }
            pageFields.put(className, keyToField);
            String base = decapitalize(className);
            String uniqueVar = base;
            int v = 2;
            while (usedVars.contains(uniqueVar)) uniqueVar = base + (v++);
            usedVars.add(uniqueVar);
            pageVar.put(className, uniqueVar);
            fields.append("    private final ").append(className).append(" ").append(uniqueVar)
                    .append(" = PageObjectFactory.getPage(").append(className).append(".class);\n\n");
        }

        StringBuilder imports = new StringBuilder("import net.serenitybdd.annotations.Step;\n"
                + "import com.microsoft.playwright.Page;\n\n");
        for (String className : allPages) {
            imports.append("import ").append(packageName).append(".").append(className).append(";\n");
        }
        imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;\n\n");

        Set<String> inlinedTargetVars = new LinkedHashSet<>();
        for (List<List<RoleEntry>> steps : stepsByPage.values()) {
            if (steps == null) continue;
            for (List<RoleEntry> st : steps) {
                if (st == null) continue;
                for (RoleEntry e : st) {
                    if (!e.isPopup()) continue;
                    String pc0 = e.getPageClass();
                    String openVar = (pc0 == null || pc0.isEmpty()) ? "" : pageVar.getOrDefault(pc0, "");
                    String t = inferPopupTargetVar(st, openVar, pageVar, opsByPage);
                    if (t != null) inlinedTargetVars.add(t);
                }
            }
        }

        java.util.function.Function<String, String> stepClassNameOf = (pc) -> {
            return pc.endsWith("Page") ? pc.substring(0, pc.length() - 4) + "Steps" : pc + "Steps";
        };

        String mergedStepClassName = RolePickerConstants.STEP_CLASS_NAME;
        String clsHeader = "package " + packageName + ".steps;\n\n" + imports
                + "public class " + mergedStepClassName + " {\n\n" + fields;
        StringBuilder methods = new StringBuilder();
        int[] npIdx = {0};
        int stepIdx = 0;
        boolean any = false;
        for (String pc : allPages) {

            if (stepsByPage.containsKey(pc)) {
                List<List<RoleEntry>> pageSteps = stepsByPage.get(pc);
                for (int si = 0; si < pageSteps.size(); si++) {
                    List<RoleEntry> stepRaw = pageSteps.get(si);

                    List<java.util.Map.Entry<Integer, RoleEntry>> seqEntries = new java.util.ArrayList<>();
                    for (RoleEntry e : stepRaw) {
                        if (e == null) continue;
                        if (e.isCloseOp()) {

                            int seq = e.getSeq();
                            seqEntries.add(java.util.Map.entry(seq > 0 ? seq : Integer.MAX_VALUE, e));
                            continue;
                        }

                        java.util.Map<String, java.util.List<Integer>> curRoundNos =
                                roundNosOfStep(notes, pc, si);
                        java.util.List<Integer> nos = nosInRound(e, curRoundNos);
                        if (nos != null && !nos.isEmpty()) {

                            for (int no : nos) {
                                if (no > 0) {
                                    seqEntries.add(java.util.Map.entry(no, e));
                                }
                            }
                        } else {

                            int seq = e.getSeq();
                            seqEntries.add(java.util.Map.entry(seq > 0 ? seq : Integer.MAX_VALUE, e));
                        }
                    }

                    seqEntries.sort(java.util.Comparator.comparingInt(java.util.Map.Entry<Integer, RoleEntry>::getKey));

                    List<RoleEntry> step = new java.util.ArrayList<>();
                    for (java.util.Map.Entry<Integer, RoleEntry> entry : seqEntries) {
                        step.add(entry.getValue());
                    }
                    stepIdx++;
                    any = true;

                    methods.append("    @Step\n");
                    methods.append("    public void step").append(stepIdx).append("() {\n");
                    boolean sawPopup = false;
                    boolean renderedCloseOp = false;
                    List<String> lastFp = null;
                    String popupTargetVar = null;
                    String activeVar = null;
                    if (step == null || step.isEmpty()) {

                    } else {
                        for (RoleEntry e : step) {
                            String epc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? pc : e.getPageClass();
                            String var = pageVar.get(epc);
                            if (var == null) var = pageVar.get(pc);
                            if (var == null) continue;

                            if (activeVar == null) {
                                activeVar = var;
                            } else if (var != null && !activeVar.equals(var)) {
                                if (popupTargetVar != null && activeVar.equals(popupTargetVar) && !var.equals(popupTargetVar)) {
                                    methods.append("        ").append(popupTargetVar)
                                            .append(".closeCurrentPage();\n");
                                    renderedCloseOp = true;
                                    activeVar = null;
                                    popupTargetVar = null;
                                }
                                activeVar = var;
                            }
                            if (e.isCloseOp()) {

                                String cv = (popupTargetVar != null) ? popupTargetVar : var;
                                methods.append("        ").append(cv)
                                        .append(".closeCurrentPage();\n");
                                renderedCloseOp = true;
                                lastFp = null;
                                continue;
                            }
                            Map<String, String> kf = pageFields.get(epc);
                            String field = (kf == null) ? null : kf.get(RoleElementPageGenerator.locatorKey(e));
                            if (field == null) continue;

                            List<String> fp = e.getFramePath();
                            if (!sameFramePath(fp, lastFp)) {

                                boolean backToMain = (fp == null || fp.isEmpty());
                                if (lastFp != null && backToMain) {
                                    methods.append("        ").append(var)
                                            .append(".switchToDefaultContent();\n");
                                }
                                if (fp != null) {
                                    for (String fsel : fp) {
                                        String name = frameNameOf(fsel);
                                        if (name != null) {
                                            methods.append("        ").append(var).append(".switchToFrame(\"")
                                                    .append(escapeJavaString(name)).append("\");\n");
                                        } else {
                                            methods.append("        ").append(var).append(".switchToFrame(\"")
                                                    .append(escapeJavaString(fsel)).append("\");\n");
                                        }
                                    }
                                }
                                lastFp = (fp == null) ? null : new ArrayList<>(fp);
                            }
                            String target = var + "." + field;

                            int __ix = (e.getIndex() >= 0) ? e.getIndex()
                                    : RolePickerPickParser.parseIndexFromSig(e.getSigKey());
                            if (__ix >= 0) {
                                if (e.isList()) target += ".get(" + __ix + ")";
                                else if (e.getCount() > 1) target += ".nth(" + __ix + ")";
                            }

                            String op = target + "." + operationFor(e);

                            if (op.endsWith(".__DRAGTO__")) {
                                String dstRef = findFieldRefAcrossPages(e.getDragDstKey(), pageFields, pageVar);
                                op = target + ".dragTo(" + dstRef + ")";
                            } else if (e.getPressKey() != null) {

                                op += ";\n        " + target + ".press(\"" + escapeJava(e.getPressKey()) + "\")";
                            }
                            if (e.isDialog()) {
                                String dlgType = (e.getDialogType() == null) ? "alert" : e.getDialogType();
                                String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert" : "dismissAlert";

                                op = var + "." + dlgMethod + "(() -> " + op + ")\n        ";
                            }
                            if (e.isDownload()) {
                                if (e.isPopup()) {
                                    sawPopup = true; if (popupTargetVar == null) popupTargetVar = var;
                                    methods.append("        ").append(var).append(".waitForDownload(() -> {\n")
                                            .append("            ").append(var).append(".waitForNewPage(() ->\n")
                                            .append("                    ").append(op).append(");\n")
                                            .append("        });\n");
                                } else {
                                    methods.append("        ").append(var).append(".waitForDownload(() ->\n")
                                            .append("                ").append(op).append(");\n");
                                }
                            } else if (e.isPopup()) {
                                sawPopup = true;
                                lastFp = null;
                                String npVar = nextNewPageVar(npIdx);

                                String popupTarget = inferPopupTargetVar(step, var, pageVar, opsByPage);
                                if (popupTarget != null) {
                                    methods.append("        Page ").append(npVar).append(" = ").append(popupTarget)
                                            .append(".waitForNewPage(() ->\n")
                                            .append("                ").append(op).append(");\n");
                                    if (popupTargetVar == null) popupTargetVar = popupTarget;
                                } else {
                                    methods.append("        Page ").append(npVar).append(" = ").append(var)
                                            .append(".waitForNewPage(() ->\n")
                                            .append("                ").append(op).append(");\n");
                                    if (popupTargetVar == null) popupTargetVar = var;
                                }
                            } else {
                                methods.append("        ").append(op).append(";\n");
                            }
                        }
                    }

                    if (sawPopup && popupTargetVar != null && !renderedCloseOp) {
                        methods.append("        ").append(popupTargetVar)
                                .append(".closeCurrentPage();\n");
                    }
                    methods.append("    }\n\n");
                }
            }
            if (opsByPage.containsKey(pc)) {
                for (String op : opsByPage.get(pc)) {
                    if ("close".equals(op)) {
                        String var = pageVar.get(pc);
                        if (var == null) continue;

                        if (inlinedTargetVars.contains(var)) continue;
                        any = true;
                        methods.append("    @Step\n");
                        methods.append("    public void close").append(pc).append("() {\n");
                        methods.append("        ").append(var)
                                .append(".closeCurrentPage();\n");
                        methods.append("    }\n\n");
                    }
                }
            }
        }

        String merged = clsHeader + methods + "}\n";
        for (String pc : allPages) out.put(pc, merged);
        if (allPages.isEmpty()) out.put(mergedStepClassName, merged);
        return out;
    }

    public static String generateMulti(LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage,
                                        LinkedHashMap<String, List<RoleEntry>> entriesByPage,
                                        String packageName, String stepClassName) {
        return generateMulti(stepsByPage, entriesByPage, new LinkedHashMap<>(), packageName, stepClassName);
    }

    private static String inferPopupTargetVar(List<RoleEntry> step, String openVar,
            Map<String, String> pageVar, LinkedHashMap<String, List<String>> opsByPage) {
        boolean sawPopup = false;
        if (step != null) {
            boolean pastPopup = false;
            for (RoleEntry e : step) {
                if (e.isPopup()) { pastPopup = true; sawPopup = true; continue; }
                if (pastPopup) {

                    String pc = e.getPageClass();
                    if (pc != null && !pc.isEmpty()) {
                        String v = pageVar.get(pc);
                        if (v != null && !v.equals(openVar)) return v;
                    }
                }
            }
        }
        if (opsByPage != null) {
            for (Map.Entry<String, List<String>> en : opsByPage.entrySet()) {
                if (en.getValue() != null && en.getValue().contains("close")) {
                    String v = pageVar.get(en.getKey());
                    if (v != null && !v.equals(openVar)) return v;
                }
            }
        }

        if (sawPopup && openVar != null && !openVar.isEmpty()) return openVar;
        return null;
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isCloseOnlyStep(List<RoleEntry> step) {
        if (step == null || step.isEmpty()) return false;
        for (RoleEntry e : step) if (!e.isCloseOp()) return false;
        return true;
    }

    private static List<List<RoleEntry>> mergeCloseOnlySteps(List<List<RoleEntry>> steps) {
        if (steps == null) return new ArrayList<>();
        List<List<RoleEntry>> out = new ArrayList<>();
        List<RoleEntry> last = null;
        for (List<RoleEntry> st : steps) {
            if (st == null) continue;
            if (isCloseOnlyStep(st)) {
                if (last != null) { last.addAll(st); continue; }
            }
            out.add(st);
            last = st;
        }
        return out;
    }

    private static String nextNewPageVar(int[] idx) {
        idx[0]++;
        return idx[0] == 1 ? "newPage" : "newPage" + idx[0];
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成 Step 类源码（Tab2）：<b>一次 Start→Pause 会话 = 一个 {@code @Step} 方法</b>。
 *
 * <h2>产物形态</h2>
 * <pre>
 * public class LogonSteps {
 *
 *     &#64;Step
 *     public void step1() {
 *         LogonPage logonPage = PageObjectFactory.getPage(LogonPage.class);
 *
 *         logonPage.userName.fill("dbb_1");
 *         logonPage.signIn.click();
 *     }
 *
 *     &#64;Step
 *     public void step2() {
 *         LogonPage logonPage = PageObjectFactory.getPage(LogonPage.class);
 *         PopupPage popupPage = PageObjectFactory.getPage(PopupPage.class);
 *
 *         Page newPage1 = logonPage.waitForNewPage(() -> logonPage.openProfile.click());
 *         popupPage.switchToPage(newPage1);
 *         popupPage.ok.click();
 *     }
 * }
 * </pre>
 *
 * <p>要点：
 * <ul>
 *   <li>方法名 {@code step1..N}，与 Assert 的 {@code assert1..N} 一一对应（同一会话）；</li>
 *   <li>方法体内<b>按需实例化本会话涉及的所有页面类</b>——同一步操作了多个页面时，
 *       这一步就实例化多个页面对象（而不是把不同页面塞进同一个 Page 类）；</li>
 *   <li>字段名/动作表达式由 {@link CodegenFieldPlanner} 统一规划，与 Page 类严格同源；</li>
 *   <li>页面切换处生成 {@code Page newPageN = from.waitForNewPage(() -> from.trigger.click())} 并接住返回值，
 *       随后 {@code to.switchToPage(newPageN)} 把新页显式绑定到目标页对象 —— 不使用任何
 *       "全局当前页"隐式状态，新页引用来自 waitForNewPage 的返回值。</li>
 * </ul>
 *
 * <p>模板：{@code codegen/templates/step-class-sessions.ftl}。
 */
public final class StepClassGenerator {

    private static final String TEMPLATE = "step-class-sessions.ftl";

    private StepClassGenerator() {
    }

    /**
     * @param header   命名来源（本会话第一个页面）：决定 Step 类名与包名
     * @param sessions 会话序列（一次 Start→Pause 一项）
     */
    public static String generateSessions(CodegenTarget header, List<MultiPageCodegen.SessionUnit> sessions) {
        if (header == null) {
            throw new IllegalArgumentException("codegen target must not be null");
        }
        List<String> imports = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> methods = new ArrayList<>(sessions.size());

        for (MultiPageCodegen.SessionUnit s : sessions) {
            for (CodegenTarget page : s.pages) {
                if (seen.add(page.pageClassName)) {
                    imports.add(page.packageName + "." + page.pageClassName);
                }
            }
            if (usesPageSwitch(s)) {
                // waitForNewPage 返回新页 Page，供 switchToPage(newPage) 显式绑定目标页对象
                seen.add("Page");
                imports.add("com.microsoft.playwright.Page");
            }

            List<String> body = new ArrayList<>();
            for (CodegenTarget page : s.pages) {
                body.add("        " + page.pageClassName + " " + page.pageVar
                        + " = PageObjectFactory.getPage(" + page.pageClassName + ".class);");
            }
            if (!s.pages.isEmpty() && !s.actions.isEmpty()) {
                body.add("");
            }
            int newPageSeq = 0;
            for (MultiPageCodegen.SessionAction action : s.actions) {
                if (action.kind == MultiPageCodegen.ActionKind.PAGE_SWITCH) {
                    newPageSeq++;
                }
                body.addAll(render(action, newPageSeq));
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", "step" + s.index);
            m.put("bodyLines", body);
            methods.add(m);
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("generatedMarker", CodegenMarkers.GENERATED_MARKER);
        model.put("stepPackageName", header.stepPackageName);
        model.put("stepClassName", header.stepClassName);
        model.put("imports", imports);
        model.put("methods", methods);
        return CodegenTemplates.render(TEMPLATE, model);
    }

    /** 本会话是否存在跨标签页切换（决定是否引入 {@code com.microsoft.playwright.Page} import）。 */
    private static boolean usesPageSwitch(MultiPageCodegen.SessionUnit s) {
        for (MultiPageCodegen.SessionAction a : s.actions) {
            if (a.kind == MultiPageCodegen.ActionKind.PAGE_SWITCH) {
                return true;
            }
        }
        return false;
    }

    /** 单条会话动作 → 方法体行（含必要的 TODO / 注释）。 */
    private static List<String> render(MultiPageCodegen.SessionAction a, int newPageSeq) {
        List<String> out = new ArrayList<>(2);
        switch (a.kind) {
            case ELEMENT:
                if (a.todo != null && !a.todo.isEmpty()) {
                    out.add("        // TODO(review): " + a.todo);
                }
                out.add("        " + pageVar(a.target) + "." + a.fieldName + "." + a.action + ";");
                break;
            case FRAME_SWITCH:
                out.add("        // switch to frame: " + (a.framePath == null ? a.frameSelector : a.framePath));
                out.add("        " + pageVar(a.target) + ".switchToFrame(\""
                        + CodegenFieldPlanner.escapeJava(a.frameSelector) + "\");");
                break;
            case DIALOG:
                out.add("        " + pageVar(a.target) + "."
                        + (a.accept ? "acceptAlert()" : "dismissAlert()") + ";");
                break;
            case PAGE_SWITCH:
                // waitForNewPage 返回新页 Page；必须接住返回值并显式 switchToPage(newPage) 绑定到
                // 目标页对象 —— 它自身的 owner.page 仍停在旧页（@Element 字段会解析到旧页），
                // 且不依赖任何"全局当前页"隐式状态。同方法多次切页时变量按序编号（newPage1..N）。
                out.add("        Page newPage" + newPageSeq + " = " + pageVar(a.target) + ".waitForNewPage(() -> "
                        + pageVar(a.target) + "." + a.fieldName + "." + a.action + ");");
                if (a.toTarget != null && !a.toTarget.equals(a.target)) {
                    out.add("        " + pageVar(a.toTarget) + ".switchToPage(newPage" + newPageSeq + ");");
                }
                break;
            case NAVIGATION:
                // 同标签页内 URL 跳转：不切页、不等待，仅提示（该页对象已在方法顶部实例化）。
                out.add("        // " + (a.note == null ? "same-tab navigation" : a.note));
                break;
            default:
                break;
        }
        return out;
    }

    private static String pageVar(CodegenTarget target) {
        return target == null ? "" : target.pageVar;
    }
}

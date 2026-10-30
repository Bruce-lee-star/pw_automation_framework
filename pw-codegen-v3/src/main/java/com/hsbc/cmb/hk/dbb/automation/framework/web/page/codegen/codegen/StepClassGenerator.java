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
 *         logonPage.waitForNewPage(() -> logonPage.openProfile.click());
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
 *   <li>页面切换处生成 {@code from.waitForNewPage(() -> from.trigger.click())}，触发元素不会在
 *       来源页被重复点击。</li>
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

            List<String> body = new ArrayList<>();
            for (CodegenTarget page : s.pages) {
                body.add("        " + page.pageClassName + " " + page.pageVar
                        + " = PageObjectFactory.getPage(" + page.pageClassName + ".class);");
            }
            if (!s.pages.isEmpty() && !s.actions.isEmpty()) {
                body.add("");
            }
            for (MultiPageCodegen.SessionAction action : s.actions) {
                body.addAll(render(action));
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

    /** 单条会话动作 → 方法体行（含必要的 TODO / 注释）。 */
    private static List<String> render(MultiPageCodegen.SessionAction a) {
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
                // 触发元素在来源页触发新页，新页由框架接管（页面对象懒跟随当前页）。
                out.add("        " + pageVar(a.target) + ".waitForNewPage(() -> " + pageVar(a.target) + "."
                        + a.fieldName + "." + a.action + ");");
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

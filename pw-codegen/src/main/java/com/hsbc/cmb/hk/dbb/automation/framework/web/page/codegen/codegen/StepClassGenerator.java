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
 *     private final LogonPage logonPage = PageObjectFactory.getPage(LogonPage.class);
 *     private final PopupPage popupPage = PageObjectFactory.getPage(PopupPage.class);
 *
 *     &#64;Step
 *     public void step1() {
 *         logonPage.userName.fill("dbb_1");
 *         logonPage.signIn.click();
 *     }
 *
 *     &#64;Step
 *     public void step2() {
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
 *   <li>页面对象<b>声明为 private final 类字段</b>（{@code private final XxxPage xxxPage = PageObjectFactory.getPage(...)}，
 *       符合 {@code PageObjectFactory} 约定），一次声明供所有 step 复用，不在方法内重复实例化；
 *       同一次录制涉及的多个页面各自一个字段（而不是把不同页面塞进同一个 Page 类）；</li>
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
        // Page 对象按框架约定声明为类字段（见 PageObjectFactory Javadoc）：所有 step 复用同一实例，
        // 不在每个方法内重复 getPage。多个会话涉及同一页类时只声明一次。
        List<String> fields = new ArrayList<>();
        Set<String> fieldClasses = new LinkedHashSet<>();
        List<Map<String, Object>> methods = new ArrayList<>(sessions.size());

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
            if (usesPageSwitch(s)) {
                // waitForNewPage 返回新页 Page，供 switchToPage(newPage) 显式绑定目标页对象。
                // P2-02：判重必须用 FQN——seen 集合里存的是 "包.类名"，混入简单名 "Page" 会与
                // 名为 Page 的页面类冲突，导致 com.microsoft.playwright.Page 导入被漏掉。
                if (seen.add("com.microsoft.playwright.Page")) {
                    imports.add("com.microsoft.playwright.Page");
                }
            }
            if (usesDownload(s) && seen.add("com.microsoft.playwright.Download")) {
                imports.add("com.microsoft.playwright.Download");
            }

            List<String> body = new ArrayList<>();
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

        // press 产物用 PressKey 枚举重载（避免字符串键名大小写不一致回放报错），按需注入 import。
        boolean usesPressKey = methods.stream()
                .flatMap(m -> ((List<?>) m.get("bodyLines")).stream())
                .anyMatch(line -> line instanceof String && ((String) line).contains("PressKey."));
        if (usesPressKey) {
            imports.add("com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PressKey");
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("generatedMarker", CodegenMarkers.GENERATED_MARKER);
        model.put("stepPackageName", header.stepPackageName);
        model.put("stepClassName", header.stepClassName);
        model.put("imports", imports);
        model.put("fields", fields);
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

    private static int parseOrDefault(String[] parts, int idx, int fallback) {
        if (parts == null || idx >= parts.length) return fallback;
        try { return Integer.parseInt(parts[idx].trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /** 本会话是否存在文件下载动作（决定是否引入 Download import）。 */
    private static boolean usesDownload(MultiPageCodegen.SessionUnit s) {
        for (MultiPageCodegen.SessionAction a : s.actions) {
            if (a.kind == MultiPageCodegen.ActionKind.DOWNLOAD) return true;
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
                // 必须「先武装监听器再触发」：acceptAlert()/dismissAlert() 是一次性 onceDialog，
                // 独立成行时弹窗早已被 Playwright 默认 dismiss，录的「确定」会跑成「取消」。
                String dialogMethod = a.accept ? "acceptAlert" : "dismissAlert";
                if (a.fieldName != null && !a.fieldName.isBlank()
                        && a.action != null && !a.action.isBlank()) {
                    out.add("        " + pageVar(a.target) + "." + dialogMethod + "(() -> "
                            + pageVar(a.target) + "." + a.fieldName + "." + a.action + ");");
                } else {
                    // 找不到触发动作（定时器 / 异步请求触发的弹窗）：退化为独立语句并显式提示。
                    out.add("        // TODO(review): dialog not bound to a recorded trigger - "
                            + "the once-dialog handler must be armed BEFORE the real trigger, "
                            + "otherwise Playwright dismisses it by default");
                    out.add("        " + pageVar(a.target) + "." + dialogMethod + "();");
                }
                break;
            case DOWNLOAD:
                // C02：waitForDownload 必须先武装再触发；绑定触发动作时把 once 监听包在外面。
                if (a.fieldName != null && !a.fieldName.isBlank()
                        && a.action != null && !a.action.isBlank()) {
                    out.add("        Download dl = " + pageVar(a.target) + ".waitForDownload(() -> "
                            + pageVar(a.target) + "." + a.fieldName + "." + a.action + ");");
                    out.add("        // downloaded: " + (a.note == null || a.note.isBlank() ? "(name unknown)" : a.note));
                } else {
                    out.add("        // TODO(review): download not bound to a recorded trigger - "
                            + "arm waitForDownload BEFORE the real trigger, otherwise the event is missed");
                    out.add("        Download dl = " + pageVar(a.target) + ".waitForDownload();");
                }
                break;
            case SCROLL:
                // C04：滚动后需显式等待异步加载完成（框架无法自动判断"加载完"）。
                String[] xy = (a.action == null ? "0,0" : a.action).split(",", -1);
                int dx = parseOrDefault(xy, 0, 0);
                int dy = parseOrDefault(xy, 1, 0);
                out.add("        " + pageVar(a.target) + ".scrollBy(" + dx + ", " + dy + ");");
                out.add("        // TODO(review): wait for lazy-loaded content to settle before the next action");
                break;
            case RELOAD:
                // C05：刷新 / beforeunload。
                out.add("        " + pageVar(a.target) + ".refresh();");
                break;
            case DRAG:
                // C03：把源元素拖到目标元素（PageElement.dragTo）。降级路径（端点未进入字段表）时
                // action 为 null、note 带选择器摘要——只输出 TODO 注释，不生成非法源码。
                if (a.fieldName == null || a.action == null) {
                    out.add("        // TODO(review): " + (a.note == null ? "unresolved drag" : a.note));
                } else {
                    out.add("        " + pageVar(a.target) + "." + a.fieldName + ".dragTo("
                            + pageVar(a.toTarget) + "." + a.action + ");");
                }
                break;
            case PAGE_SWITCH:
                // waitForNewPage 返回新页 Page；必须接住返回值并显式 switchToPage(newPage) 绑定到
                // 目标页对象 —— 它自身的 owner.page 仍停在旧页（@Element 字段会解析到旧页），
                // 且不依赖任何"全局当前页"隐式状态。同方法多次切页时变量按序编号（newPage1..N）。
                out.add("        Page newPage" + newPageSeq + " = " + pageVar(a.target) + ".waitForNewPage(() -> "
                        + pageVar(a.target) + "." + a.fieldName + "." + a.action + ");");
                // P0-3：新标签与旧标签同 URL 时 toTarget == a.target（PageUrl 忽略标签身份），
                // 若据此跳过 switchToPage，newPageN 声明后无人使用、后续动作全打在旧页对象上。
                // 因此【总是生成切页绑定】：不同 URL 绑到新页类 toTarget；同页类新标签绑到来源页自身
                // 的 pageVar（把其 owner 指向新页）；toTarget 缺失时退回来源页，保证 newPageN 必被使用。
                CodegenTarget switchTarget = a.toTarget != null ? a.toTarget : a.target;
                out.add("        " + pageVar(switchTarget) + ".switchToPage(newPage" + newPageSeq + ");"
                        + (a.toTarget != null && a.toTarget.equals(a.target)
                           ? "  // TODO(review): same page class in a new tab - this rebinds the single "
                             + "page object to the new tab; keep both tabs only if the framework supports it"
                           : ""));
                break;
            case NAVIGATION:
                // 同标签页内 URL 跳转：不切页、不等待，仅提示（该页对象已声明为类字段）。
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

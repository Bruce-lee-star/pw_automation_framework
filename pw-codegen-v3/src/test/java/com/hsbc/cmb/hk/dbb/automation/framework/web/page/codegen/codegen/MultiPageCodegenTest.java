package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 会话式生成引擎的纯单元测试（不依赖 Playwright / 浏览器）。
 *
 * <p>锁定的产品契约：
 * <ul>
 *   <li><b>一次 Start→Pause = 一个 step</b>：{@code step1} / {@code step2}，Assert 同构 {@code assert1} / {@code assert2}；</li>
 *   <li>同一步跨了多个页面 ⇒ 该方法体内<b>实例化多个页面类</b>，元素字段仍各归各的 Page 类；</li>
 *   <li>切页内联 {@code waitForNewPage(() -> from.trigger.action)}，不再有独立流程类；</li>
 *   <li>frame 切换 / dialog 落在真实发生位置（同一个 step 内）。</li>
 * </ul>
 */
public class MultiPageCodegenTest {

    private static CodegenEntry entry(String sigKey, String role, String name, String css, int index,
                                      List<String> framePath) {
        return new CodegenEntry(sigKey, role, name, "role", 100, null, css, null, index, framePath);
    }

    private static CodegenEntry entry(String sigKey, String role, String name, String css) {
        return entry(sigKey, role, name, css, -1, List.of());
    }

    private static CodegenFieldSpec spec(CodegenFieldPlanner.Plan plan, CodegenEntry e) {
        for (CodegenFieldSpec f : plan.fields) {
            if (f.entry == e) return f;
        }
        return null;
    }

    @Test
    public void oneSessionPerStepAndMultiPageStepInstantiatesBothPageClasses() {
        // ---- 页面 A：登录页 ----
        CodegenEntry aUser = entry("textbox#u", "textbox", "username", "#u");
        CodegenEntry aOpen = entry("button#o", "button", "openPopup", "#o");   // 触发切到 B
        List<CodegenEntry> pageA = List.of(aUser, aOpen);

        // ---- 页面 B：弹窗页 ----
        CodegenEntry bNext = entry("link#n", "link", "next", "#n");
        List<CodegenEntry> pageB = List.of(bNext);

        String[] nls = new String[0];
        CodegenFieldPlanner.Plan planA = CodegenFieldPlanner.plan(pageA, nls, Map.of("textbox#u", "dbb_1"));
        CodegenFieldPlanner.Plan planB = CodegenFieldPlanner.plan(pageB, nls, Map.of());
        CodegenTarget tA = CodegenTarget.ofNamed("com.example.pages", "LogonPage");
        CodegenTarget tB = CodegenTarget.ofNamed("com.example.pages", "PopupPage");

        CodegenFieldSpec user = spec(planA, aUser);
        CodegenFieldSpec open = spec(planA, aOpen);
        CodegenFieldSpec next = spec(planB, bNext);
        assertNotNull(user);
        assertNotNull(open);
        assertNotNull(next);

        // 会话 1（step1）：A 页填用户名 → 点 openPopup 切到 B 页 → B 页点 next（同一步跨两页）
        List<MultiPageCodegen.SessionAction> actions1 = List.of(
                MultiPageCodegen.SessionAction.element(tA, user.fieldName, user.action, ""),
                MultiPageCodegen.SessionAction.pageSwitch(tA, open.fieldName, open.action, tB),
                MultiPageCodegen.SessionAction.element(tB, next.fieldName, next.action, ""));
        MultiPageCodegen.SessionUnit s1 = new MultiPageCodegen.SessionUnit(1, tA, List.of(tA, tB), actions1,
                List.of(new MultiPageCodegen.PageEntries(tA, pageA, planA),
                        new MultiPageCodegen.PageEntries(tB, pageB, planB)));

        // 会话 2（step2）：只操作 A 页
        List<MultiPageCodegen.SessionAction> actions2 = List.of(
                MultiPageCodegen.SessionAction.element(tA, user.fieldName, user.action, ""));
        MultiPageCodegen.SessionUnit s2 = new MultiPageCodegen.SessionUnit(2, tA, List.of(tA), actions2,
                List.of(new MultiPageCodegen.PageEntries(tA, pageA, planA)));

        MultiPageCodegen.MultiPageResult result = MultiPageCodegen.build(
                List.of(new MultiPageCodegen.PageUnit(tA, pageA, planA),
                        new MultiPageCodegen.PageUnit(tB, pageB, planB)),
                List.of(s1, s2), nls);

        // Page：仍按页面归集字段（两个页面类）
        assertTrue("Page 产物应包含两个页面类",
                result.pageCode.contains("class LogonPage") && result.pageCode.contains("class PopupPage"));

        // Step：一个类 + 两个方法（一次 Start→Pause 一步），不再生成流程类
        assertTrue("Step 产物应为单个 LogonSteps 类", result.stepCode.contains("class LogonSteps"));
        assertTrue("第一次 Start→Pause 应是 step1", result.stepCode.contains("public void step1()"));
        assertTrue("第二次 Start→Pause 应是 step2", result.stepCode.contains("public void step2()"));
        assertFalse("切页已内联进 step 方法，不应再有流程类",
                result.stepCode.contains("ScenarioFlowSteps"));

        String step1 = methodBody(result.stepCode, "step1");
        // 同一步跨两页 ⇒ 该 step 内实例化两个页面类
        assertTrue("step1 应实例化 LogonPage",
                step1.contains("LogonPage logonPage = PageObjectFactory.getPage(LogonPage.class);"));
        assertTrue("step1 应实例化 PopupPage",
                step1.contains("PopupPage popupPage = PageObjectFactory.getPage(PopupPage.class);"));
        // 切页用 waitForNewPage 包住触发器（触发器不重复点击），并接住返回的新页 Page
        assertTrue("step1 应以 waitForNewPage 编排切页并接收返回值",
                step1.contains("Page newPage1 = logonPage.waitForNewPage(() -> logonPage."
                        + open.fieldName + "." + open.action + ");"));
        // 切页后必须把 waitForNewPage 返回的新页显式绑定到目标页对象（不依赖全局当前页）
        assertTrue("切页后应把新页绑定到目标页对象（switchToPage(newPage1)）",
                step1.contains("popupPage.switchToPage(newPage1);"));
        assertTrue("切换后应继续操作新页元素",
                step1.contains("popupPage." + next.fieldName + "." + next.action + ";"));

        String step2 = methodBody(result.stepCode, "step2");
        assertTrue("step2 只用到 A 页，应实例化 LogonPage",
                step2.contains("getPage(LogonPage.class)"));
        assertFalse("step2 未操作 B 页，不应实例化 PopupPage", step2.contains("PopupPage"));

        // Assert：与 Step 严格同构
        assertTrue("Assert 产物应为单个 LogonAssertions 类",
                result.assertCode.contains("class LogonAssertions"));
        assertTrue("断言方法应为 assert1", result.assertCode.contains("public void assert1()"));
        assertTrue("断言方法应为 assert2", result.assertCode.contains("public void assert2()"));

        // 面板三栏的块：Page 一页一块；Step / Assert 各单块
        assertEquals("Page 栏应按页给出两个块", 2, result.pageBlocks.size());
        assertEquals("LogonSteps", result.stepBlocks.get(0).label);
        assertEquals(1, result.assertBlocks.size());
        assertEquals("LogonAssertions", result.assertBlocks.get(0).label);
    }

    @Test
    public void sameTabNavigationInsertsCommentInsteadOfWaitForNewPage() {
        // 同一标签页内导航：A 页点链接后 URL 跳到 B 页（而非弹新窗）。
        // 此时不应生成 waitForNewPage（那是跨标签页语义），只插入「已导航」注释，两个页类都在方法顶部实例化。
        CodegenEntry aClick = entry("link#go", "link", "goToB", "#go");
        CodegenEntry bSubmit = entry("button#s", "button", "submit", "#s");
        List<CodegenEntry> pageA = List.of(aClick);
        List<CodegenEntry> pageB = List.of(bSubmit);
        String[] nls = new String[0];
        CodegenFieldPlanner.Plan planA = CodegenFieldPlanner.plan(pageA, nls, Map.of());
        CodegenFieldPlanner.Plan planB = CodegenFieldPlanner.plan(pageB, nls, Map.of());
        CodegenTarget tA = CodegenTarget.ofNamed("com.example.pages", "PrivacyAndSecurityPage");
        CodegenTarget tB = CodegenTarget.ofNamed("com.example.pages", "BusinessIntegratedAccountPage");
        CodegenFieldSpec go = spec(planA, aClick);
        CodegenFieldSpec submit = spec(planB, bSubmit);
        assertNotNull(go);
        assertNotNull(submit);

        // 同一步内：在 A 页点击 → 同标签页跳到 B 页 → 在 B 页提交
        List<MultiPageCodegen.SessionAction> actions = List.of(
                MultiPageCodegen.SessionAction.element(tA, go.fieldName, go.action, ""),
                MultiPageCodegen.SessionAction.navigation(tA, tB),
                MultiPageCodegen.SessionAction.element(tB, submit.fieldName, submit.action, ""));
        MultiPageCodegen.SessionUnit s = new MultiPageCodegen.SessionUnit(1, tA, List.of(tA, tB), actions,
                List.of(new MultiPageCodegen.PageEntries(tA, pageA, planA),
                        new MultiPageCodegen.PageEntries(tB, pageB, planB)));

        MultiPageCodegen.MultiPageResult result = MultiPageCodegen.build(
                List.of(new MultiPageCodegen.PageUnit(tA, pageA, planA),
                        new MultiPageCodegen.PageUnit(tB, pageB, planB)),
                List.of(s), nls);

        // 两个页面类都产出（不再被吞掉）
        assertTrue("Page 产物应包含两个页面类",
                result.pageCode.contains("class PrivacyAndSecurityPage")
                        && result.pageCode.contains("class BusinessIntegratedAccountPage"));

        String step1 = methodBody(result.stepCode, "step1");
        // 两个页类都在方法顶部实例化
        assertTrue("step1 应实例化 PrivacyAndSecurityPage",
                step1.contains("PrivacyAndSecurityPage privacyAndSecurityPage = PageObjectFactory.getPage(PrivacyAndSecurityPage.class);"));
        assertTrue("step1 应实例化 BusinessIntegratedAccountPage",
                step1.contains("BusinessIntegratedAccountPage businessIntegratedAccountPage = PageObjectFactory.getPage(BusinessIntegratedAccountPage.class);"));
        // 同标签页导航：不生成 waitForNewPage，而是「已导航」注释
        assertFalse("同标签页导航不应生成 waitForNewPage", step1.contains("waitForNewPage"));
        assertTrue("同标签页导航应插入「已导航」注释",
                step1.contains("same-tab navigation: now on BusinessIntegratedAccountPage"));
        // 导航后继续操作新页元素
        assertTrue("导航后应继续操作 B 页元素",
                step1.contains("businessIntegratedAccountPage." + submit.fieldName + "." + submit.action + ";"));
    }

    @Test
    public void frameSwitchAndDialogAreInlinedInTheSameStep() {
        CodegenEntry inFrame = entry("textbox#f", "textbox", "frameField", "#inner", -1, List.of("iframe#f"));
        List<CodegenEntry> page = List.of(inFrame);
        String[] nls = new String[0];
        CodegenFieldPlanner.Plan plan = CodegenFieldPlanner.plan(page, nls, Map.of());
        CodegenTarget t = CodegenTarget.ofNamed("com.example.pages", "SoloPage");
        CodegenFieldSpec f = spec(plan, inFrame);
        assertNotNull(f);

        List<MultiPageCodegen.SessionAction> actions = List.of(
                MultiPageCodegen.SessionAction.frameSwitch(t, "iframe#f", "iframe#f"),
                MultiPageCodegen.SessionAction.element(t, f.fieldName, f.action, ""),
                MultiPageCodegen.SessionAction.dialog(t, true));
        MultiPageCodegen.SessionUnit session = new MultiPageCodegen.SessionUnit(1, t, List.of(t), actions,
                List.of(new MultiPageCodegen.PageEntries(t, page, plan)));

        MultiPageCodegen.MultiPageResult result = MultiPageCodegen.build(
                List.of(new MultiPageCodegen.PageUnit(t, page, plan)), List.of(session), nls);

        String step1 = methodBody(result.stepCode, "step1");
        assertTrue("frame 内元素前应生成 switchToFrame", step1.contains("switchToFrame(\"iframe#f\")"));
        assertTrue("dialog 应生成 acceptAlert", step1.contains("acceptAlert()"));
        assertFalse("单页单会话不应出现 waitForNewPage", step1.contains("waitForNewPage"));
    }

    /** 从生成源码里抽出某个方法的完整方法体（含花括号）。 */
    private static String methodBody(String all, String methodName) {
        int sig = all.indexOf("void " + methodName + "()");
        if (sig < 0) return "";
        int brace = all.indexOf('{', sig);
        int depth = 0, end = brace;
        for (int i = brace; i < all.length(); i++) {
            if (all.charAt(i) == '{') depth++;
            else if (all.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        return all.substring(sig, end);
    }
}

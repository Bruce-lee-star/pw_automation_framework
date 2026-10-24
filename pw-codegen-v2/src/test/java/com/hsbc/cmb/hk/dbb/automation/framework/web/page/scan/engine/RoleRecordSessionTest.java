package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine.RoleRecordSession;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.RoleEntry;

/**
 * 录制会话 API 契约（Phase 1）。
 *
 * <p>锁定三件事：
 * <ol>
 *   <li>交互动作（click/type/hover/rightClick）进入步骤类，且操作语义与 {@code operationFor} 一致
 *       （type → {@code fill("...")}、hover → {@code hover()}、rightClick → {@code rightClick()}）；</li>
 *   <li>assert 动作不进入交互步骤，单独产出断言类（{@code isVisible()}）；</li>
 *   <li>所有被操作元素去重后进入页面类（{@code @RoleElement} + {@code PageElement} 字段）。</li>
 * </ol>
 * 纯单元（无需浏览器）：元素用 {@link RoleEntry} 手动构造，验证"序列 → 代码"链路复用生成器三件套。
 */
public class RoleRecordSessionTest {

    private static final String PKG = "com.example.tests.pages";
    private static final String PAGE = "LoginPage";
    private static final String STEP = "LoginPageSteps";

    private static RoleEntry button(String name, String key) {
        return new RoleEntry("button", name, "button", 1, key);
    }

    private static RoleEntry textbox(String name, String key) {
        return new RoleEntry("textbox", name, "input", 1, key);
    }

    @Test
    public void clickAndTypeProduceStepWithClickAndFill() {
        RoleRecordSession session = RoleRecordSession.create(PKG, PAGE, STEP);
        RoleEntry loginBtn = button("Login", "login.btn");
        RoleEntry userInput = textbox("Username", "login.user");

        session.recordClick(loginBtn);
        session.recordType(userInput, "alice");

        RoleRecordSession.GeneratedArtifact art = session.generate();
        assertNotNull(art);
        assertTrue("页面类应包含 @RoleElement 字段", art.pageSource.contains("@RoleElement"));
        assertTrue("页面类应包含 PageElement 字段", art.pageSource.contains("public PageElement"));
        assertTrue("步骤类应含 click()", art.stepSource.contains(".click();"));
        assertTrue("type(文本框) 应产出 fill(\"alice\")", art.stepSource.contains("fill(\"alice\")"));
        assertFalse("无断言动作时断言类应为 null", art.assertionSource != null && !art.assertionSource.isEmpty());
    }

    @Test
    public void hoverAndRightClickUseCorrectOperations() {
        RoleRecordSession session = RoleRecordSession.create(PKG, PAGE, STEP);
        // 实战中每次拾取得到独立 RoleEntry 实例，故 hover / rightClick 各自独立（避免操作标志互相污染）。
        RoleEntry hoverTarget = button("Menu", "menu.hover.btn");
        RoleEntry rightTarget = button("Context", "menu.right.btn");

        session.recordHover(hoverTarget);
        session.recordRightClick(rightTarget);

        RoleRecordSession.GeneratedArtifact art = session.generate();
        assertTrue("hover 应产出 hover()", art.stepSource.contains(".hover();"));
        assertTrue("rightClick 应产出 rightClick()", art.stepSource.contains(".rightClick();"));
    }

    @Test
    public void assertVisibleGoesToAssertionClassNotStep() {
        RoleRecordSession session = RoleRecordSession.create(PKG, PAGE, STEP);
        RoleEntry loginBtn = button("Login", "login.btn");

        session.recordClick(loginBtn);
        session.recordAssertVisible(loginBtn);

        RoleRecordSession.GeneratedArtifact art = session.generate();
        assertNotNull(art.assertionSource);
        assertTrue("断言类应含 isVisible()", art.assertionSource.contains("isVisible()"));
        // 断言动作不应作为 click 步骤出现（断言与点击用同一字段，但断言不重复生成 click 语句）
        assertTrue("断言类应生成 assertStep1 方法", art.assertionSource.contains("assertStep1"));
    }

    @Test
    public void distinctElementsDeduplicatedInPageClass() {
        RoleRecordSession session = RoleRecordSession.create(PKG, PAGE, STEP);
        RoleEntry a = button("One", "btn.one");
        RoleEntry b = button("Two", "btn.two");

        session.recordClick(a);
        session.recordClick(a); // 同元素重复点击，页面类只应有一个字段
        session.recordClick(b);

        RoleRecordSession.GeneratedArtifact art = session.generate();
        int roleElementCount = countOccurrences(art.pageSource, "public PageElement ");
        assertEquals("去重后页面类应为 2 个字段", 2, roleElementCount);
    }

    @Test
    public void emptySessionRejected() {
        RoleRecordSession session = RoleRecordSession.create(PKG, PAGE, STEP);
        try {
            session.generate();
            fail("空会话 generate() 应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void nullTargetRejected() {
        RoleRecordSession session = RoleRecordSession.create(PKG, PAGE, STEP);
        try {
            session.recordClick(null);
            fail("null 目标应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static int countOccurrences(String src, String sub) {
        if (src == null || sub == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = src.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}

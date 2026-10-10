package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 右键点击（contextmenu）录制的契约：
 * {@code operationFor} 必须输出元素门面的 {@code rightClick()}（{@code PageElement#rightClick}），
 * 而不是 Playwright 原生串 {@code click({ button: 'right' })}（封装类无该重载、产物编译不过）；
 * 且优先级高于角色推断（右键 checkbox 不应误生成 setChecked），整链 step 产物须含 {@code .rightClick()}。
 *
 * <p>纯离线用例，复用 {@link RolePickerCodeAssembler#buildStepCode} 全链路，无浏览器依赖。
 */
public class RoleElementStepGeneratorRightClickTest {

    private static final String PAGE = "LoginPage";
    private static final String PKG = "com.example.steps";

    /** 构造角色策略元素：{@code RoleEntry(role, name, tag, level, key)} 会把 strategy 置为角色策略。 */
    private static RoleEntry entry(String role, String name, String key) {
        return new RoleEntry(role, name, role, 1, key);
    }

    /** 标注为右键交互。 */
    private static RoleEntry rightClick(RoleEntry e) {
        e.setRightClick(true);
        return e;
    }

    @Test
    // @DisplayName: "普通按钮右键 → rightClick()"
    public void buttonRightClickProducesRightClickCall() {
        RoleEntry e = rightClick(entry("button", "Submit", "k_submit"));
        assertEquals("rightClick()", RoleElementStepGenerator.operationFor(e));
    }

    @Test
    // @DisplayName: "右键优先级高于角色推断：右键 checkbox 不退化成 setChecked"
    public void rightClickBeatsCheckboxInference() {
        RoleEntry e = rightClick(entry("checkbox", "Agree", "k_agree"));
        assertEquals("rightClick()", RoleElementStepGenerator.operationFor(e));
    }

    @Test
    // @DisplayName: "右键优先级高于 hover：右键不应退化为 .hover()"
    public void rightClickBeatsHoverInference() {
        RoleEntry e = rightClick(entry("link", "Home", "k_home"));
        e.setHover(true);
        assertEquals("rightClick()", RoleElementStepGenerator.operationFor(e));
    }

    private static String generateSingleStep(RoleEntry e) {
        PickSnapshot snap = new PickSnapshot(PAGE,
                Collections.singletonList(e),
                Collections.singletonList(new StepRec(PAGE, Collections.singletonList(e))),
                new ArrayList<>());
        LinkedHashMap<String, String> out = RolePickerCodeAssembler.buildStepCode(snap, PKG, "DemoSteps");
        assertTrue("应生成该页的按页视图", out.containsKey(PAGE));
        String src = out.get(PAGE);
        int i = src.indexOf("public void step1()");
        assertTrue("未生成 step1 方法：" + src, i > 0);
        return src.substring(i);
    }

    private static int count(String s, String needle) {
        int n = 0, i = s.indexOf(needle);
        while (i >= 0) { n++; i = s.indexOf(needle, i + needle.length()); }
        return n;
    }

    @Test
    // @DisplayName: "整链产物含 .rightClick()，且不出现裸 .click() 或 doubleClick()"
    public void pipelineEmitsRightClickCallWithoutBareClick() {
        String body = generateSingleStep(rightClick(entry("button", "Submit", "k_submit")));
        assertTrue("产物须含右键点击调用：" + body, body.contains(".rightClick()"));
        // 裸 .click()（左键）不应出现；右键调用是 .rightClick()，不是 .click()
        assertEquals("不应出现裸 .click()：" + body, 0, count(body, ".click()"));
        assertFalse("不应误生成 doubleClick()：" + body, body.contains("doubleClick()"));
    }

    @Test
    // @DisplayName: "右键 + 弹窗标记 → 仍被 waitForNewPage 包裹（flag 驱动包裹与 operation 内容无关）"
    public void rightClickWithPopupIsWrapped() {
        RoleEntry e = rightClick(entry("link", "Report", "k_report"));
        e.setPopup(true);
        String body = generateSingleStep(e);
        assertTrue("右键点击应被 waitForNewPage 包裹：" + body, body.contains("waitForNewPage"));
        assertTrue("包裹体内须是右键调用：" + body, body.contains(".rightClick()"));
    }
}

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
 * 步骤代码「产物零注释」契约 + 步骤编号 / 对账丢弃等下游行为。
 *
 * <p>锁定两件事：
 * <ol>
 *   <li>生成的 Java 产物里不得出现任何注释（no comment in generated java）；</li>
 *   <li>面板封装序号决定 step 编号顺序；被对账丢弃的元素不产生操作，整条被丢弃的封装不生成方法。</li>
 * </ol>
 */
public class RoleStepGenerationNoCommentsTest {

    private static final String PAGE = "LoginPage";
    private static final String PKG = "com.example.steps";

    private static RoleEntry entry(String name, String key) {
        return new RoleEntry("button", name, "button", 1, key);
    }

    private static RoleEntry pick(RoleEntry e, int... nos) {
        List<Integer> l = new ArrayList<>();
        for (int n : nos) {
            l.add(n);
        }
        e.setPickNos(l);
        return e;
    }

    private static String generate(List<RoleEntry> entries, List<StepRec> steps) {
        PickSnapshot snap = new PickSnapshot(PAGE, entries, steps, new ArrayList<>());
        LinkedHashMap<String, String> out = RolePickerCodeAssembler.buildStepCode(snap, PKG, "DemoSteps");
        assertTrue("应生成该页的按页视图，实际键=" + out.keySet(), out.containsKey(PAGE));
        return out.get(PAGE);
    }

    /** 产物不得含任何注释（行注释 // 或块注释起始 /*）。 */
    private static void assertNoComments(String src) {
        assertFalse("产物不应含 // 注释：\n" + src, src.contains("//"));
        assertFalse("产物不应含块注释：\n" + src, src.contains("/*"));
    }

    private static String afterFirstStep(String src) {
        int i = src.indexOf("public void step1()");
        assertTrue("未生成 step1 方法：" + src, i > 0);
        return src.substring(i);
    }

    private static int count(String s, String needle) {
        int n = 0;
        int i = s.indexOf(needle);
        while (i >= 0) {
            n++;
            i = s.indexOf(needle, i + needle.length());
        }
        return n;
    }

    @Test
    public void stepsAreNumberedInPanelOrderWithoutComments() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        String src = generate(Arrays.asList(a, b), Arrays.asList(
                new StepRec(PAGE, Collections.singletonList(pick(a, 1))),
                new StepRec(PAGE, Collections.singletonList(pick(b, 2)))));

        assertTrue(src, src.contains("public void step1()"));
        assertTrue(src, src.contains("public void step2()"));
        assertNoComments(src);
    }

    @Test
    public void fullyDroppedStepIsSkippedWithoutComments() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry ghost = entry("Ghost", "k_ghost");
        String src = generate(Collections.singletonList(a), Arrays.asList(
                new StepRec(PAGE, Collections.singletonList(pick(ghost, 1))),
                new StepRec(PAGE, Collections.singletonList(pick(a, 2)))));

        assertTrue(src, src.contains("public void step1()"));
        assertFalse("面板第 1 个封装的元素已全删，不应生成 step2：" + src, src.contains("public void step2()"));
        assertNoComments(src);
    }

    @Test
    public void droppedPickProducesNoOperationAndNoComment() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry ghost = entry("Ghost", "k_ghost");
        RoleEntry b = entry("B", "k_b");
        String src = generate(Arrays.asList(a, b), Collections.singletonList(
                new StepRec(PAGE, Arrays.asList(pick(a, 1), pick(ghost, 2), pick(b, 3)))));

        assertTrue(src, src.contains("public void step1()"));
        assertEquals("丢弃的 pick 不应产生操作（应只剩 A/B 两次 click）：" + src,
                2, count(afterFirstStep(src), ".click()"));
        assertNoComments(src);
    }

    @Test
    public void elementWithoutPickNosSortsLast() {
        RoleEntry late = entry("Late", "k_late");
        RoleEntry first = entry("First", "k_first");

        String fLate = null;
        String fFirst = null;
        for (RoleElementPageGenerator.GeneratedField f :
                RoleElementPageGenerator.assignFields(Arrays.asList(late, first))) {
            String lk = RoleElementPageGenerator.locatorKey(f.entry);
            if (lk != null && lk.endsWith(":k_late")) {
                fLate = f.fieldName;
            } else if (lk != null && lk.endsWith(":k_first")) {
                fFirst = f.fieldName;
            }
        }
        assertTrue("未解析出 k_late 的字段名", fLate != null && !fLate.isEmpty());
        assertTrue("未解析出 k_first 的字段名", fFirst != null && !fFirst.isEmpty());

        String src = generate(Arrays.asList(late, first),
                Collections.singletonList(new StepRec(PAGE, Arrays.asList(late, pick(first, 1)))));

        String body = afterFirstStep(src);
        int iFirst = body.indexOf("." + fFirst + ".");
        int iLate = body.indexOf("." + fLate + ".");
        assertTrue("两个元素的操作都应在 step1 内：" + body, iFirst >= 0 && iLate >= 0);
        assertTrue("无序号元素必须垫底（有号元素在前），实际 first@" + iFirst + " late@" + iLate + "：\n" + body,
                iFirst < iLate);
    }
}

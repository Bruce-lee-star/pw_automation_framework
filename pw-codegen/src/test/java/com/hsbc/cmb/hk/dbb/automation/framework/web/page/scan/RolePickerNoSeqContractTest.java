package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 不加序号契约：元素不分配拾取序号（pickNos 为空）时，
 * {@link RolePickerCodeAssembler#snapWithAutoStep} 把整段「开始→停止」拾取封装为<b>单一 step</b>，
 * 步骤内操作顺序 = 拾取自然顺序（javaPickBySig 插入顺序 = 点击先后），不再依赖 pickNos 排序。
 *
 * <p>同步锁定面板「封装为步骤」按钮逻辑移除后的唯一生成路径：
 * 仅「开始拾取→停止拾取」会产出一个步骤，无显式序号、无手动分组。
 */
public class RolePickerNoSeqContractTest {

    private static final String PAGE = "LoginPage";

    private static RoleEntry entry(String name, String key) {
        return new RoleEntry("button", name, "button", 1, key);
    }

    private static String fieldOf(RoleEntry e, List<RoleEntry> entries) {
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(entries)) {
            String lk = RoleElementPageGenerator.locatorKey(f.entry);
            String want = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && lk.equals(want)) {
                return f.fieldName;
            }
        }
        throw new AssertionError("未找到字段名：" + RoleElementPageGenerator.locatorKey(e));
    }

    /** 按操作行出现先后，抽出被操作元素的字段名（同 RoleStepOrderContractTest 的判定口径）。 */
    private static List<String> operatedFields(String body, List<RoleEntry> entries) {
        Map<String, String> keyToField = new LinkedHashMap<>();
        for (RoleElementPageGenerator.GeneratedField f : RoleElementPageGenerator.assignFields(entries)) {
            keyToField.put(RoleElementPageGenerator.locatorKey(f.entry), f.fieldName);
        }
        List<int[]> hits = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> en : keyToField.entrySet()) {
            Matcher m = Pattern.compile(Pattern.quote("." + en.getValue() + ".")).matcher(body);
            while (m.find()) {
                hits.add(new int[]{m.start(), names.size()});
            }
            names.add(en.getValue());
        }
        hits.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<String> ordered = new ArrayList<>();
        for (int[] h : hits) {
            ordered.add(names.get(h[1]));
        }
        return ordered;
    }

    private static String bodyOfStep(String src, int methodNo) {
        int i = src.indexOf("public void step" + methodNo + "()");
        assertTrue("未生成 step" + methodNo + "：" + src, i > 0);
        int end = src.indexOf("\n    }", i);
        return src.substring(i, end < 0 ? src.length() : end);
    }

    @Test
    public void noPickNosYieldsSingleStepInNaturalOrder() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        RoleEntry c = entry("C", "k_c");
        List<RoleEntry> entries = Arrays.asList(a, b, c);   // 不调用 pick() → 默认无 pickNos
        PickSnapshot snap = new PickSnapshot(PAGE, entries, new ArrayList<>(), new ArrayList<>());
        PickSnapshot out = RolePickerCodeAssembler.snapWithAutoStep(snap);
        assertEquals("不加序号时应把整段拾取封装为单一 step", 1, out.steps.size());
        String src = RolePickerCodeAssembler.buildStepCode(out, "com.example.steps", "DemoSteps").get(PAGE);
        // 期望步骤内顺序为插入顺序 A → B → C（无 pickNos 排序）
        assertEquals(Arrays.asList(fieldOf(a, entries), fieldOf(b, entries), fieldOf(c, entries)),
                operatedFields(bodyOfStep(src, 1), entries));
    }

    @Test
    public void shuffledEntriesStillNaturalOrderWithoutPickNos() {
        RoleEntry a = entry("A", "k_a");
        RoleEntry b = entry("B", "k_b");
        RoleEntry c = entry("C", "k_c");
        // 数组故意打乱（模拟浏览器侧回传顺序不可控）：不加序号时应按插入顺序，而非数组排列
        List<RoleEntry> entries = Arrays.asList(c, a, b);
        PickSnapshot snap = new PickSnapshot(PAGE, entries, new ArrayList<>(), new ArrayList<>());
        PickSnapshot out = RolePickerCodeAssembler.snapWithAutoStep(snap);
        assertEquals(1, out.steps.size());
        String src = RolePickerCodeAssembler.buildStepCode(out, "com.example.steps", "DemoSteps").get(PAGE);
        // 期望仍是插入顺序 C → A → B（自然顺序，与数组排列无关）
        assertEquals(Arrays.asList(fieldOf(c, entries), fieldOf(a, entries), fieldOf(b, entries)),
                operatedFields(bodyOfStep(src, 1), entries));
    }
}

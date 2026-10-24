package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「一组元素（列表）」字段/步骤生成契约：被标记为 {@code list=true} 的拾取，
 * 页面类生成 {@code public PageElementList x;} 字段（并引入 {@code PageElementList} import），
 * step 用 {@code x.get(index)} 取单项（语义等价 page.pause 的 {@code locator.nth(index)}）；
 * 未标记的仍生成单个 {@code PageElement} + {@code .nth(index)}。
 *
 * <p>纯离线用例：{@code RoleElementPageGenerator.generate} / {@code RolePickerCodeAssembler.buildStepCode}
 * 全链路无浏览器依赖。
 */
public class RoleElementListFieldTest {

    private static final String PAGE = "LoginPage";
    private static final String PKG = "com.example.pages";

    /** 角色策略元素（{@code index=2} 表示该定位器匹配一组元素的第 3 个），可选标记为列表。 */
    private static RoleEntry row(boolean list) {
        RoleEntry e = new RoleEntry("row", "Row", null, null, "role", null, "k_row", false, null, false, 2, false);
        e.setList(list);
        return e;
    }

    @Test
    public void listEntryGeneratesPageElementListField() {
        String src = RoleElementPageGenerator.generate(Collections.singletonList(row(true)), PKG, PAGE);
        assertTrue("列表字段必须引入 PageElementList import：" + src,
                src.contains("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElementList;"));
        assertTrue("列表元素应生成 PageElementList 字段：" + src, src.contains("public PageElementList "));
        assertFalse("列表元素不应生成单个 PageElement 字段：" + src, src.contains("public PageElement "));
    }

    @Test
    public void nonListEntryStaysSinglePageElement() {
        String src = RoleElementPageGenerator.generate(Collections.singletonList(row(false)), PKG, PAGE);
        assertTrue("未标记列表的元素仍是单个 PageElement 字段：" + src, src.contains("public PageElement "));
        assertFalse("未标记列表的元素不应引入 PageElementList import：" + src, src.contains("PageElementList"));
    }

    /** 同签名落在【不同 index】上的多个拾取（现场：3 个 QR 图 #3/#4/#5）。 */
    private static RoleEntry qr(int index) {
        RoleEntry e = new RoleEntry("QR code to download the mobile app", "QR code to download the mobile app",
                null, null, "role", "img", "k_qr", false, null, false, index, false);
        e.setSigKey("role:img:QR code to download the mobile app#" + index);
        return e;
    }

    @Test
    public void sameSignatureWithDifferentIndexesBecomesOneList() {
        java.util.List<RoleEntry> es = new ArrayList<>();
        es.add(qr(3));
        es.add(qr(4));
        es.add(qr(5));
        String src = RoleElementPageGenerator.generate(es, PKG, PAGE);
        assertTrue("一组元素应合成一个 PageElementList 字段：" + src, src.contains("public PageElementList "));
        assertFalse("一组元素不应生成单个 PageElement 字段：" + src, src.contains("public PageElement "));
        assertEquals("一组元素只应有 1 个字段（3 个拾取共用）",
                1, countOccurrences(src, "public PageElementList "));
    }

    @Test
    public void sameSignatureGroupStepUsesDistinctGetIndexes() {
        java.util.List<RoleEntry> es = new ArrayList<>();
        es.add(qr(3));
        es.add(qr(4));
        es.add(qr(5));
        PickSnapshot snap = new PickSnapshot(PAGE, es,
                Collections.singletonList(new StepRec(PAGE, es)), new ArrayList<>());
        String src = RolePickerCodeAssembler.buildStepCode(snap, PKG, "DemoSteps").get(PAGE);
        assertTrue("每个拾取应按自身 index 取单项：" + src,
                src.contains(".get(3)") && src.contains(".get(4)") && src.contains(".get(5)"));
        assertFalse("一组元素不应退化成无索引的重复调用：" + src, src.contains(".nth("));
    }

    @Test
    public void sameElementClickedTwiceIsNotAList() {
        // 同一元素被重复点击（index 相同）→ 只是重复条目，不应变成"一组元素"
        java.util.List<RoleEntry> es = new ArrayList<>();
        es.add(qr(3));
        es.add(qr(3));
        assertFalse("index 相同的重复拾取不成组",
                RoleElementPageGenerator.listGroupBaseSigs(es).contains(
                        RoleElementPageGenerator.baseSigOf("role:img:QR code to download the mobile app#3")));
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test
    public void listEntryStepUsesGetIndex() {
        RoleEntry e = row(true);
        PickSnapshot snap = new PickSnapshot(PAGE, Collections.singletonList(e),
                Collections.singletonList(new StepRec(PAGE, Collections.singletonList(e))), new ArrayList<>());
        LinkedHashMap<String, String> out = RolePickerCodeAssembler.buildStepCode(snap, PKG, "DemoSteps");
        assertTrue("应生成该页 step 视图，实际键=" + out.keySet(), out.containsKey(PAGE));
        String src = out.get(PAGE);
        assertTrue("列表元素的 step 应用 list.get(index) 取单项：" + src, src.contains(".get(2)"));
        assertFalse("列表元素的 step 不应使用 .nth(index)：" + src, src.contains(".nth(2)"));
    }

    /** 现场回归：一组元素（list）已被识别，但 step pick 的 index 因回传链路丢失（恒为 -1）、sigKey 仍在。
     *  生成时须用 sigKey 内的 #N 还原下标，渲染成 list.get(index)。 */
    @Test
    public void listStepIndexRecoveredFromSigKeyWhenIndexLost() {
        java.util.List<RoleEntry> es = new ArrayList<>();
        es.add(qr(3)); es.add(qr(4)); es.add(qr(5));
        RoleEntry clicked = new RoleEntry("QR code to download the mobile app", "QR code to download the mobile app",
                null, null, "role", "img", "k_qr", false, null, false, -1, false);
        clicked.setSigKey("role:img:QR code to download the mobile app#3");
        clicked.setPickNos(java.util.Arrays.asList(1, 2, 3));
        PickSnapshot snap = new PickSnapshot(PAGE, es,
                Collections.singletonList(new StepRec(PAGE, Collections.singletonList(clicked))), new ArrayList<>());
        String src = RolePickerCodeAssembler.buildStepCode(snap, PKG, "DemoSteps").get(PAGE);
        assertTrue("list 元素点 3 次应生成带下标的 list.get(index)：" + src, src.contains(".get(3)"));
    }

    /** 现场回归：step pick 完全贫化（sigKey 与 index 均缺失，等同浏览器侧只读回传的 step）。
     *  须从权威内存态 entries 回补下标与 list 标记，避免退化成无下标重复 click()。 */
    @Test
    public void impoverishedStepPickRecoversIndexFromEntries() {
        java.util.List<RoleEntry> es = new ArrayList<>();
        es.add(qr(3)); es.add(qr(4)); es.add(qr(5));
        RoleEntry clicked = new RoleEntry("QR code to download the mobile app", "QR code to download the mobile app",
                null, null, "role", "img", "k_qr", false, null, false, -1, false);
        clicked.setSigKey(null);
        clicked.setPickNos(java.util.Arrays.asList(1, 2, 3));
        PickSnapshot snap = new PickSnapshot(PAGE, es,
                Collections.singletonList(new StepRec(PAGE, Collections.singletonList(clicked))), new ArrayList<>());
        String src = RolePickerCodeAssembler.buildStepCode(snap, PKG, "DemoSteps").get(PAGE);
        assertTrue("贫化 step pick 也应从 entries 回补下标，生成 list.get(index)：" + src, src.contains(".get("));
        assertFalse("不应再退化成无下标重复 click()：" + src,
                src.contains("qrCodeToDownloadTheMobileApp.click()"));
    }
}

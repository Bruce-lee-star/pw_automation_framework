package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 录制 → 模型 → 产物链路上「动作语义」的回归用例（逐行评审 P0-1 / P0-3）。
 *
 * <p>这些逻辑刻意做成不依赖 Playwright / 面板 / CDP 的纯函数，本机即可逐条验证：
 * 勾选态终态解析、重复动作判定、按本次操作生成动作。
 */
public class CodegenLauncherModelTest {

    // ------------------------------------------------------------------
    // P0-1：勾选态终态由「动作语义」推导，不依赖 CDP 抽取与页面默认 toggle 的时序竞态
    // ------------------------------------------------------------------

    @Test
    public void clickOnUncheckedCheckboxResolvesToChecked() {
        CodegenEntry entry = checkbox(false);
        CodegenEntry after = CodegenLauncher.resolveCheckedState(
                entry, Map.of("checked", Boolean.FALSE), "click");
        assertTrue("未勾选 → 点一下 ⇒ 终态已勾选", after.checked);
        assertEquals(entry.sigKey, after.sigKey);
    }

    @Test
    public void clickOnCheckedCheckboxResolvesToUnchecked() {
        CodegenEntry entry = checkbox(true);
        CodegenEntry after = CodegenLauncher.resolveCheckedState(
                entry, Map.of("checked", Boolean.TRUE), "click");
        assertFalse("已勾选 → 点一下 ⇒ 终态已取消", after.checked);
    }

    @Test
    public void clickOnRadioAlwaysResolvesToChecked() {
        CodegenEntry entry = new CodegenEntry("radio|Yes#0", "radio", "Yes", "ROLE", 100,
                "role=radio[name=\"Yes\"]", "#yes", null, 0, List.of(), List.of(), 1, "input", false);
        CodegenEntry after = CodegenLauncher.resolveCheckedState(
                entry, Map.of("checked", Boolean.FALSE), "click");
        assertTrue("radio 点击必然选中，断言不得停留在 is(false)", after.checked);
    }

    @Test
    public void nonClickOrMissingEvidenceKeepsExtractedState() {
        CodegenEntry entry = checkbox(true);
        assertSame("fill 不是 toggle，不得翻转勾选态",
                entry, CodegenLauncher.resolveCheckedState(entry, Map.of("checked", Boolean.FALSE), "fill"));
        assertSame("注入侧无 checked 证据（旧脚本 / 跨源 iframe）时保留抽取快照",
                entry, CodegenLauncher.resolveCheckedState(entry, Map.of(), "click"));
        CodegenEntry button = new CodegenEntry("button|Save#0", "button", "Save", "ROLE", 100,
                "role=button[name=\"Save\"]", "#save", null, 0);
        assertSame("非勾选类元素不参与终态推导",
                button, CodegenLauncher.resolveCheckedState(button, Map.of("checked", Boolean.TRUE), "click"));
    }

    /** N-02：组件库常见的 div[role=checkbox][aria-checked=true]，已选中再点一次必须出 uncheck()。 */
    @Test
    public void ariaCheckboxCheckedBeforeClickResolvesToUncheck() {
        CodegenEntry entry = new CodegenEntry("checkbox|Terms#0", "checkbox", "Terms", "ROLE", 100,
                "role=checkbox[name=\"Terms\"]", "div[role=checkbox]", null, 0,
                List.of(), List.of(), 1, "div", true);

        CodegenEntry after = CodegenLauncher.resolveCheckedState(
                entry, Map.of("checked", Boolean.TRUE), "click");

        assertFalse("aria-checked=true 是点击前状态，点一下应变为未选中", after.checked);
        CodegenFieldSpec spec = new CodegenFieldSpec("terms", "PageElement", "", null,
                "uncheck", "uncheck()", "isChecked()", false, true, after, null);
        assertEquals("uncheck()", CodegenLauncher.actionForPick(spec, pick(after, "click")));
    }

    // ------------------------------------------------------------------
    // T16：连续 fill 的切分与「按本次值生成动作」
    // ------------------------------------------------------------------

    @Test
    public void incrementalTypingMergesButNewValueSplits() {
        assertTrue("打字追加应合并", CodegenLauncher.isIncremental("A", "AB"));
        assertTrue("退格应合并", CodegenLauncher.isIncremental("ABC", "AB"));
        assertTrue("失焦 change 重报同值应合并（不得产生重复动作）",
                CodegenLauncher.isIncremental("AB", "AB"));
        assertFalse("先填 A 再填 B 必须切分", CodegenLauncher.isIncremental("A", "B"));
        assertFalse(CodegenLauncher.isIncremental(null, "A"));
    }

    @Test
    public void fillActionUsesThisPickValueNotFieldTemplate() {
        CodegenEntry user = new CodegenEntry("textbox|Username#0", "textbox", "Username", "ROLE", 100,
                "role=textbox[name=\"Username\"]", "#u", null, 0, List.of(), List.of(), 1, "input");
        CodegenFieldSpec spec = new CodegenFieldSpec("username", "PageElement", "", null,
                "fill", "fill(\"B\")", "isVisible()", false, true, user, "B");

        CodegenLauncher.Pick p = pick(user, "fill");
        p.actionValue = "A";

        assertEquals("第一次填写必须用自己的值", "fill(\"A\")", CodegenLauncher.actionForPick(spec, p));
    }

    @Test
    public void fillActionEscapesValue() {
        CodegenEntry user = new CodegenEntry("textbox|Username#0", "textbox", "Username", "ROLE", 100,
                "role=textbox[name=\"Username\"]", "#u", null, 0, List.of(), List.of(), 1, "input");
        CodegenFieldSpec spec = new CodegenFieldSpec("username", "PageElement", "", null,
                "fill", "fill(\"\")", "isVisible()", false, true, user, "");

        CodegenLauncher.Pick p = pick(user, "fill");
        p.actionValue = "a\"b\\c";

        assertEquals("fill(\"a\\\"b\\\\c\")", CodegenLauncher.actionForPick(spec, p));
    }

    @Test
    public void selectActionUsesThisPickValue() {
        CodegenEntry prov = new CodegenEntry("combobox|Province#0", "combobox", "Province", "ROLE", 100,
                "role=combobox[name=\"Province\"]", "#prov", null, 0, List.of(), List.of(), 1, "select");
        CodegenFieldSpec spec = new CodegenFieldSpec("province", "PageElement", "", null,
                "select", "selectByValue(\"\")", "isVisible()", false, true, prov, "");

        CodegenLauncher.Pick p = pick(prov, "fill");
        p.actionValue = "HK";

        assertEquals("selectByValue(\"HK\")", CodegenLauncher.actionForPick(spec, p));
    }

    // ------------------------------------------------------------------
    // T17：导出目录唯一性（同毫秒不得互相覆盖）
    // ------------------------------------------------------------------

    @Test
    public void exportDirAllocatesUniqueFolderOnCollision() throws Exception {
        Path root = Files.createTempDirectory("codegen-export-root-");
        try {
            LocalDateTime t = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 678_000_000);
            String stamp = "20260102-030405-678";

            Files.createDirectories(root.resolve(stamp));
            Path second = CodegenLauncher.allocateExportDir(root, t);
            assertEquals("同毫秒已被占用时必须加序号", stamp + "-2", second.getFileName().toString());

            Files.createDirectories(second);
            Path third = CodegenLauncher.allocateExportDir(root, t);
            assertEquals(stamp + "-3", third.getFileName().toString());
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // T15：会话级 dialog 策略（不再恒 accept）
    // ------------------------------------------------------------------

    @Test
    public void dialogPolicyControlsAccept() {
        assertTrue(CodegenLauncher.acceptForDialog(CodegenLauncher.DialogPolicy.ACCEPT, "confirm"));
        assertFalse("dismiss 策略必须真的取消，才能录到取消分支",
                CodegenLauncher.acceptForDialog(CodegenLauncher.DialogPolicy.DISMISS, "confirm"));
        assertFalse(CodegenLauncher.acceptForDialog(CodegenLauncher.DialogPolicy.DISMISS, "alert"));
        assertTrue("beforeunload 必须放行导航，否则页面停住",
                CodegenLauncher.acceptForDialog(CodegenLauncher.DialogPolicy.DISMISS, "beforeunload"));
    }

    @Test
    public void dialogPolicyParsingDefaultsToAccept() {
        assertEquals(CodegenLauncher.DialogPolicy.DISMISS,
                CodegenLauncher.parseDialogPolicy(" DISMISS "));
        assertEquals(CodegenLauncher.DialogPolicy.ACCEPT, CodegenLauncher.parseDialogPolicy(null));
        assertEquals("拼错/未知值回落默认 accept", CodegenLauncher.DialogPolicy.ACCEPT,
                CodegenLauncher.parseDialogPolicy("nope"));
    }

    // ------------------------------------------------------------------
    // P0-3：去重键升级为「元素 + 动作签名」
    // ------------------------------------------------------------------

    @Test
    public void identicalPureClickIsDeduplicated() {
        assertTrue(CodegenLauncher.isRepeatedClick("click|click()", "click|click()", "click"));
    }

    @Test
    public void alternatingCheckAndUncheckAreBothKept() {
        assertFalse("勾 → 取消是两次不同动作，不得被吞",
                CodegenLauncher.isRepeatedClick("check|check()", "uncheck|uncheck()", "uncheck"));
    }

    @Test
    public void repeatedNonClickActionIsKept() {
        assertFalse("两次 fill（含不同值提交）不得按连点规则丢弃",
                CodegenLauncher.isRepeatedClick("fill|fill(\"A\")", "fill|fill(\"A\")", "fill"));
        assertFalse(CodegenLauncher.isRepeatedClick(null, "click|click()", "click"));
    }

    @Test
    public void checkboxActionFollowsEachPickState() {
        CodegenFieldSpec spec = spec(checkbox(true), "check()", false);
        CodegenLauncher.Pick first = pick(checkbox(true), "click");
        CodegenLauncher.Pick second = pick(checkbox(false), "click");

        assertEquals("check()", CodegenLauncher.actionForPick(spec, first));
        assertEquals("uncheck()", CodegenLauncher.actionForPick(spec, second));
    }

    @Test
    public void listActionKeepsGetZeroPrefix() {
        CodegenFieldSpec spec = spec(checkbox(false), "uncheck()", true);
        assertEquals("get(0).check()", CodegenLauncher.actionForPick(spec, pick(checkbox(true), "click")));
    }

    /** T14：修饰键必须保留（Ctrl+Enter 不能被压成普通回车），且仍禁止拼入引号/分号。 */
    @Test
    public void modifierKeysArePreserved() {
        CodegenEntry user = new CodegenEntry("textbox|Username#0", "textbox", "Username", "ROLE", 100,
                "role=textbox[name=\"Username\"]", "#u", null, 0, List.of(), List.of(), 1, "input");
        CodegenFieldSpec spec = new CodegenFieldSpec("username", "PageElement", "", null,
                "fill", "fill(\"\")", "isVisible()", false, true, user, "");

        assertEquals("press(\"Control+Enter\")",
                CodegenLauncher.actionForPick(spec, pick(user, "press:Control+Enter")));
        assertEquals("press(\"Shift+Tab\")",
                CodegenLauncher.actionForPick(spec, pick(user, "press:Shift+Tab")));
        assertEquals("非法按键名仍不得拼进源码", "fill(\"\")",
                CodegenLauncher.actionForPick(spec, pick(user, "press:Enter\";boom")));
    }

    /** T14：file 选择框不得生成 fill(fakepath)，而是可编译的 uploadFile 占位（由人工补资源路径）。 */
    @Test
    public void fileSelectionGeneratesUploadPlaceholder() {
        CodegenEntry file = new CodegenEntry("button|Choose file#0", "button", "Choose file", "ROLE", 100,
                "role=button[name=\"Choose file\"]", "#file", null, 0, List.of(), List.of(), 1, "input");
        CodegenFieldSpec spec = new CodegenFieldSpec("chooseFile", "PageElement", "", null,
                "click", "click()", "isVisible()", false, true, file, null);

        assertEquals("uploadFile(\"TODO_REPLACE_WITH_RESOURCE_NAME\")",
                CodegenLauncher.actionForPick(spec, pick(file, "file")));
    }

    @Test
    public void keyboardEventGeneratesPressAction() {
        CodegenFieldSpec spec = spec(checkbox(true), "check()", false);
        assertEquals("press(\"Enter\")",
                CodegenLauncher.actionForPick(spec, pick(checkbox(true), "press:Enter")));
        assertEquals("press(\"Escape\")",
                CodegenLauncher.actionForPick(spec, pick(checkbox(true), "press:Escape")));
        assertEquals("非法按键名不得拼进 Java 源码", "check()",
                CodegenLauncher.actionForPick(spec, pick(checkbox(true), "press:Enter\"); dropTable()")));
    }

    @Test
    public void nonCheckboxUsesFieldPlanAction() {
        CodegenEntry button = new CodegenEntry("button|Save#0", "button", "Save", "ROLE", 100,
                "role=button[name=\"Save\"]", "#save", null, 0);
        CodegenFieldSpec spec = new CodegenFieldSpec("save", "PageElement", "", null,
                "click", "click()", "isVisible()", false, true, button, null);
        assertEquals("click()", CodegenLauncher.actionForPick(spec, pick(button, "click")));
    }

    // ------------------------------------------------------------------

    /** 右键 / 双击必须由「本次录制动作」决定，而不是字段模板里的 click。 */
    @Test
    public void rightClickAndDoubleClickComeFromRecordedAction() {
        CodegenEntry button = new CodegenEntry("button|More#0", "button", "More", "ROLE", 100,
                "role=button[name=\"More\"]", "#more", null, 0, List.of(), List.of(), 1, "button", false);
        CodegenFieldSpec spec = new CodegenFieldSpec("moreBtn", "PageElement", "", null,
                "click", "click()", "isVisible()", false, true, button, null);
        assertEquals("rightClick()", CodegenLauncher.actionForPick(spec, pick(button, "contextmenu")));
        assertEquals("doubleClick()", CodegenLauncher.actionForPick(spec, pick(button, "dblclick")));
        assertEquals("click()", CodegenLauncher.actionForPick(spec, pick(button, "click")));
    }

    /** 右键两次是两次明确动作，不能被「纯连点去重」吞掉。 */
    @Test
    public void rightClickTwiceIsNotDeduplicated() {
        assertFalse(CodegenLauncher.isRepeatedClick("click|rightClick()", "click|rightClick()",
                "click", "contextmenu"));
        assertTrue(CodegenLauncher.isRepeatedClick("click|click()", "click|click()", "click", "click"));
    }

    private static CodegenEntry checkbox(boolean checked) {
        return new CodegenEntry("checkbox|Remember me#0", "checkbox", "Remember me", "ROLE", 100,
                "role=checkbox[name=\"Remember me\"]", "#rm", null, 0,
                List.of(), List.of(), 1, "input", checked);
    }

    private static CodegenFieldSpec spec(CodegenEntry entry, String action, boolean isList) {
        return new CodegenFieldSpec("rememberMe", "PageElement", "", null,
                "check", action, "isChecked()", isList, true, entry, null);
    }

    private static CodegenLauncher.Pick pick(CodegenEntry entry, String actionType) {
        return new CodegenLauncher.Pick(1L, 1L, 1, entry, null, "https://example.test/",
                null, false, true, actionType);
    }
}

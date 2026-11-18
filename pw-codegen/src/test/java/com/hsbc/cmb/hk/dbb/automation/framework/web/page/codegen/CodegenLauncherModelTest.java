package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenFieldSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.model.CodegenEntry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.recorder.CodegenRecorder;
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

    /** P1-01：点一下输入框（无值的 click）不得套用字段模板的 fill("末值")，应产出 click()。 */
    @Test
    public void clickOnTextboxDoesNotGenerateFillWithLastValue() {
        CodegenEntry user = new CodegenEntry("textbox|Username#0", "textbox", "Username", "ROLE", 100,
                "role=textbox[name=\"Username\"]", "#u", null, 0, List.of(), List.of(), 1, "input");
        CodegenFieldSpec spec = new CodegenFieldSpec("username", "PageElement", "", null,
                "fill", "fill(\"B\")", "isVisible()", false, true, user, "B");

        assertEquals("聚焦输入框应产出 click()，不能写成 fill(\"末值\")",
                "click()", CodegenLauncher.actionForPick(spec, pick(user, "click")));
    }

    /** P1-01：fill 事件但无 actionValue 时产出空串占位，绝不写死历史值。 */
    @Test
    public void fillWithoutValueUsesEmptyPlaceholder() {
        CodegenEntry user = new CodegenEntry("textbox|Username#0", "textbox", "Username", "ROLE", 100,
                "role=textbox[name=\"Username\"]", "#u", null, 0, List.of(), List.of(), 1, "input");
        CodegenFieldSpec spec = new CodegenFieldSpec("username", "PageElement", "", null,
                "fill", "fill(\"历史值\")", "isVisible()", false, true, user, "历史值");

        assertEquals("无录制值的 fill 只能是空串占位 + TODO，不得回填历史值",
                "fill(\"\")", CodegenLauncher.actionForPick(spec, pick(user, "fill")));
    }

    /** P1-16：方向键 / 数字键 / 功能键 / 斜杠都是 Playwright 合法键名，白名单不得丢弃。 */
    @Test
    public void pressAcceptsArrowsDigitsFunctionKeysAndSlash() {
        CodegenEntry user = new CodegenEntry("textbox|Username#0", "textbox", "Username", "ROLE", 100,
                "role=textbox[name=\"Username\"]", "#u", null, 0, List.of(), List.of(), 1, "input");
        CodegenFieldSpec spec = new CodegenFieldSpec("username", "PageElement", "", null,
                "fill", "fill(\"\")", "isVisible()", false, true, user, "");

        assertEquals("press(PressKey.ARROW_DOWN)", CodegenLauncher.actionForPick(spec, pick(user, "press:ArrowDown")));
        assertEquals("press(PressKey.DIGIT_1)", CodegenLauncher.actionForPick(spec, pick(user, "press:1")));
        assertEquals("press(PressKey.F5)", CodegenLauncher.actionForPick(spec, pick(user, "press:F5")));
        assertEquals("press(PressKey.SLASH)", CodegenLauncher.actionForPick(spec, pick(user, "press:/")));
        assertEquals("press(PressKey.CONTROL, PressKey.ARROW_UP)",
                CodegenLauncher.actionForPick(spec, pick(user, "press:Control+ArrowUp")));
        // 大小写不一致也归一到枚举，杜绝 "enter"/"Enter" 回放报错
        assertEquals("press(PressKey.ENTER)", CodegenLauncher.actionForPick(spec, pick(user, "press:enter")));
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

    /** P1-13：多选 select 的全部选中值要一次性回放，不能只留首项 + TODO。 */
    @Test
    public void multiSelectGeneratesSelectByValuesWithAllValues() {
        CodegenEntry multi = new CodegenEntry("combobox|Colors#0", "combobox", "Colors", "ROLE", 100,
                "role=combobox[name=\"Colors\"]", "#colors", null, 0,
                List.of(), List.of(), 1, "select");
        CodegenFieldSpec spec = new CodegenFieldSpec("colors", "PageElement", "", null,
                "select", "selectByValue(\"\")", "isVisible()", false, true, multi, "");
        CodegenLauncher.Pick p = pick(multi, "fill");
        p.actionValue = "red";
        p.selectedValues = List.of("red", "green", "blue");
        p.valueCount = 3;

        assertEquals("selectByValues(\"red\", \"green\", \"blue\")",
                CodegenLauncher.actionForPick(spec, p));
    }

    /** P1-13：载荷缺 values（老脚本）时仍回退单值路径，不猜值。 */
    @Test
    public void multiSelectWithoutValuesFallsBackToSingle() {
        CodegenEntry multi = new CodegenEntry("combobox|Colors#0", "combobox", "Colors", "ROLE", 100,
                "role=combobox[name=\"Colors\"]", "#colors", null, 0,
                List.of(), List.of(), 1, "select");
        CodegenFieldSpec spec = new CodegenFieldSpec("colors", "PageElement", "", null,
                "select", "selectByValue(\"\")", "isVisible()", false, true, multi, "");
        CodegenLauncher.Pick p = pick(multi, "fill");
        p.actionValue = "red";
        p.valueCount = 3;

        assertEquals("selectByValue(\"red\")", CodegenLauncher.actionForPick(spec, p));
    }

    /** P1-13：stringListOf 对非法载荷（非数组 / 含非字符串）返回 null，不猜值。 */
    @Test
    public void stringListOfRejectsInvalidPayloads() {
        assertEquals(List.of("A", "B"), CodegenLauncher.stringListOf(List.of("A", "B")));
        assertEquals(null, CodegenLauncher.stringListOf("A"));
        assertEquals(null, CodegenLauncher.stringListOf(List.of("A", 2)));
        assertEquals(null, CodegenLauncher.stringListOf(List.of()));
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

        assertEquals("press(PressKey.CONTROL, PressKey.ENTER)",
                CodegenLauncher.actionForPick(spec, pick(user, "press:Control+Enter")));
        assertEquals("press(PressKey.SHIFT, PressKey.TAB)",
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
        assertEquals("press(PressKey.ENTER)",
                CodegenLauncher.actionForPick(spec, pick(checkbox(true), "press:Enter")));
        assertEquals("press(PressKey.ESCAPE)",
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

    /** 双击两次同理：dblclick 不走连点去重（否则第二次双击被静默吞掉）。 */
    @Test
    public void doubleClickTwiceIsNotDeduplicated() {
        assertFalse(CodegenLauncher.isRepeatedClick("click|doubleClick()", "click|doubleClick()",
                "click", "dblclick"));
    }

    /**
     * 双击的第一下 click 必须并入 doubleClick：浏览器双击序列是 click(detail=1) → click(detail=2) → dblclick，
     * 注入侧只跳过 detail&gt;1 的那次，剩下的 [click, dblclick] 不合并就会生成 click(); doubleClick();
     */
    @Test
    public void doubleClickMergesItsLeadingClick() {
        CodegenEntry button = new CodegenEntry("button|Open#0", "button", "Open", "ROLE", 100,
                "role=button[name=\"Open\"]", "#open", null, 0, List.of(), List.of(), 1, "button", false);

        List<CodegenLauncher.Pick> merged = CodegenLauncher.mergeDoubleClicks(List.of(
                pickAt(button, "click", 1L), pickAt(button, "dblclick", 2L)));

        assertEquals("第一下 click 应被 doubleClick 吸收", 1, merged.size());
        assertEquals("dblclick", merged.get(0).actionType);
    }

    /** 合并只针对「同元素」：双击别的元素不能把上一次单击吞掉。 */
    @Test
    public void doubleClickDoesNotSwallowAnotherElementsClick() {
        CodegenEntry open = new CodegenEntry("button|Open#0", "button", "Open", "ROLE", 100,
                "role=button[name=\"Open\"]", "#open", null, 0, List.of(), List.of(), 1, "button", false);
        CodegenEntry close = new CodegenEntry("button|Close#0", "button", "Close", "ROLE", 100,
                "role=button[name=\"Close\"]", "#close", null, 0, List.of(), List.of(), 1, "button", false);

        List<CodegenLauncher.Pick> merged = CodegenLauncher.mergeDoubleClicks(List.of(
                pickAt(open, "click", 1L), pickAt(close, "dblclick", 2L)));

        assertEquals("不同元素的两条动作都要保留", 2, merged.size());
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

    private static CodegenLauncher.Pick pickAt(CodegenEntry entry, String actionType, long seq) {
        return new CodegenLauncher.Pick(seq, seq, 1, entry, null, "https://example.test/",
                null, false, true, actionType);
    }

    // ------------------------------------------------------------------
    // T08 夹具① / P0-2：两会话「勾选 → 取消」，断言期望值必须按会话取，不得被全局末态覆盖
    // ------------------------------------------------------------------

    /**
     * 会话1 勾选「记住我」→ 会话2 取消。断言类必须产出 {@code assert1=is(true)}、{@code assert2=is(false)}。
     *
     * <p>历史实现把字段视图 checked 取成全局末态（false），两个会话的断言都会被覆盖成 is(false)，回放必红。
     * 这里直接驱动 {@link CodegenLauncher#assembleMultiPage}（package-private），覆盖
     * Pick → buildSession → Assert 源码的完整链路，而不是只测某个纯函数片段。
     */
    @Test
    public void twoSessionsCheckThenUncheck_assertPerSessionState() {
        String url = "https://example.test/portalserver/login";
        // 会话1：勾选（checked=true）；会话2：同一元素取消（checked=false），相同 sigKey
        CodegenEntry checkedInS1 = new CodegenEntry("checkbox|Remember me#0", "checkbox", "Remember me", "ROLE",
                100, "role=checkbox[name=\"Remember me\"]", "#rm", null, 0,
                List.of(), List.of(), 1, "input", true);
        CodegenEntry uncheckedInS2 = new CodegenEntry("checkbox|Remember me#0", "checkbox", "Remember me", "ROLE",
                100, "role=checkbox[name=\"Remember me\"]", "#rm", null, 0,
                List.of(), List.of(), 1, "input", false);
        List<CodegenLauncher.Pick> picks = List.of(
                new CodegenLauncher.Pick(1L, 1L, 1, checkedInS1, null, url, null, false, false, "click"),
                new CodegenLauncher.Pick(2L, 2L, 2, uncheckedInS2, null, url, null, false, true, "click"));

        MultiPageCodegen.MultiPageResult result = CodegenLauncher.assembleMultiPage(
                picks, new CodegenRecorder(), List.of(), null);
        String code = result.assertCode;

        assertTrue("应生成 assert1 方法:\n" + code, code.contains("public void assert1() {"));
        assertTrue("应生成 assert2 方法:\n" + code, code.contains("public void assert2() {"));
        int a1 = code.indexOf("public void assert1() {");
        int a2 = code.indexOf("public void assert2() {");
        assertTrue("assert1 必须在 assert2 之前:\n" + code, a1 >= 0 && a2 > a1);
        String assert1Body = code.substring(a1, a2);
        String assert2Body = code.substring(a2);
        assertTrue("会话1 勾选 ⇒ assert1 期望 is(true):\n" + assert1Body,
                assert1Body.contains("isChecked(), is(true)"));
        assertTrue("会话2 取消 ⇒ assert2 期望 is(false):\n" + assert2Body,
                assert2Body.contains("isChecked(), is(false)"));
    }

    // ------------------------------------------------------------------
    // T08 夹具③ / P1-07/08：press 触发 confirm→alert 链式 dialog，两段都绑定触发
    // ------------------------------------------------------------------

    /**
     * press:Enter 提交触发 confirm，紧随 alert。
     * 历史实现只认 click 触发且第一个 dialog 后清空 lastElement*，两段都退化成独立 acceptAlert()
     * （监听器晚于弹窗，默认 dismiss，回放必错）。修复后两段都应包在 acceptAlert(() -> press(PressKey.ENTER)) 内。
     */
    @Test
    public void pressTriggeredChainedDialogsBothBindTrigger() {
        String url = "https://example.test/portalserver/login";
        CodegenEntry submit = new CodegenEntry("button|Submit#0", "button", "Submit", "ROLE", 100,
                "role=button[name=\"Submit\"]", "#submit", null, 0,
                List.of(), List.of(), 1, "button", false);
        List<CodegenLauncher.Pick> picks = List.of(
                new CodegenLauncher.Pick(1L, 1L, 1, submit, null, url, null, false, false, "press:Enter"));
        List<CodegenLauncher.DialogOp> dialogs = List.of(
                new CodegenLauncher.DialogOp(null, "confirm", true, 2L, 1),
                new CodegenLauncher.DialogOp(null, "alert", true, 3L, 1));

        MultiPageCodegen.MultiPageResult result = CodegenLauncher.assembleMultiPage(
                picks, new CodegenRecorder(), dialogs, null);
        String step = result.stepCode;

        int bound = 0;
        int idx = 0;
        while ((idx = step.indexOf("acceptAlert(() ->", idx)) >= 0) {
            bound++;
            idx += 1;
        }
        assertEquals("confirm + alert 两段都应绑定触发:\n" + step, 2, bound);
        assertTrue("触发动作必须是本步 press(PressKey):\n" + step, step.contains("press(PressKey.ENTER)"));
        assertFalse("不应出现未绑定触发的独立 acceptAlert():\n" + step,
                step.matches("(?s).*acceptAlert\\(\\);.*"));
    }
}

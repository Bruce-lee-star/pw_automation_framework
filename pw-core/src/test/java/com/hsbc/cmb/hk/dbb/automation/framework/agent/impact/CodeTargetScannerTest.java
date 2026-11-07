package com.hsbc.cmb.hk.dbb.automation.framework.agent.impact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodeTargetScanner} 契约测试：页面对象/NLS 资源命中、行号、跳过目录、无信号早退。
 */
public class CodeTargetScannerTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void findsRoleElementAndElementDeclarationsAndNlsEntries() throws Exception {
        Path src = folder.newFolder("src").toPath();
        write(src.resolve("LoginPage.java"), String.join("\n",
                "public class LoginPage {",
                "    @RoleElement(role = AriaRole.TEXTBOX, key = \"user_name\")",
                "    public PageElement userNameInput;",
                "",
                "    @RoleElement(role = AriaRole.BUTTON, key = \"button_next\")",
                "    public PageElement buttonNextBtn;",
                "",
                "    @Element(\"[data-i18n='button_next']\")",
                "    public PageElement nextButton;",
                "}"));
        write(src.resolve("nls/NLS_idv_logon.json"), String.join("\n",
                "{",
                "  \"button_next\": \"Next\",",
                "  \"button_cancel\": \"Cancel\",",
                "  \"wechat_button_next\": \"下一步 Next\"",
                "}"));

        ChangeSignal signal = ChangeSignal.ofElementRefs(Set.of(),
                Set.of(new ElementRef("button", "next")), Set.of(), Set.of());
        CodeTargetScanner.ScanResult result = CodeTargetScanner.scan(List.of(src), signal);

        List<CodeTarget> targets = result.targets();
        assertEquals(targets.toString(), 3, targets.size());
        assertTrue(targets.toString(), targets.stream().allMatch(t -> t.kind() == CodeTarget.Kind.PAGE_OBJECT
                || t.kind() == CodeTarget.Kind.RESOURCE));
        assertEquals(2, targets.stream().filter(t -> t.kind() == CodeTarget.Kind.PAGE_OBJECT).count());
        assertEquals(1, targets.stream().filter(t -> t.kind() == CodeTarget.Kind.RESOURCE).count());

        CodeTarget roleElement = targets.stream()
                .filter(t -> "button_next".equals(t.identifier()))
                .filter(t -> t.kind() == CodeTarget.Kind.PAGE_OBJECT)
                .findFirst().orElseThrow();
        assertEquals("LoginPage.java:5", roleElement.file() + ":" + roleElement.line());
        assertTrue(roleElement.snippet(), roleElement.snippet().contains("@RoleElement"));

        CodeTarget nls = targets.stream()
                .filter(t -> t.kind() == CodeTarget.Kind.RESOURCE)
                .findFirst().orElseThrow();
        assertEquals("nls/NLS_idv_logon.json:2", nls.file() + ":" + nls.line());
        assertEquals("button_next", nls.identifier());
    }

    @Test
    public void roleNarrowingKeepsSameNameDifferentControlOut() throws Exception {
        Path src = folder.newFolder("srcRole").toPath();
        write(src.resolve("LoginPage.java"), String.join("\n",
                "public class LoginPage {",
                "    @RoleElement(role = AriaRole.BUTTON, key = \"username\")",
                "    public PageElement forgotUsernameBtn;",
                "",
                "    @RoleElement(role = AriaRole.HEADING, key = \"username\")",
                "    public PageElement titleUsername;",
                "",
                "    @RoleElement(role = AriaRole.TEXTBOX, key = \"username\")",
                "    public PageElement usernameInput;",
                "}"));

        ChangeSignal withRole = ChangeSignal.ofElementRefs(Set.of(),
                Set.of(new ElementRef("textbox", "username")), Set.of(), Set.of());
        List<CodeTarget> targets = CodeTargetScanner.scan(List.of(src), withRole).targets();
        assertEquals(targets.toString(), 1, targets.size());
        assertEquals("LoginPage.java:8", targets.get(0).file() + ":" + targets.get(0).line());

        ChangeSignal roleless = ChangeSignal.of(Set.of(), Set.of("username"), Set.of(), Set.of());
        assertEquals(3, CodeTargetScanner.scan(List.of(src), roleless).targets().size());
    }

    @Test
    public void resourceValueEqualToElementNameIsHitWithoutDeclaration() throws Exception {
        Path src = folder.newFolder("srcV").toPath();
        write(src.resolve("nls/labels.json"), String.join("\n",
                "{",
                "  \"some_label\": \"Next\",",
                "  \"other_label\": \"下一步 Next\"",
                "}"));

        ChangeSignal signal = ChangeSignal.of(Set.of(), Set.of("next"), Set.of(), Set.of());
        CodeTargetScanner.ScanResult result = CodeTargetScanner.scan(List.of(src), signal);

        assertEquals(result.targets().toString(), 1, result.targets().size());
        assertEquals("some_label", result.targets().get(0).identifier());
    }

    @Test
    public void selectorSignalMatchesByStableAnchorsAndFillsNlsKeys() throws Exception {
        Path src = folder.newFolder("srcSelector").toPath();
        write(src.resolve("LoginPage.java"), String.join("\n",
                "public class LoginPage {",
                "    @Element(\"[data-i18n='button_next']\")",
                "    public PageElement nextButton;",
                "",
                "    @Element(\"#a1b2c3d4\")",
                "    public PageElement randomIdButton;",
                "}"));
        write(src.resolve("nls/labels.json"), String.join("\n",
                "{",
                "  \"button_next\": \"Next\"",
                "}"));

        // 前缀选择器与声明 [data-i18n='button_next'] 命中；data-i18n 键反查出 NLS 条目
        ChangeSignal signal = ChangeSignal.of(Set.of(), Set.of(), Set.of(), Set.of())
                .withSelectors(Set.of("[data-i18n^='button_']"));
        List<CodeTarget> targets = CodeTargetScanner.scan(List.of(src), signal).targets();

        assertEquals(targets.toString(), 2, targets.size());
        assertEquals("[data-i18n='button_next']", targets.get(0).identifier());
        assertEquals(SelectorFacts.Stability.STABLE.label(), targets.get(0).stability());
        assertEquals("button_next", targets.get(1).identifier());
        assertEquals(CodeTarget.Kind.RESOURCE, targets.get(1).kind());
    }

    @Test
    public void identicalVolatileSelectorIsMatchedAndAdvised() throws Exception {
        Path src = folder.newFolder("srcVolatile").toPath();
        write(src.resolve("Page.java"), "@Element(\"#a1b2c3d4\")");

        ChangeSignal signal = ChangeSignal.of(Set.of(), Set.of(), Set.of(), Set.of())
                .withSelectors(Set.of("#a1b2c3d4"));
        List<CodeTarget> targets = CodeTargetScanner.scan(List.of(src), signal).targets();

        assertEquals(targets.toString(), 1, targets.size());
        assertEquals(SelectorFacts.Stability.ANCHORLESS.label(), targets.get(0).stability());
        assertTrue(targets.get(0).advice(), targets.get(0).advice().contains("父级"));
    }

    @Test
    public void skipsBuildDirectoriesAndUnscannedSuffixes() throws Exception {
        Path src = folder.newFolder("src2").toPath();
        write(src.resolve("target/Generated.java"),
                "@RoleElement(role = AriaRole.BUTTON, key = \"button_next\")");
        write(src.resolve("notes.txt"), "@RoleElement(role = AriaRole.BUTTON, key = \"button_next\")");

        ChangeSignal signal = ChangeSignal.of(Set.of(), Set.of("next"), Set.of(), Set.of());
        CodeTargetScanner.ScanResult result = CodeTargetScanner.scan(List.of(src), signal);

        assertTrue(result.targets().toString(), result.targets().isEmpty());
    }

    @Test
    public void returnsEmptyWithoutElementSignal() throws Exception {
        Path src = folder.newFolder("src3").toPath();
        write(src.resolve("LoginPage.java"),
                "@RoleElement(role = AriaRole.BUTTON, key = \"button_next\")");

        ChangeSignal keywordOnly = ChangeSignal.of(Set.of(), Set.of(), Set.of("logon"), Set.of());
        CodeTargetScanner.ScanResult result = CodeTargetScanner.scan(List.of(src), keywordOnly);

        assertTrue(result.targets().isEmpty());
        assertEquals(0, result.scannedFiles());
        assertFalse(result.truncated());
    }

    @Test
    public void substringOfALongerWordDoesNotMatch() throws Exception {
        Path src = folder.newFolder("src4").toPath();
        write(src.resolve("Page.java"), "@RoleElement(role = AriaRole.LINK, key = \"nextgen_banner\")");

        ChangeSignal signal = ChangeSignal.of(Set.of(), Set.of("next"), Set.of(), Set.of());
        CodeTargetScanner.ScanResult result = CodeTargetScanner.scan(List.of(src), signal);

        assertTrue(result.targets().toString(), result.targets().isEmpty());
    }

    private static void write(Path file, String content) throws Exception {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}

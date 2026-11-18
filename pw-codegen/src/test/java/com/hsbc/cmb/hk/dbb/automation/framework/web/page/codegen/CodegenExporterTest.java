package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.MultiPageCodegen;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 产物出口（评审 P0-6）：一个代码块 = 一个 UTF-8 {@code .java} 文件，文件名安全且互不覆盖。 */
public class CodegenExporterTest {

    @Test
    public void writesOneJavaFilePerBlock() throws Exception {
        Path dir = Files.createTempDirectory("codegen-export-");
        try {
            MultiPageCodegen.MultiPageResult result = new MultiPageCodegen.MultiPageResult(
                    "PAGE_CODE", "STEP_CODE", "ASSERT_CODE",
                    List.of(block("LoginPage", "public class LoginPage {}"),
                            block("HomePage", "public class HomePage {}")),
                    List.of(block("LoginSteps", "public class LoginSteps {}")),
                    List.of(block("LoginAssert", "public class LoginAssert {}")));

            Path out = CodegenExporter.export(result, dir);

            assertEquals(dir, out);
            assertEquals("public class LoginPage {}", Files.readString(dir.resolve("LoginPage.java")));
            assertEquals("public class HomePage {}", Files.readString(dir.resolve("HomePage.java")));
            assertEquals("public class LoginSteps {}", Files.readString(dir.resolve("LoginSteps.java")));
            assertEquals("public class LoginAssert {}", Files.readString(dir.resolve("LoginAssert.java")));
            try (var files = Files.list(dir)) {
                assertEquals(4, files.count());
            }
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void unsafeLabelsAreSanitizedAndDuplicatesNeverOverwrite() throws Exception {
        Path dir = Files.createTempDirectory("codegen-export-");
        try {
            MultiPageCodegen.MultiPageResult result = new MultiPageCodegen.MultiPageResult(
                    "", "", "",
                    List.of(block("../../Login/Page", "A"), block("LoginPage", "B"),
                            block("LoginPage", "C"), block("", "D")),
                    List.of(), List.of());

            CodegenExporter.export(result, dir);

            try (var names = Files.list(dir)) {
                for (Path f : names.toList()) {
                    // Path#getFileName() 对根路径返回 null：显式断言，避免 NPE 伪装成断言失败
                    // （也是 SpotBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE 的语义收口）。
                    Path fileName = f.getFileName();
                    assertNotNull("导出文件必须有文件名: " + f, fileName);
                    assertTrue("文件名不得带路径分隔符: " + fileName,
                            fileName.toString().matches("[A-Za-z0-9_]+\\.java"));
                }
            }
            try (var files = Files.list(dir)) {
                assertEquals("4 个块必须各占一个文件，重名不得覆盖", 4, files.count());
            }
            assertEquals("GeneratedCode.java",
                    CodegenExporter.sanitize("") + ".java");
            assertEquals("Code_1Page.java", CodegenExporter.sanitize("1Page") + ".java");
        } finally {
            deleteTree(dir);
        }
    }

    /** package 声明必须落成同名子目录：产物拷进 src/main/java 后路径与包名一致（T17）。 */
    @Test
    public void packageDeclarationCreatesMatchingSubdirectories() throws Exception {
        Path dir = Files.createTempDirectory("codegen-export-");
        try {
            String pageCode = "package com.acme.pages;\n\npublic class LoginPage {}";
            MultiPageCodegen.MultiPageResult result = new MultiPageCodegen.MultiPageResult(
                    "", "", "",
                    List.of(block("LoginPage", pageCode)),
                    List.of(block("LoginSteps", "package com.acme.steps;\npublic class LoginSteps {}")),
                    List.of());

            CodegenExporter.export(result, dir);

            Path page = dir.resolve("com/acme/pages/LoginPage.java");
            assertTrue("必须按 package 建子目录", Files.exists(page));
            assertEquals(pageCode, Files.readString(page));
            assertTrue(Files.exists(dir.resolve("com/acme/steps/LoginSteps.java")));
        } finally {
            deleteTree(dir);
        }
    }

    /** 非法 package 声明不得参与路径拼接（防路径穿越），产物落回导出根目录。 */
    @Test
    public void suspiciousPackageCannotEscapeExportDir() throws Exception {
        Path dir = Files.createTempDirectory("codegen-export-");
        try {
            MultiPageCodegen.MultiPageResult result = new MultiPageCodegen.MultiPageResult(
                    "", "", "",
                    List.of(block("Evil", "package ../../evil;\npublic class Evil {}")),
                    List.of(), List.of());

            CodegenExporter.export(result, dir);

            assertTrue("非法 package 应退回根目录", Files.exists(dir.resolve("Evil.java")));
            assertEquals("", CodegenExporter.packageOf("package ../../evil;"));
            assertEquals("", CodegenExporter.packageOf("package com.acme; import x;"));
        } finally {
            deleteTree(dir);
        }
    }

    /** P2-04：文件名必须与源码里的 public class 同名（含中文类名），否则拷进工程 javac 报错。 */
    @Test
    public void fileNameMatchesPublicClassEvenForCjkName() throws Exception {
        Path dir = Files.createTempDirectory("codegen-export-");
        try {
            MultiPageCodegen.MultiPageResult result = new MultiPageCodegen.MultiPageResult(
                    "public class 登录页 {}", "", "",
                    List.of(block("登录页", "public class 登录页 {}")), List.of(), List.of());
            CodegenExporter.export(result, dir);

            assertTrue("必须按 public class 名命名，而不是把中文净化成下划线",
                    Files.exists(dir.resolve("登录页.java")));
            assertEquals("public class 登录页 {}", Files.readString(dir.resolve("登录页.java")));
            assertEquals("登录页", CodegenExporter.publicClassName("public class 登录页 extends Base {}"));
            assertEquals("LoginPage", CodegenExporter.publicClassName("public class LoginPage {"));
            assertEquals(null, CodegenExporter.publicClassName("// public class Fake {"));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void missingOrEmptyResultIsRejected() throws Exception {
        assertThrows(IllegalStateException.class, () -> CodegenExporter.export(null, Path.of("target")));
        Path dir = Files.createTempDirectory("codegen-export-");
        try {
            MultiPageCodegen.MultiPageResult empty = new MultiPageCodegen.MultiPageResult(
                    "", "", "", List.of(), List.of(), List.of());
            assertThrows(IllegalStateException.class, () -> CodegenExporter.export(empty, dir));
        } finally {
            deleteTree(dir);
        }
    }

    private static MultiPageCodegen.CodeBlock block(String label, String code) {
        return new MultiPageCodegen.CodeBlock(label, code);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}

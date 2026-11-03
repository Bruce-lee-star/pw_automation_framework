package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link CodegenFileWriter} 契约测试：生成标记识别 + 「先全查、后全写」的冲突语义。
 */
public class CodegenFileWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String marked(String body) {
        return "// " + CodegenMarkers.GENERATED_MARKER + "\n" + body;
    }

    @Test
    public void missing_file_is_not_generated() throws Exception {
        assertFalse(CodegenFileWriter.isGenerated(tmp.getRoot().toPath().resolve("nope.java")));
    }

    @Test
    public void handwritten_file_is_not_generated() throws Exception {
        Path f = tmp.newFile("Hand.java").toPath();
        Files.writeString(f, "public class Hand {}\n", StandardCharsets.UTF_8);
        assertFalse(CodegenFileWriter.isGenerated(f));
    }

    @Test
    public void marked_file_is_generated_even_with_bom() throws Exception {
        Path f = tmp.newFile("Gen.java").toPath();
        Files.writeString(f, "\uFEFF" + marked("public class Gen {}\n"), StandardCharsets.UTF_8);
        assertTrue(CodegenFileWriter.isGenerated(f));
    }

    @Test
    public void marked_file_can_be_overwritten() throws Exception {
        Path f = tmp.newFile("Gen.java").toPath();
        Files.writeString(f, marked("old\n"), StandardCharsets.UTF_8);

        CodegenFileWriter.writeGenerated(f, marked("new\n"));

        assertEquals(marked("new\n"), Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    public void conflict_blocks_all_writes() throws Exception {
        Path feature = tmp.getRoot().toPath().resolve("out/a.feature");
        Path glue = tmp.newFile("Handwritten.java").toPath();
        Files.writeString(glue, "public class Handwritten {}\n", StandardCharsets.UTF_8);

        Map<Path, String> outputs = new LinkedHashMap<>();
        outputs.put(feature, "# " + CodegenMarkers.GENERATED_MARKER + "\nFeature: A\n");
        outputs.put(glue, "// " + CodegenMarkers.GENERATED_MARKER + "\npublic class Handwritten {}\n");

        try {
            CodegenFileWriter.writeAllGenerated(outputs);
            fail("手写产物应拒绝覆盖");
        } catch (CodegenConflictException e) {
            assertTrue("异常指向手写文件", e.file().equals(glue));
            assertTrue(e.getMessage().contains(CodegenMarkers.GENERATED_MARKER));
        }
        assertFalse("冲突时不得先写 feature", Files.exists(feature));
        assertEquals("手写内容原样保留", "public class Handwritten {}\n",
                Files.readString(glue, StandardCharsets.UTF_8));
    }
}

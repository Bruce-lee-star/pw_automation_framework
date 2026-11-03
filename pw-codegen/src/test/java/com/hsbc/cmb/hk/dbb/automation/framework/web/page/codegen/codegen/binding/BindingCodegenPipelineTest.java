package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenConflictException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.codegen.CodegenMarkers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link BindingCodegenPipeline} 契约测试：计划文件/JSON 两条入口、可重复生成、手写产物拒绝覆盖。
 */
public class BindingCodegenPipelineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path featureDir;
    private Path glueDir;

    @Before
    public void setUp() throws Exception {
        featureDir = tmp.getRoot().toPath().resolve("resources/features");
        glueDir = tmp.getRoot().toPath().resolve("java/com/example/glue");
    }

    private static String fixture() throws Exception {
        try (InputStream in = BindingCodegenPipelineTest.class
                .getResourceAsStream("/codegen/binding/role_element_login.binding.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture 缺失");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void json_entry_writes_both_artifacts_and_creates_dirs() throws Exception {
        BindingCodegenResult r = BindingCodegenPipeline.runJson(fixture(), featureDir, glueDir);

        Path feature = featureDir.resolve("role_element_login.feature");
        Path glue = glueDir.resolve("RoleElementLoginGlue.java");
        assertEquals(feature, r.featureFile());
        assertEquals(glue, r.glueFile());
        assertTrue(Files.exists(feature));
        assertTrue(Files.exists(glue));

        String featureText = Files.readString(feature, StandardCharsets.UTF_8);
        assertTrue(featureText.startsWith("# " + CodegenMarkers.GENERATED_MARKER));
        assertTrue(featureText.contains("\n    When I log on as \"alice\" with role \"maker\"\n"));
        assertTrue(Files.readString(glue, StandardCharsets.UTF_8).contains("steps.logon(username, role);"));
    }

    @Test
    public void rerun_overwrites_previous_generated_artifacts() throws Exception {
        BindingCodegenPipeline.runJson(fixture(), featureDir, glueDir);
        Path glue = glueDir.resolve("RoleElementLoginGlue.java");
        Files.writeString(glue, "# " + CodegenMarkers.GENERATED_MARKER + "\n// stale\n", StandardCharsets.UTF_8);

        BindingCodegenPipeline.runJson(fixture(), featureDir, glueDir);

        assertFalse("旧产物被新产物替换", Files.readString(glue, StandardCharsets.UTF_8).contains("stale"));
        assertTrue(Files.readString(glue, StandardCharsets.UTF_8).contains("public class RoleElementLoginGlue"));
    }

    @Test
    public void handwritten_glue_blocks_both_writes() throws Exception {
        Files.createDirectories(glueDir);
        Path glue = glueDir.resolve("RoleElementLoginGlue.java");
        String handwritten = "public class RoleElementLoginGlue {\n    // 手写改动\n}\n";
        Files.writeString(glue, handwritten, StandardCharsets.UTF_8);

        try {
            BindingCodegenPipeline.runJson(fixture(), featureDir, glueDir);
            fail("手写 Glue 应拒绝覆盖");
        } catch (CodegenConflictException e) {
            assertTrue(e.file().equals(glue));
        }
        assertFalse("冲突时 feature 也不落盘", Files.exists(featureDir.resolve("role_element_login.feature")));
        assertEquals(handwritten, Files.readString(glue, StandardCharsets.UTF_8));
    }

    @Test
    public void plan_file_entry_reads_utf8_json() throws Exception {
        Path planFile = tmp.getRoot().toPath().resolve("plans/role_element_login.json");
        Files.createDirectories(planFile.getParent());
        Files.writeString(planFile, fixture(), StandardCharsets.UTF_8);

        BindingCodegenResult r = BindingCodegenPipeline.run(planFile, featureDir, glueDir);

        assertTrue(Files.exists(r.featureFile()));
        assertTrue(Files.exists(r.glueFile()));
    }

    @Test
    public void invalid_plan_is_rejected_before_touching_disk() throws Exception {
        String json = """
                {
                  "featureName": "Mismatch",
                  "stepsClass": "LoginSteps",
                  "scenarios": [{"name": "S", "steps": [
                    {"keyword": "When", "phrase": "I log on as {string}",
                     "params": [{"name": "username", "type": "string"}],
                     "args": ["alice", "extra"], "delegate": "logon", "forward": true}
                  ]}]
                }
                """;

        try {
            BindingCodegenPipeline.runJson(json, featureDir, glueDir);
            fail("占位符与字面值不匹配应拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("字面值"));
        }
        assertFalse(Files.exists(featureDir));
        assertFalse(Files.exists(glueDir));
    }
}

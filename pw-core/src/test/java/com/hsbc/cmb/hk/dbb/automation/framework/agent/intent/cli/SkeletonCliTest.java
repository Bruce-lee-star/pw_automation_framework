package com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.cli;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedSkeleton;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.intent.RecordedStep;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkeletonCli} 契约测试：两个子命令的退出码、文件/标准流行为、脱敏默认值。
 *
 * <p>只验「参数解析 + IO + 退出码」，策略正确性由底层类各自的测试保证。</p>
 */
public class SkeletonCliTest {

    private static final String PAGE_REF = "https://www.example.com/logon";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    // ------------------------------------------------------------------
    // prompt
    // ------------------------------------------------------------------

    @Test
    public void promptWritesRedactedBundleToFile() throws Exception {
        Path skeletonFile = writeSkeleton();

        int code = run(new String[]{"prompt", skeletonFile.toString(),
                "--out", folder.getRoot().toPath().resolve("prompt.md").toString()});

        assertEquals(0, code);
        String bundle = Files.readString(
                folder.getRoot().toPath().resolve("prompt.md"), StandardCharsets.UTF_8);
        assertTrue("值须占位化", bundle.contains("${value}"));
        assertFalse("业务值不得写入提示词", bundle.contains("alice"));
        assertTrue(out(), out().contains("提示词已写入"));
    }

    @Test
    public void promptWithoutOutPrintsToStdout() throws Exception {
        Path skeletonFile = writeSkeleton();

        assertEquals(0, run(new String[]{"prompt", skeletonFile.toString()}));
        assertTrue(out(), out().contains("## 允许的动作"));
    }

    @Test
    public void promptKeepValuesKeepsOriginalValue() throws Exception {
        Path skeletonFile = writeSkeleton();

        assertEquals(0, run(new String[]{"prompt", skeletonFile.toString(), "--keep-values"}));
        assertTrue(out(), out().contains("alice"));
    }

    // ------------------------------------------------------------------
    // check
    // ------------------------------------------------------------------

    @Test
    public void checkAcceptsEquivalentSuggestion() throws Exception {
        Path skeletonFile = writeSkeleton(singleClickSkeleton());
        Path reply = writeReply("好的，结果如下：\n```json\n" + clickPlan() + "\n```\n");

        int code = run(new String[]{"check", skeletonFile.toString(), reply.toString(),
                "--origin", "codegen"});

        assertEquals(out(), 0, code);
        assertTrue(out(), out().contains("equivalent=true"));
    }

    @Test
    public void checkReportsDivergenceWithRejectedExitCode() throws Exception {
        Path skeletonFile = writeSkeleton(singleClickSkeleton());
        Path reply = writeReply(clickPlan().replace("name=Submit", "name=Go"));

        int code = run(new String[]{"check", skeletonFile.toString(), reply.toString(),
                "--origin", "codegen"});

        assertEquals(1, code);
        assertTrue(out(), out().contains("equivalent=false"));
    }

    @Test
    public void checkReadsSuggestionFromStdin() throws Exception {
        Path skeletonFile = writeSkeleton(singleClickSkeleton());
        InputStream stdin = new ByteArrayInputStream(
                clickPlan().getBytes(StandardCharsets.UTF_8));

        int code = SkeletonCli.run(
                new String[]{"check", skeletonFile.toString(), "-", "--origin", "codegen"},
                stdin, newPrintStream(stdout), newPrintStream(stderr));

        assertEquals(out(), 0, code);
    }

    // ------------------------------------------------------------------
    // 用法 / 输入错误
    // ------------------------------------------------------------------

    @Test
    public void checkRequiresApprovedOrigin() throws Exception {
        Path skeletonFile = writeSkeleton(singleClickSkeleton());
        Path reply = writeReply(clickPlan());

        assertEquals(2, run(new String[]{"check", skeletonFile.toString(), reply.toString()}));
        assertTrue(err(), err().contains("--origin"));
    }

    @Test
    public void unknownSubcommandPrintsUsage() {
        assertEquals(2, run(new String[]{"translate", "x"}));
        assertTrue(err(), err().contains("用法"));
    }

    @Test
    public void missingSkeletonFileIsInputError() {
        assertEquals(2, run(new String[]{"prompt", folder.getRoot().toPath()
                .resolve("nope.json").toString()}));
        assertTrue(err(), err().contains("不可读") || err().contains("不存在"));
    }

    @Test
    public void helpExitsZero() {
        assertEquals(0, run(new String[]{"--help"}));
        assertTrue(out(), out().contains("退出码"));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private int run(String[] args) {
        return SkeletonCli.run(args, new ByteArrayInputStream(new byte[0]),
                newPrintStream(stdout), newPrintStream(stderr));
    }

    private static PrintStream newPrintStream(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    private Path writeSkeleton() throws Exception {
        return writeSkeleton(twoStepSkeleton());
    }

    private Path writeSkeleton(RecordedSkeleton skeleton) throws Exception {
        File file = folder.newFile("skeleton-" + System.nanoTime() + ".json");
        Files.writeString(file.toPath(), skeleton.toJson(), StandardCharsets.UTF_8);
        return file.toPath();
    }

    private Path writeReply(String content) throws Exception {
        File file = folder.newFile("reply-" + System.nanoTime() + ".md");
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file.toPath();
    }

    private static RecordedSkeleton twoStepSkeleton() {
        return new RecordedSkeleton(RecordedSkeleton.SUPPORTED_SCHEMA_VERSION, "codegen", PAGE_REF,
                List.of(RecordedStep.of("NAVIGATE", null, "url", PAGE_REF),
                        new RecordedStep("FILL", "role=textbox;name=User", Map.of("value", "alice"))));
    }

    private static RecordedSkeleton singleClickSkeleton() {
        return new RecordedSkeleton(RecordedSkeleton.SUPPORTED_SCHEMA_VERSION, "codegen", PAGE_REF,
                List.of(new RecordedStep("CLICK", "role=button;name=Submit", Map.of())));
    }

    /** 与 RuleBasedIntentTranslator 的基线同构：plan 级 url 取值域 + 单 CLICK。 */
    private static String clickPlan() {
        return "{\"schemaVersion\":1,\"planId\":\"plan-from-copilot\","
                + "\"scope\":{\"url\":{\"allow\":[\"" + PAGE_REF + "\"]}},"
                + "\"steps\":[{\"action\":\"CLICK\",\"target\":\"role=button;name=Submit\"}]}";
    }
}

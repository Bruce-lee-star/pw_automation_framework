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
    // impact
    // ------------------------------------------------------------------

    @Test
    public void impactMarksElementHitsAsMustMaintainAndExitsRejected() throws Exception {
        Path features = writeFeatures();
        Path skeletonFile = writeSkeleton(singleClickSkeleton());

        int code = run(new String[]{"impact", "--features", features.toString(),
                "--skeleton", skeletonFile.toString()});

        assertEquals(out(), 1, code);
        assertTrue(out(), out().contains("必须维护 1"));
        assertTrue(out(), out().contains("正确密码登录"));
        assertTrue(out(), out().contains("element:submit"));
    }

    @Test
    public void impactOnlyActionableDropsNoChangeRows() throws Exception {
        Path features = writeFeatures();
        Path skeletonFile = writeSkeleton(singleClickSkeleton());
        Path report = folder.getRoot().toPath().resolve("impact.md");

        int code = run(new String[]{"impact", "--features", features.toString(),
                "--skeleton", skeletonFile.toString(), "--only-actionable",
                "--out", report.toString()});

        assertEquals(1, code);
        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(markdown, markdown.contains("正确密码登录"));
        assertFalse(markdown, markdown.contains("切换 profile"));
        assertTrue(markdown, markdown.contains("已过滤 1 条"));
        assertTrue(out(), out().contains("影响报告已写入"));
    }

    @Test
    public void impactWeakKeywordOnlyYieldsReviewAndSuccessExit() throws Exception {
        Path features = writeFeatures();

        int code = run(new String[]{"impact", "--features", features.toString(),
                "--keyword", "登录"});

        assertEquals(out(), 0, code);
        assertTrue(out(), out().contains("需复核 2"));
    }

    @Test
    public void impactTagAcceptsMissingAtPrefix() throws Exception {
        Path features = writeFeatures();

        int code = run(new String[]{"impact", "--features", features.toString(),
                "--tag", "profile"});

        assertEquals(out(), 1, code);
        assertTrue(out(), out().contains("必须维护 1"));
        assertTrue(out(), out().contains("切换 profile"));
    }

    @Test
    public void impactWithSourcesReportsCodeTargets() throws Exception {
        Path features = writeFeatures();
        Path skeletonFile = writeSkeleton(singleClickSkeleton());
        Path sources = writeSources();
        Path report = folder.getRoot().toPath().resolve("impact-code.md");

        int code = run(new String[]{"impact", "--features", features.toString(),
                "--skeleton", skeletonFile.toString(),
                "--sources", sources.toString(), "--out", report.toString()});

        assertEquals(1, code);
        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(markdown, markdown.contains("代码层落点"));
        assertTrue(markdown, markdown.contains("LoginPage.java:2"));
        assertTrue(markdown, markdown.contains("button_submit"));
        assertTrue(out(), out().contains("代码落点 1"));
    }

    @Test
    public void impactWithoutSourcesSaysNotProvided() throws Exception {
        Path features = writeFeatures();
        Path skeletonFile = writeSkeleton(singleClickSkeleton());
        Path report = folder.getRoot().toPath().resolve("impact-nosrc.md");

        assertEquals(1, run(new String[]{"impact", "--features", features.toString(),
                "--skeleton", skeletonFile.toString(), "--out", report.toString()}));
        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(markdown, markdown.contains("请确认 `--sources`"));
    }

    @Test
    public void impactMissingSourcesDirIsInputError() throws Exception {
        Path features = writeFeatures();

        assertEquals(2, run(new String[]{"impact", "--features", features.toString(),
                "--keyword", "登录", "--sources",
                folder.getRoot().toPath().resolve("no-src").toString()}));
        assertTrue(err(), err().contains("--sources"));
    }

    @Test
    public void impactAcceptsSelectorSignalAndTracksStability() throws Exception {
        Path features = writeFeatures();
        Path sources = writeSelectorSources();
        Path report = folder.getRoot().toPath().resolve("impact-selector.md");

        int code = run(new String[]{"impact", "--features", features.toString(),
                "--selector", "[data-i18n^='button_']", "--sources", sources.toString(),
                "--out", report.toString()});

        assertEquals(out(), 0, code);
        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(markdown, markdown.contains("[data-i18n='button_next']"));
        assertTrue(markdown, markdown.contains("稳定性"));
        assertTrue(markdown, markdown.contains("含随机值"));
        assertTrue(markdown, markdown.contains("建议"));
    }

    @Test
    public void impactRejectsInvalidSelector() throws Exception {
        Path features = writeFeatures();

        assertEquals(2, run(new String[]{"impact", "--features", features.toString(),
                "--selector", "   "}));
        assertTrue(err(), err().contains("选择器不合法"));
    }

    @Test
    public void impactEmptySignalIsInputError() throws Exception {
        Path features = writeFeatures();

        assertEquals(2, run(new String[]{"impact", "--features", features.toString()}));
        assertTrue(err(), err().contains("变更信号为空"));
    }

    @Test
    public void impactMissingFeaturesDirIsInputError() {
        assertEquals(2, run(new String[]{"impact",
                "--features", folder.getRoot().toPath().resolve("nope").toString(),
                "--keyword", "logon"}));
        assertTrue(err(), err().contains("不存在"));
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

    /** 源码夹具：一行 @RoleElement 命中骨架元素名 Submit。 */
    private Path writeSources() throws Exception {
        File dir = folder.newFolder("src");
        String content = String.join("\n",
                "public class LoginPage {",
                "    @RoleElement(role = AriaRole.BUTTON, key = \"button_submit\")",
                "}");
        Files.writeString(new File(dir, "LoginPage.java").toPath(), content, StandardCharsets.UTF_8);
        return dir.toPath();
    }

    /** 选择器夹具：稳定属性 + 随机 id 混合，用于验证稳定性列与建议。 */
    private Path writeSelectorSources() throws Exception {
        File dir = folder.newFolder("src-selector");
        String content = String.join("\n",
                "public class LoginPage {",
                "    @Element(\"[data-i18n='button_next']#a1b2c3d4\")",
                "    public PageElement nextButton;",
                "}");
        Files.writeString(new File(dir, "LoginPage.java").toPath(), content, StandardCharsets.UTF_8);
        return dir.toPath();
    }

    /** 存量用例夹具：一条命中元素信号、一条只命中 @profile 标签。 */
    private Path writeFeatures() throws Exception {
        File dir = folder.newFolder("features");
        String content = String.join("\n",
                "@login",
                "Feature: DBB 登录",
                "",
                "  # 注释行不计入",
                "  Scenario: 正确密码登录",
                "    Given 打开登录页",
                "    When 点击 Submit 按钮",
                "    Then 看到首页",
                "",
                "  @profile",
                "  Scenario: 切换 profile",
                "    Given 打开登录页",
                "    When 切换 profile",
                "    Then 看到首页",
                "");
        Files.writeString(new File(dir, "login_dbb.feature").toPath(), content, StandardCharsets.UTF_8);
        return dir.toPath();
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

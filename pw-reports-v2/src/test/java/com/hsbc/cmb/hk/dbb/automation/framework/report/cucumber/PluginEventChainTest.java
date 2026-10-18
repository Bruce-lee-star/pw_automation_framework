package com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import com.hsbc.cmb.hk.dbb.automation.framework.report.render.JsonWriter;
import io.cucumber.plugin.event.Argument;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.HookTestStep;
import io.cucumber.plugin.event.HookType;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.Step;
import io.cucumber.plugin.event.StepArgument;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PwReportPlugin} 事件链端到端门禁——模块<b>唯一的对外入口</b>此前零覆盖。
 *
 * <p>此前只有 {@code StatusMapper} / {@code OutcomeIdFactory} 两个纯函数被测，事件
 * 装配路径（hook 过滤、@Before/@After 失败分流、步骤栈 push/pop、AMBIGUOUS 附注）
 * 从未被验证。本类用 fake {@link EventPublisher} 派发<b>真实</b> cucumber 事件对象
 * （签名按 cucumber-plugin 7.34.9 实际接口实现），断言落盘产物形状。</p>
 */
class PluginEventChainTest {

    @TempDir
    Path dir;

    /** 收集到的处理器，按事件类型索引（只装本测试注册过的 6 个类型）。 */
    private final Map<Class<?>, List<Consumer<Object>>> handlers = new HashMap<>();

    private ReportBus previousBus;
    private PwReportPlugin plugin;

    /** 当前用例：TestStep 事件必须携带与 TestCaseStarted 同一个 TestCase 实例。 */
    private TestCase lastCase;

    @BeforeEach
    void setUp() {
        previousBus = ReportBus.getDefault();
        ReportBus.installDefault(new ReportBus(PwReportsConfig.builder().outputDir(dir).build()));
        plugin = new PwReportPlugin();
        plugin.setEventPublisher(new CollectingPublisher());
    }

    @AfterEach
    void tearDown() {
        ReportBus.installDefault(previousBus);
    }

    // ------------------------------------------------------------ event plumbing

    @SuppressWarnings("unchecked")
    private <T> void fire(T event) {
        List<Consumer<Object>> list = handlers.get(event.getClass());
        assertThat(list).as("插件未注册 " + event.getClass().getSimpleName() + " 处理器").isNotNull();
        for (Consumer<Object> c : list) {
            c.accept(event);
        }
    }

    private void startRun() {
        fire(new TestRunStarted(Instant.now()));
    }

    private void finishRun() {
        fire(new TestRunFinished(Instant.now()));
    }

    private void startCase(String uri, int line, String name, List<String> tags) {
        lastCase = testCase(uri, line, name, tags);
        fire(new TestCaseStarted(Instant.now(), lastCase));
    }

    private void startStep(String keyword, String text, int line) {
        fire(new TestStepStarted(Instant.now(), lastCase, pickleStep(keyword, text, line)));
    }

    private void finishStep(String keyword, String text, int line, Status status, Throwable error) {
        fire(new TestStepFinished(Instant.now(), lastCase, pickleStep(keyword, text, line),
                new Result(status, Duration.ofMillis(1), error)));
    }

    private void finishHook(HookType type, Status status, Throwable error) {
        fire(new TestStepFinished(Instant.now(), lastCase, hookStep(type),
                new Result(status, Duration.ofMillis(1), error)));
    }

    private void finishCase(Status status, Throwable error, long ms) {
        fire(new TestCaseFinished(Instant.now(), lastCase,
                new Result(status, Duration.ofMillis(ms), error)));
    }

    /** 收集处理器；未注册类型不派发（与真实 EventBus 的「谁注册谁收到」一致）。 */
    private final class CollectingPublisher implements EventPublisher {

        @Override
        public <T> void registerHandlerFor(Class<T> eventType,
                io.cucumber.plugin.event.EventHandler<T> handler) {
            handlers.computeIfAbsent((Class<?>) eventType, k -> new ArrayList<>())
                    .add(o -> handler.receive((T) o));
        }

        @Override
        public <T> void removeHandlerFor(Class<T> eventType,
                io.cucumber.plugin.event.EventHandler<T> handler) {
            // 无操作：本类只做一次性派发
        }
    }

    // ------------------------------------------------------------------ fixtures
    // 以下签名按 cucumber-plugin 7.34.9 的 javap 实证实现，不凭记忆。

    private static TestCase testCase(String uri, int line, String name, List<String> tags) {
        return new TestCase() {
            @Override
            public Integer getLine() {
                return line;
            }

            @Override
            public io.cucumber.plugin.event.Location getLocation() {
                return new io.cucumber.plugin.event.Location(line, 0);
            }

            @Override
            public String getKeyword() {
                return "Scenario";
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getScenarioDesignation() {
                return name;
            }

            @Override
            public List<String> getTags() {
                return tags;
            }

            @Override
            public List<io.cucumber.plugin.event.TestStep> getTestSteps() {
                return List.of();
            }

            @Override
            public URI getUri() {
                return URI.create(uri);
            }

            @Override
            public UUID getId() {
                return UUID.nameUUIDFromBytes((uri + line + name).getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static Step gherkin(String keyword, String text, int line) {
        return new Step() {
            @Override
            public StepArgument getArgument() {
                return null;
            }

            @Override
            public String getKeyword() {
                return keyword;
            }

            @Override
            public String getText() {
                return text;
            }

            @Override
            public int getLine() {
                return line;
            }

            @Override
            public io.cucumber.plugin.event.Location getLocation() {
                return new io.cucumber.plugin.event.Location(line, 0);
            }
        };
    }

    private static PickleStepTestStep pickleStep(String keyword, String text, int line) {
        return new PickleStepTestStep() {
            @Override
            public String getPattern() {
                return text;
            }

            @Override
            public Step getStep() {
                return gherkin(keyword, text, line);
            }

            @Override
            public List<Argument> getDefinitionArgument() {
                return List.of();
            }

            @Override
            public StepArgument getStepArgument() {
                return null;
            }

            @Override
            public int getStepLine() {
                return line;
            }

            @Override
            public URI getUri() {
                return URI.create("file:features/x.feature");
            }

            @Override
            public String getStepText() {
                return text;
            }

            @Override
            public String getCodeLocation() {
                return "Steps.java:10";
            }

            @Override
            public UUID getId() {
                return UUID.nameUUIDFromBytes((text + line).getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static HookTestStep hookStep(HookType type) {
        return new HookTestStep() {
            @Override
            public HookType getHookType() {
                return type;
            }

            @Override
            public String getCodeLocation() {
                return "Hooks.java:1";
            }

            @Override
            public UUID getId() {
                return UUID.nameUUIDFromBytes(type.name().getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private TestOutcome read(String id) throws Exception {
        return JsonWriter.mapper().readValue(dir.resolve(id + ".json").toFile(), TestOutcome.class);
    }

    // --------------------------------------------------------------------- tests

    @Test
    @DisplayName("完整 run → 场景 → 步骤：产物形状正确（步骤树、keyword 前缀、行号、tag、duration）")
    void fullRunProducesOutcome() throws Exception {
        startRun();
        startCase("file:features/login.feature", 12, "user logs in", List.of("@smoke", "@auth"));

        startStep("Given ", "a valid user", 13);
        finishStep("Given ", "a valid user", 13, Status.PASSED, null);
        startStep("When ", "he submits the form", 14);
        finishStep("When ", "he submits the form", 14, Status.PASSED, null);

        finishCase(Status.PASSED, null, 130);
        finishRun();

        TestOutcome o = read("login-12-user-logs-in");
        assertThat(o.getResult()).isEqualTo(TestResult.SUCCESS);
        assertThat(o.getDuration()).isEqualTo(130);
        assertThat(o.getTags()).containsExactly("@smoke", "@auth");
        assertThat(o.getUserStory().getDisplayName()).as("feature 基名去扩展名").isEqualTo("login");
        assertThat(o.getUserStory().getPath()).isEqualTo("file:features/login.feature");

        List<TestStep> steps = o.getTestSteps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getDescription()).isEqualTo("Given a valid user");
        assertThat(steps.get(0).getResult()).isEqualTo(TestResult.SUCCESS);
        assertThat(steps.get(0).getLineNumber()).as("gherkin 行号").isEqualTo(13);
        assertThat(steps.get(0).getStepId()).isNotBlank();
        assertThat(steps.get(1).getDescription()).isEqualTo("When he submits the form");
        assertThat(steps.get(1).getLineNumber()).isEqualTo(14);
    }

    @Test
    @DisplayName("hook 不入步骤树（hook 步骤的 started/finished 被忽略）")
    void hookIsExcludedFromStepTree() throws Exception {
        startRun();
        startCase("file:features/login.feature", 3, "with hooks", List.of());

        // Before hook 成功 → 既不入树也不产生任何记录
        fire(new TestStepStarted(Instant.now(), lastCase, hookStep(HookType.BEFORE)));
        finishHook(HookType.BEFORE, Status.PASSED, null);

        startStep("Given ", "a precondition", 5);
        finishStep("Given ", "a precondition", 5, Status.PASSED, null);

        finishCase(Status.PASSED, null, 6);
        finishRun();

        TestOutcome o = read("login-3-with-hooks");
        assertThat(o.getTestSteps()).as("只有 pickle 步骤").hasSize(1);
        assertThat(o.getTestSteps().get(0).getDescription()).isEqualTo("Given a precondition");
    }

    @Test
    @DisplayName("@Before 失败 → 用例级根因（result=FAILURE + cause），不进步骤树")
    void beforeHookFailureBecomesCaseRootCause() throws Exception {
        startRun();
        startCase("file:features/login.feature", 7, "before fails", List.of());
        finishHook(HookType.BEFORE, Status.FAILED, new IllegalStateException("browser not started"));
        finishCase(Status.FAILED, new IllegalStateException("browser not started"), 1);
        finishRun();

        TestOutcome o = read("login-7-before-fails");
        assertThat(o.getResult()).isEqualTo(TestResult.FAILURE);
        assertThat(o.getTestFailureCause()).as("@Before 失败必须留下用例级根因").isNotNull();
        assertThat(o.getTestFailureCause().getMessage()).contains("browser not started");
    }

    @Test
    @DisplayName("@After 失败 → 用例级 warning 证据，但不覆盖用例结论")
    void afterHookFailureBecomesWarningNotFailure() throws Exception {
        startRun();
        startCase("file:features/login.feature", 9, "after fails", List.of());
        startStep("Given ", "ok step", 10);
        finishStep("Given ", "ok step", 10, Status.PASSED, null);
        finishHook(HookType.AFTER, Status.FAILED, new IllegalStateException("teardown blew up"));
        // 场景本身 PASSED：@After 失败不得把结论翻成 FAILURE
        finishCase(Status.PASSED, null, 6);
        finishRun();

        TestOutcome o = read("login-9-after-fails");
        assertThat(o.getResult()).isEqualTo(TestResult.SUCCESS);
        assertThat(o.getEvidence()).as("@After 失败降级为用例级 warning 证据").anyMatch(l -> l.contains("WARNING") && l.contains("teardown blew up"));
    }

    @Test
    @DisplayName("AMBIGUOUS 步骤 → FAILURE + 步骤附注（不静默当 PASSED）")
    void ambiguousStepBecomesFailureWithNote() throws Exception {
        startRun();
        startCase("file:features/login.feature", 15, "ambiguous step", List.of());
        startStep("Given ", "a duplicated step", 16);
        finishStep("Given ", "a duplicated step", 16, Status.AMBIGUOUS, null);
        finishCase(Status.FAILED, null, 2);
        finishRun();

        TestOutcome o = read("login-15-ambiguous-step");
        assertThat(o.getResult()).isEqualTo(TestResult.FAILURE);
        TestStep s = o.getTestSteps().get(0);
        assertThat(s.getResult()).isEqualTo(TestResult.FAILURE);
        assertThat(s.getEvidence()).anyMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("ambiguous"));
    }

    @Test
    @DisplayName("SKIPPED 步骤入树并标 SKIPPED（前置失败后的跳过也要可见，只排除 UNUSED）")
    void skippedStepIsRecorded() throws Exception {
        startRun();
        startCase("file:features/login.feature", 20, "skipped step", List.of());
        startStep("Given ", "a skipped precondition", 21);
        finishStep("Given ", "a skipped precondition", 21, Status.SKIPPED, null);
        finishCase(Status.SKIPPED, null, 0);
        finishRun();

        TestOutcome o = read("login-20-skipped-step");
        assertThat(o.getResult()).isEqualTo(TestResult.SKIPPED);
        assertThat(o.getTestSteps()).as("跳过也要可见，否则报告看不出为何没跑").hasSize(1);
        assertThat(o.getTestSteps().get(0).getResult()).isEqualTo(TestResult.SKIPPED);
    }

    @Test
    @DisplayName("UNUSED 步骤：句柄必须被收尾（不留 result=null 幽灵步、不泄漏 current 上下文）")
    void unusedStepHandleIsStillClosed() throws Exception {
        startRun();
        startCase("file:features/login.feature", 22, "unused step", List.of());
        startStep("Given ", "an undefined precondition", 23);
        finishStep("Given ", "an undefined precondition", 23, Status.UNUSED, null);

        // 关键：UNUSED 之后紧跟一个正常步骤，若 current 上下文未回落，它会被挂到死步骤下
        startStep("When ", "the flow continues", 24);
        finishStep("When ", "the flow continues", 24, Status.PASSED, null);
        finishCase(Status.PASSED, null, 1);
        finishRun();

        TestOutcome o = read("login-22-unused-step");
        assertThat(o.getResult()).isEqualTo(TestResult.SUCCESS);
        assertThat(o.getTestSteps()).hasSize(2);
        assertThat(o.getTestSteps().get(1).getResult())
                .as("后续步骤必须正常收尾，不能是幽灵/嵌套错位").isEqualTo(TestResult.SUCCESS);
    }

    @Test
    @DisplayName("步骤失败 → 步骤 FAILURE + 用例 FAILURE + 根因消息")
    void failedStepPropagatesFailure() throws Exception {
        startRun();
        startCase("file:features/login.feature", 30, "bad step", List.of());
        startStep("Then ", "the balance matches", 31);
        finishStep("Then ", "the balance matches", 31, Status.FAILED,
                new AssertionError("expected 100 but was 80"));
        finishCase(Status.FAILED, new AssertionError("expected 100 but was 80"), 5);
        finishRun();

        TestOutcome o = read("login-30-bad-step");
        assertThat(o.getResult()).isEqualTo(TestResult.FAILURE);
        assertThat(o.getTestFailureCause()).isNotNull();
        assertThat(o.getTestSteps().get(0).getResult()).isEqualTo(TestResult.FAILURE);
    }

    @Test
    @DisplayName("两场景连跑：句柄/stack 每场景重置，不串台")
    void twoCasesDoNotBleedState() throws Exception {
        startRun();

        startCase("file:features/a.feature", 5, "first case", List.of());
        startStep("Given ", "step one", 6);
        finishStep("Given ", "step one", 6, Status.PASSED, null);
        finishCase(Status.PASSED, null, 1);

        startCase("file:features/b.feature", 8, "second case", List.of());
        startStep("When ", "step two", 9);
        finishStep("When ", "step two", 9, Status.PASSED, null);
        finishCase(Status.PASSED, null, 2);

        finishRun();

        assertThat(read("a-5-first-case").getTestSteps()).hasSize(1);
        assertThat(read("b-8-second-case").getTestSteps()).hasSize(1);
        assertThat(read("a-5-first-case").getUserStory().getDisplayName()).isEqualTo("a");
        assertThat(read("b-8-second-case").getUserStory().getDisplayName()).isEqualTo("b");
    }

    @Test
    @DisplayName("同名同 line 场景 → outcomeId 冲突后缀，产物不互相覆盖")
    void duplicateScenarioGetsSuffix() throws Exception {
        startRun();
        startCase("file:features/a.feature", 5, "same name", List.of());
        finishCase(Status.PASSED, null, 1);
        startCase("file:features/a.feature", 5, "same name", List.of());
        finishCase(Status.PASSED, null, 1);
        finishRun();

        assertThat(dir.resolve("a-5-same-name.json")).exists();
        assertThat(dir.resolve("a-5-same-name-2.json")).as("冲突走 -2 后缀").exists();
    }

    @Test
    @DisplayName("run 结束后 index.html 与 summary 均已落盘")
    void runFinishWritesIndexAndSummary() {
        startRun();
        startCase("file:features/login.feature", 12, "indexed", List.of());
        finishCase(Status.PASSED, null, 4);
        finishRun();

        assertThat(dir.resolve("index.html")).exists();
        assertThat(dir.resolve("serenity-summary.json")).exists();
        assertThat(dir.resolve("login-12-indexed.html")).exists();
    }

    @Test
    @DisplayName("finishRun 幂等：重复派发 TestRunFinished 不炸、不写坏产物")
    void runFinishedIsIdempotent() throws Exception {
        startRun();
        startCase("file:features/login.feature", 12, "twice", List.of());
        finishCase(Status.PASSED, null, 4);
        finishRun();
        finishRun();   // 某些 runner 下可能重复派发

        assertThat(dir.resolve("login-12-twice.json")).exists();
        assertThat(read("login-12-twice")).isNotNull();
    }
}

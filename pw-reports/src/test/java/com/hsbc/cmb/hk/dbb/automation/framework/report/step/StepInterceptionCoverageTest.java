package com.hsbc.cmb.hk.dbb.automation.framework.report.step;

import com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.NoStepInterception;
import com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.Step;
import com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.Steps;
import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Step 拦截覆盖门禁（R5/R6）：经 {@link PwReportObjectFactory} 真实装配路径
 * （getInstance → @Steps 注入 → ByteBuddy 代理 → 方法进出事件）驱动，断言
 * {@link ReportBus} 录到的步骤树形状。
 *
 * <p>这是框架自有 {@code @Step}/{@code @Steps} 拦截契约的行为级证明：fixture 全部使用
 * 框架自有注解书写。
 *
 * <p>与单元测试的分工：{@code PwStepAnnotations} 的按名匹配属
 * 纯函数逻辑；本类只守<b>端到端可观测行为</b>：树形、嵌套、FAILURE、rethrow、
 * 降级路径、shared 语义、kill-switch 直通。
 */
class StepInterceptionCoverageTest {

    @TempDir
    Path tempDir;

    private ReportBus bus;
    private PwReportObjectFactory factory;
    private ReportBus previous;

    @BeforeEach
    void setUp() {
        PwStepFactory.clearSharedCacheForTest();
        // 捕获进程级默认总线（可能为 null → getDefault 惰性创建闲总线，无事件不落盘），
        // 测试后还原，避免污染同 JVM 其他测试。
        previous = ReportBus.getDefault();
        bus = new ReportBus(PwReportsConfig.builder().outputDir(tempDir).build());
        ReportBus.installDefault(bus);
        bus.startRun();
        factory = new PwReportObjectFactory();
        factory.start();
    }

    @AfterEach
    void tearDown() {
        ReportBus.installDefault(previous);
    }

    // ---------------------------------------------------------------- 主路径

    @Test
    @DisplayName("@Steps 注入 + @Step 拦截：树形 / value 描述 / 嵌套 / 返回值透传")
    void serenityFqnInjectionRecordsStepTree() {
        ScenarioGlue glue = factory.getInstance(ScenarioGlue.class);

        // 注入的是 ByteBuddy 子类代理（非原始类）——否则拦截无从发生
        assertThat(glue.demoSteps).isInstanceOf(DemoSteps.class);
        assertThat(glue.demoSteps.getClass()).isNotEqualTo(DemoSteps.class);

        // 用例外调用：bus 无当前句柄 → NOOP，方法体照常执行、不录步骤
        assertThat(glue.demoSteps.echo("outside")).isEqualTo("echo:outside");

        StepHandle caseHandle = bus.startCase("cov-10-demo", "features/login", "login works", List.of("smoke"));

        glue.demoSteps.openPage();     // @Step("open the login page") → 描述取 value
        glue.demoSteps.plainHelper();  // 无注解 → 选择性不拦截
        glue.demoSteps.nestedParent(); // @Step 无 value → 方法名；内含子 @Step

        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 123);

        TestOutcome outcome = singleOutcome();
        assertThat(outcome.getName()).isEqualTo("login works");
        assertThat(outcome.getResult()).isEqualTo(TestResult.SUCCESS);
        assertThat(outcome.getDuration()).isEqualTo(123);

        List<TestStep> roots = outcome.getTestSteps();
        assertThat(roots).hasSize(2); // plainHelper 未被录制；用例外 echo 未串入
        assertThat(roots.get(0).getDescription()).isEqualTo("open the login page");
        assertThat(roots.get(0).getResult()).isEqualTo(TestResult.SUCCESS);
        assertThat(roots.get(0).getChildren()).isEmpty();

        TestStep nested = roots.get(1);
        assertThat(nested.getDescription()).isEqualTo("nestedParent");
        assertThat(nested.getChildren()).hasSize(1);
        assertThat(nested.getChildren().get(0).getDescription()).isEqualTo("nested child");
        assertThat(nested.getChildren().get(0).getResult()).isEqualTo(TestResult.SUCCESS);

        // finishRun 双触发安全（插件 TestRunFinished + ObjectFactory.stop）
        factory.stop();
        assertThat(bus.sinkErrorCount()).isZero();
        assertThat(tempDir.resolve("cov-10-demo").resolve("cov-10-demo.json")).exists();
    }

    @Test
    @DisplayName("@Step 方法异常：FAILURE + exception 落树，且原样 rethrow（报告不吞业务异常）")
    void failingStepRecordsFailureAndRethrows() {
        ScenarioGlue glue = factory.getInstance(ScenarioGlue.class);
        StepHandle caseHandle = bus.startCase("cov-20-fail", "features/login", "failing scenario", List.of());

        Throwable boom = assertThatThrownBy(() -> glue.demoSteps.failingStep())
                .isInstanceOf(AssertionError.class)
                .hasMessage("step boom")
                .actual();

        bus.finishCase(caseHandle, TestResult.FAILURE, boom, 9);

        TestOutcome outcome = singleOutcome();
        assertThat(outcome.getResult()).isEqualTo(TestResult.FAILURE);

        TestStep step = outcome.getTestSteps().get(0);
        assertThat(step.getDescription()).isEqualTo("boom step");
        assertThat(step.getResult()).isEqualTo(TestResult.FAILURE);
        assertThat(step.getException()).isNotNull();
        assertThat(step.getException().getMessage()).isEqualTo("step boom");
        assertThat(step.getException().getErrorType()).isEqualTo("FAILURE");

        // 用例级双口径（SummaryReportGenerator 两处读取路径都兼容）
        assertThat(outcome.getTestFailureMessage()).isEqualTo("step boom");
        assertThat(outcome.getTestFailureClassname()).isEqualTo(AssertionError.class.getName());
        assertThat(outcome.getTestFailureCause()).isNotNull();
    }

    // ---------------------------------------------------------------- 降级路径

    @Test
    @DisplayName("final 步骤库类：降级原始实例——方法照常执行、整类不入树")
    void finalStepsClassDegradesToRawInstance() {
        FinalGlue glue = factory.getInstance(FinalGlue.class);
        // 关键：降级不是「注入 null」——字段可用，只是无拦截
        assertThat(glue.finalSteps).isNotNull();
        assertThat(glue.finalSteps.getClass()).isEqualTo(FinalSteps.class);

        StepHandle caseHandle = bus.startCase("cov-30-final", "f", "final class", List.of());
        glue.finalSteps.finalStep();
        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 1);

        assertThat(singleOutcome().getTestSteps()).isEmpty();
    }

    @Test
    @DisplayName("final @Step 方法：类仍代理，仅 final 方法漏入（其余照常录）")
    void finalStepMethodIsSkippedButClassStillProxied() {
        MethodFinalGlue glue = factory.getInstance(MethodFinalGlue.class);
        StepHandle caseHandle = bus.startCase("cov-31-finalm", "f", "final method", List.of());

        glue.steps.finalMethod();  // 无法覆盖 → 不拦截
        glue.steps.openMethod();   // 照常拦截

        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 1);

        List<TestStep> steps = singleOutcome().getTestSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getDescription()).isEqualTo("open method step");
    }

    @Test
    @DisplayName("@NoStepInterception：显式退出标记 → 原始实例直通，零开销")
    void noInterceptionMarkerPassesThrough() {
        SkippedGlue glue = factory.getInstance(SkippedGlue.class);
        assertThat(glue.steps).isNotNull();
        assertThat(glue.steps.getClass()).isEqualTo(SkippedSteps.class);

        StepHandle caseHandle = bus.startCase("cov-32-skip", "f", "opt-out", List.of());
        glue.steps.skipped();
        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 1);

        assertThat(singleOutcome().getTestSteps()).isEmpty();
    }

    @Test
    @DisplayName("Object 方法（toString/equals/hashCode）不走拦截、不入树")
    void objectMethodsAreNotIntercepted() {
        ScenarioGlue glue = factory.getInstance(ScenarioGlue.class);
        StepHandle caseHandle = bus.startCase("cov-40-object", "f", "object methods", List.of());

        Object proxy = glue.demoSteps;
        assertThat(proxy.toString()).contains("DemoSteps");
        assertThat(proxy).isEqualTo(proxy);
        assertThat(proxy.hashCode()).isEqualTo(System.identityHashCode(proxy));

        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 1);
        assertThat(singleOutcome().getTestSteps()).isEmpty();
    }

    // ---------------------------------------------------------------- 语义与开关

    @Test
    @DisplayName("shared=true 全 glue 共享一个代理实例；默认每注入点独立；注入幂等")
    void sharedSemanticsAndIdempotentInjection() {
        SharedGlueA a = factory.getInstance(SharedGlueA.class);
        SharedGlueB b = factory.getInstance(SharedGlueB.class);

        // shared=true：跨 glue 类同类型共享
        assertThat(a.sharedSteps).isSameAs(b.sharedSteps);
        // 默认 shared=false：每注入点独立实例
        assertThat(a.ownSteps).isNotSameAs(b.ownSteps);
        assertThat(a.ownSteps).isNotSameAs(a.sharedSteps);

        // 幂等：业务显式赋值不被注入覆盖
        DemoSteps manual = new DemoSteps();
        SharedGlueA a2 = new SharedGlueA();
        a2.ownSteps = manual;
        PwStepInjector.injectInto(a2);
        assertThat(a2.ownSteps).isSameAs(manual);
    }

    @Test
    @DisplayName("框架 @Steps/@Step 被注入与拦截")
    void ownAnnotationsAreIntercepted() {
        OwnGlue glue = factory.getInstance(OwnGlue.class);
        assertThat(glue.steps).isNotNull();

        StepHandle caseHandle = bus.startCase("cov-60-own", "f", "own annotations", List.of());
        glue.steps.one();
        glue.steps.two(); // 内含子 @Step
        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 3);

        List<TestStep> roots = singleOutcome().getTestSteps();
        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).getDescription()).isEqualTo("own step one");
        assertThat(roots.get(1).getDescription()).isEqualTo("two");
        assertThat(roots.get(1).getChildren()).hasSize(1);
        assertThat(roots.get(1).getChildren().get(0).getDescription()).isEqualTo("own step three");
    }

    @Test
    @DisplayName("kill-switch：运行时 enabled=false 期间 @Step 直通执行；恢复后照常录")
    void killSwitchPassesThrough() {
        ScenarioGlue glue = factory.getInstance(ScenarioGlue.class);
        StepHandle caseHandle = bus.startCase("cov-70-kill", "f", "kill switch", List.of());

        bus.setEnabled(false);
        assertThat(glue.demoSteps.echo("off")).isEqualTo("echo:off"); // 直通，不录

        bus.setEnabled(true);
        glue.demoSteps.openPage(); // 恢复后照常

        bus.finishCase(caseHandle, TestResult.SUCCESS, null, 1);
        List<TestStep> steps = singleOutcome().getTestSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getDescription()).isEqualTo("open the login page");
    }

    // ---------------------------------------------------------------- fixtures

    private TestOutcome singleOutcome() {
        List<TestOutcome> snapshots = bus.outcomeSnapshots();
        assertThat(snapshots).hasSize(1);
        return snapshots.get(0);
    }

    /** 框架 @Step 注解的步骤库。 */
    public static class DemoSteps {

        @Step("open the login page")
        public void openPage() {
        }

        /** 无注解方法：选择性拦截的对照——不得入树。 */
        public void plainHelper() {
        }

        @Step
        public void nestedParent() {
            nestedChild(); // this 为代理 → 虚拟分派 → 子步骤
        }

        @Step("nested child")
        public void nestedChild() {
        }

        @Step("echo value")
        public String echo(String in) {
            return "echo:" + in;
        }

        @Step("boom step")
        public void failingStep() {
            throw new AssertionError("step boom");
        }
    }

    /** final 类：无法子类化 → 整类降级。 */
    public static final class FinalSteps {

        @Step("final class step")
        public void finalStep() {
        }
    }

    /** 普通类 + final 方法：类仍代理，final 方法漏入。 */
    public static class MethodFinalSteps {

        @Step("final method step")
        public final void finalMethod() {
        }

        @Step("open method step")
        public void openMethod() {
        }
    }

    /** 显式退出标记：直通。 */
    @NoStepInterception
    public static class SkippedSteps {

        @Step("skipped step")
        public void skipped() {
        }
    }

    public static class ScenarioGlue {

        @Steps
        private DemoSteps demoSteps;
    }

    public static class FinalGlue {

        @Steps
        private FinalSteps finalSteps;
    }

    public static class MethodFinalGlue {

        @Steps
        private MethodFinalSteps steps;
    }

    public static class SkippedGlue {

        @Steps
        private SkippedSteps steps;
    }

    public static class SharedGlueA {

        @Steps(shared = true)
        private DemoSteps sharedSteps;

        @Steps
        private DemoSteps ownSteps;
    }

    public static class SharedGlueB {

        @Steps(shared = true)
        private DemoSteps sharedSteps;

        @Steps
        private DemoSteps ownSteps;
    }

    /** 框架 @Step/@Steps 注解的步骤库 / glue。 */
    public static class OwnSteps {

        @Step("own step one")
        public void one() {
        }

        @Step
        public void two() {
            three();
        }

        @Step("own step three")
        public void three() {
        }
    }

    public static class OwnGlue {

        @Steps
        private OwnSteps steps;
    }
}

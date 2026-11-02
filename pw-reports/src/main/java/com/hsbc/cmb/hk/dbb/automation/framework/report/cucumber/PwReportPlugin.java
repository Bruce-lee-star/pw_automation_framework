package com.hsbc.cmb.hk.dbb.automation.framework.report.cucumber;

import com.hsbc.cmb.hk.dbb.automation.framework.report.event.ReportBus;
import com.hsbc.cmb.hk.dbb.automation.framework.report.event.StepHandle;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.HookTestStep;
import io.cucumber.plugin.event.HookType;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.DataTableArgument;
import io.cucumber.plugin.event.DocStringArgument;
import io.cucumber.plugin.event.Step;
import io.cucumber.plugin.event.StepArgument;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Cucumber 事件源——{@link ConcurrentEventListener}（为并行留门，R7）。
 *
 * <p>注册路径：
 * <ul>
 *   <li>JUnit Platform 引擎：classpath 根 {@code cucumber.plugin=…PwReportPlugin}
 *       或随 jar 的 {@code META-INF/services/io.cucumber.plugin.EventListener}；</li>
 *   <li>JUnit 4（{@code @RunWith(Cucumber.class)}）：SPI 不生效，必须
 *       {@code @CucumberOptions(plugin = {"…PwReportPlugin"})} 显式声明
 *       （本框架存量消费方即此路径）。</li>
 * </ul>
 *
 * <p>事件处理规则（方案 v3 §4）：
 * <ul>
 *   <li>hook（{@link HookTestStep}）不入步骤树；{@code @Before} 失败 → 用例级根因，
 *       {@code @After}（含 step 级 hook）失败 → 用例级 warning 证据；</li>
 *   <li>Status 全枚举映射（{@link StatusMapper}），AMBIGUOUS → FAILURE + 步骤附注；</li>
 *   <li>outcomeId = {@code yyyyMMdd-HHmmss-<SHA-256 hex(case identity)>}；
 *       冲突后缀由总线负责；</li>
 *   <li>步骤句柄走线程本地栈——嵌套 @Step 在 glue 执行期间由 dispatcher 经总线
 *       CURRENT 管理，与本栈互不干扰（本栈只对 Cucumber 级步骤 push/pop）。</li>
 * </ul>
 */
public final class PwReportPlugin implements ConcurrentEventListener {

    private volatile ReportBus resolved;

    private final ThreadLocal<StepHandle> caseHandle = new ThreadLocal<>();
    private final ThreadLocal<Deque<StepHandle>> stepStack = ThreadLocal.withInitial(ArrayDeque::new);
    /** Scenario Outline 行计数器（outlineKey → 已见行数），用于 exampleRowNumber（1 起）。 */
    private final java.util.Map<String, Integer> outlineRowCounter = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestRunStarted.class, this::onRunStarted);
        publisher.registerHandlerFor(TestCaseStarted.class, this::onCaseStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::onStepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::onStepFinished);
        publisher.registerHandlerFor(TestCaseFinished.class, this::onCaseFinished);
        publisher.registerHandlerFor(TestRunFinished.class, this::onRunFinished);
    }

    // ---------------------------------------------------------------- handlers

    private void onRunStarted(TestRunStarted event) {
        bus().setSource(ReportBus.ReportSource.CUCUMBER);
        bus().startRun();
    }

    private void onCaseStarted(TestCaseStarted event) {
        TestCase tc = event.getTestCase();
        String uri = tc.getUri() == null ? "" : tc.getUri().toString();
        String baseId = OutcomeIdFactory.baseId(uri, tc.getLine(), tc.getName());
        StepHandle handle = bus().startCase(
                baseId,
                OutcomeIdFactory.featureBaseName(uri),
                tc.getName(),
                safeTags(tc.getTags()),
                uri);
        caseHandle.set(handle);
        stepStack.get().clear();

        // 手工用例标记：标签含 @manual → 不执行、报告记录为手工（与 Serenity 语义对齐）。
        // 真正的"跳过执行"由测试侧 @Before 钩子（检测到该标签即 skip）配合完成。
        if (tc.getTags() != null && tc.getTags().stream()
                .anyMatch(t -> "@manual".equals(t) || "manual".equals(t))) {
            bus().markManual(handle);
        }

        // Scenario Outline 探测（Cucumber 7：keyword 含 "Outline"）→ 标注 outline，供 sink 端分组聚合
        if (tc.getKeyword() != null && tc.getKeyword().contains("Outline")) {
            String outlineKey = OutcomeIdFactory.featureBaseName(uri) + "/" + tc.getName();
            int rowNumber = outlineRowCounter.merge(outlineKey, 1, Integer::sum);
            bus().markOutline(handle, outlineKey, rowNumber);
        }
    }

    private void onStepStarted(TestStepStarted event) {
        TestStep step = event.getTestStep();
        if (!(step instanceof PickleStepTestStep pickle)) {
            return; // hook 不记入步骤树（§4.3）
        }
        Step gherkin = pickle.getStep();
        String description = describe(gherkin);
        // Gherkin 参数：DataTable / DocString（2026-10-18 新增；非此类步骤为 null）
        StepArgument argument = gherkin.getArgument();
        List<List<String>> dataTable = null;
        String docString = null;
        if (argument instanceof DataTableArgument table) {
            dataTable = table.cells();
        } else if (argument instanceof DocStringArgument doc) {
            docString = doc.getContent();
        }
        StepHandle handle = bus().stepStarted(description, gherkin.getLine(), dataTable, docString);
        stepStack.get().push(handle);
    }

    private void onStepFinished(TestStepFinished event) {
        TestStep step = event.getTestStep();
        Result result = event.getResult();
        if (step instanceof PickleStepTestStep) {
            StepHandle handle = stepStack.get().poll();
            if (handle == null || handle.isNoop()) {
                return; // 防御：start 丢失或总线未激活
            }
            TestResult mapped = StatusMapper.toTestResult(result.getStatus());
            if (mapped == null) {
                // UNUSED（步骤定义不存在）：不产生结果，但仍必须收尾句柄——
                // 否则该步骤永久留在树里且 result 为 null（渲染成 UNKNOWN 幽灵步），
                // 且 current 线程上下文不回落，嵌套子步骤会被挂到这个死步骤下。
                bus().stepFinished(handle, null, null);
                return;
            }
            bus().stepFinished(handle, mapped, result.getError());
            if (result.getStatus() == Status.AMBIGUOUS) {
                bus().attachEvidence(handle, StatusMapper.AMBIGUOUS_NOTE);
            }
            return;
        }
        if (step instanceof HookTestStep hook && result.getStatus() == Status.FAILED) {
            StepHandle currentCase = caseHandle.get();
            if (currentCase == null || currentCase.isNoop()) {
                return;
            }
            if (hook.getHookType() == HookType.BEFORE) {
                bus().recordBeforeHookFailure(currentCase, result.getError());
            } else {
                bus().recordAfterHookWarning(currentCase, result.getError());
            }
        }
    }

    private void onCaseFinished(TestCaseFinished event) {
        Result result = event.getResult();
        StepHandle handle = caseHandle.get();
        TestResult mapped = StatusMapper.toTestResult(result.getStatus());
        bus().finishCase(handle, mapped, result.getError(), result.getDuration().toMillis());
        caseHandle.remove();
        stepStack.remove();
    }

    private void onRunFinished(TestRunFinished event) {
        bus().finishRun();
        bus().setSource(ReportBus.ReportSource.GENERIC);
    }

    // ---------------------------------------------------------------- internals

    private ReportBus bus() {
        ReportBus b = resolved;
        if (b == null) {
            b = ReportBus.getDefault();
            resolved = b;
        }
        return b;
    }

    private static String describe(Step gherkin) {
        String keyword = gherkin.getKeyword();
        String text = gherkin.getText();
        StringBuilder sb = new StringBuilder();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(keyword.trim()).append(' ');
        }
        sb.append(text == null ? "" : text);
        return sb.toString().trim();
    }

    private static List<String> safeTags(List<String> tags) {
        return tags == null ? List.of() : tags;
    }
}

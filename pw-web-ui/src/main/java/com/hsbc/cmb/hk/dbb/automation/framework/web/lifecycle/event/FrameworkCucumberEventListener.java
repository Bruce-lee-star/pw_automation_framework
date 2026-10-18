package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.Step;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;

/**
 * Cucumber 原生事件 → 框架原生生命周期总线 的适配器（DEV-V5 迁移关键石）。
 *
 * <p>替代 serenity 的 {@code StepEventBus} 作为框架监听器（{@code PlaywrightListener} /
 * {@code StepFailureAggregator} / {@code AutoBrowserProcessor} 等）的事件源。</p>
 *
 * <p><b>注册（JUnit4，{@code @RunWith(Cucumber.class)}）</b>：Cucumber 的 JUnit4 runner <b>不做</b>
 * 插件 SPI 发现——{@code io.cucumber.plugin.Plugin} 的 {@code ServiceLoader} 仅在 <b>JUnit Platform
 * 引擎</b>（{@code cucumber-junit-platform-engine}）下生效（2026-10-17 实测证实：仅发 SPI 文件、
 * JUnit4 下不加载）。故当前必须显式声明于 {@code @CucumberOptions(plugin = {…FrameworkCucumberEventListener})}。
 * 随 pw-web-ui 发布的 {@code META-INF/services/io.cucumber.plugin.Plugin} 仅为将来迁移到 JUnit Platform
 * 引擎时的自动发现预留。本适配器不依赖 serenity，故在默认构建与 v2 构建下均生效。</p>
 *
 * <p>派发规则：
 * <ul>
 *   <li>hook（{@code HookTestStep}）不入步骤级事件（场景结果已覆盖其成败）；</li>
 *   <li>仅 {@code PickleStepTestStep}（真实步骤）派发
 *       {@link FrameworkLifecycleBus#fireStepStarted} 与 {@link FrameworkLifecycleBus#fireStepFinished}；</li>
 *   <li>{@link Status} 全枚举映射到 {@link LifecycleStatus}（{@code IGNORED} 由 cucumber 的
 *       {@code UNUSED}/{@code SKIPPED} 归并，cucumber 无独立 IGNORED 枚举）。</li>
 * </ul>
 */
public final class FrameworkCucumberEventListener implements ConcurrentEventListener {

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        //  进入 Cucumber 运行时：一次性注册框架默认原生监听器集合（替代 框架 适配器），
        //  之后本监听器把 Cucumber 事件翻译为原生总线事件派发。
        FrameworkLifecycleBus.ensureDefaultListeners();
        publisher.registerHandlerFor(TestRunStarted.class, e -> FrameworkLifecycleBus.fireTestRunStarted());
        publisher.registerHandlerFor(TestCaseStarted.class, this::onCaseStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::onStepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::onStepFinished);
        publisher.registerHandlerFor(TestCaseFinished.class, this::onCaseFinished);
        publisher.registerHandlerFor(TestRunFinished.class, e -> FrameworkLifecycleBus.fireTestRunFinished());
    }

    private void onCaseStarted(TestCaseStarted event) {
        FrameworkLifecycleBus.fireScenarioStarted(scenarioInfo(event.getTestCase()));
    }

    private void onStepStarted(TestStepStarted event) {
        if (!(event.getTestStep() instanceof PickleStepTestStep pickle)) {
            return; // hook 不入步骤级事件
        }
        Step gherkin = pickle.getStep();
        Integer line = gherkin == null ? null : gherkin.getLine();
        LifecycleStepInfo step = LifecycleStepInfo.builder()
                .stepName(gherkin == null ? "" : gherkin.getText())
                .line(line == null ? -1 : line)
                .build();
        FrameworkLifecycleBus.fireStepStarted(step);
    }

    private void onStepFinished(TestStepFinished event) {
        if (!(event.getTestStep() instanceof PickleStepTestStep pickle)) {
            return; // hook 不入步骤级事件
        }
        Step gherkin = pickle.getStep();
        Integer line = gherkin == null ? null : gherkin.getLine();
        LifecycleStepResult step = LifecycleStepResult.builder()
                .status(map(event.getResult().getStatus()))
                .error(event.getResult().getError())
                .stepName(gherkin == null ? null : gherkin.getText())
                .line(line == null ? -1 : line)
                .build();
        FrameworkLifecycleBus.fireStepFinished(step);
    }

    private void onCaseFinished(TestCaseFinished event) {
        TestCase tc = event.getTestCase();
        Result result = event.getResult();
        LifecycleScenarioResult scenario = LifecycleScenarioResult.builder()
                .info(scenarioInfo(tc))
                .status(map(result.getStatus()))
                .error(result.getError())
                .build();
        FrameworkLifecycleBus.fireScenarioFinished(scenario);
    }

    private static LifecycleScenarioInfo scenarioInfo(TestCase tc) {
        String uri = tc.getUri() == null ? "" : tc.getUri().toString();
        return LifecycleScenarioInfo.builder()
                .name(tc.getName())
                .tags(tc.getTags())
                .featureName(uri)
                .uri(uri)
                .build();
    }

    private static LifecycleStatus map(Status status) {
        if (status == null) {
            return LifecycleStatus.PASSED;
        }
        switch (status) {
            case PASSED:
                return LifecycleStatus.PASSED;
            case FAILED:
                return LifecycleStatus.FAILED;
            case SKIPPED:
                return LifecycleStatus.SKIPPED;
            case PENDING:
                return LifecycleStatus.PENDING;
            case AMBIGUOUS:
                return LifecycleStatus.AMBIGUOUS;
            case UNDEFINED:
                return LifecycleStatus.FAILED;
            case UNUSED:
                return LifecycleStatus.IGNORED;
            default:
                return LifecycleStatus.PASSED;
        }
    }
}

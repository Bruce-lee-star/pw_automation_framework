package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;

import net.serenitybdd.core.Serenity;
import net.thucydides.core.steps.StepEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 桥接协作类（设计文档 9.10-③ / 9.3）：Serenity 失败回放。
 *
 * <p>并发任务的失败只发生在工作线程，结果以结构化 {@link ContextTaskResult} 回传<b>编排线程</b>；本类在编排线程把每个失败
 * 经 {@link StepEventBus#testFailed(Throwable)} 标记到 Serenity 报告（复用既有失败通道，零新增报告代码）。
 * 工作线程从不触碰 {@code StepEventBus}（桥接原则）。</p>
 *
 * <p>非 Serenity 环境（如离线单测）下 {@code StepEventBus.getEventBus()} 可能不可用，回放被安全降级，仅保留
 * {@link CompletionException} 抛出语义，不影响调用方既有断言。</p>
 */
public final class SerenityBusBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(SerenityBusBridge.class);

    private SerenityBusBridge() {
    }

    /**
     * 在编排线程回放失败：对每个失败项标记 Serenity，并最终抛出汇总 {@link CompletionException}（若存在失败）。
     *
     * @param results runAll 返回的等序结果
     */
    public static void replayFailures(List<? extends ContextTaskResult<?>> results) {
        List<String> summaries = new ArrayList<>();
        for (ContextTaskResult<?> r : results) {
            if (!r.isSuccess()) {
                try {
                    r.valueOrThrow(); // 抛 CompletionException，message 含线程/耗时/页面错误诊断
                } catch (CompletionException ce) {
                    summaries.add(ce.getMessage());
                    markFailure(ce);
                }
            }
        }
        if (!summaries.isEmpty()) {
            throw new CompletionException("ConcurrentContextExecutor: " + summaries.size()
                    + " task(s) failed -> " + summaries, null);
        }
    }

    /** 经 Serenity 事件总线标记失败；不可用时安全降级（不向上抛，避免掩盖原始失败语义）。 */
    private static void markFailure(Throwable failure) {
        try {
            StepEventBus bus = StepEventBus.getEventBus();
            if (bus != null) {
                bus.testFailed(failure);
            }
        } catch (Throwable t) {
            LOGGER.debug("[SerenityBusBridge] testFailed replay skipped (no Serenity bus available): {}",
                    t.getMessage());
        }
    }

    /**
     * 在编排线程显式标注"浏览器崩溃恢复重跑"事件（WEB-P1-4 验收 ②：重跑事件在 Serenity 报告中可见）。
     *
     * <p>仅对 {@link ContextTaskResult#isReplayed()} 为 true 的结果写入一条报告数据（标题固定、
     * 内容含任务名 / 重跑类型 / 首次失败诊断），使"曾因崩溃被自动重跑一次"在 HTML 报告中清晰可查，
     * 不与最终是否成功相混淆（成功重跑同样标注）。</p>
     *
     * <p>必须在<b>编排线程</b>调用（{@code runAll} 的调用方线程），工作线程不触碰 Serenity 总线（桥接原则 9.3）。
     * 非 Serenity 环境（如离线单测）下安全降级，仅记录调试日志，不影响调用方既有断言。</p>
     *
     * @param results runAll 返回的等序结果
     */
    public static void recordCrashRecoveryIfAny(List<? extends ContextTaskResult<?>> results) {
        if (results == null) {
            return;
        }
        for (ContextTaskResult<?> r : results) {
            if (!r.isReplayed()) {
                continue;
            }
            try {
                String type = r.getRecoveryType() == null ? "crash" : r.getRecoveryType();
                String firstMsg = r.getFirstFailureMessage() == null ? "n/a" : r.getFirstFailureMessage();
                Serenity.recordReportData()
                        .withTitle("BROWSER CRASH RECOVERY / 浏览器崩溃恢复重跑")
                        .andContents("Task '" + r.getTaskName() + "' was auto-replayed once after "
                                + type + " (bounded to 1). First failure: " + firstMsg);
            } catch (Throwable t) {
                LOGGER.debug("[SerenityBusBridge] crash-recovery annotation skipped (no Serenity bus available): {}",
                        t.getMessage());
            }
        }
    }

    /**
     * 业务<b>捕获并恢复</b>了 {@code @Step} 代理步骤抛出的异常后调用，使<b>后续</b> {@code @Step} 步骤恢复执行。
     *
     * <p><b>机制（Serenity 4.3.4 字节码核实）</b>：{@code @Step} 方法抛异常时
     * {@code StepEventBus.stepFailed(failure)} 会把内部 {@code stepFailed} 标志置 {@code true}，
     * 并把失败记入 {@code TestResultTally}（→ 报告）。此后 {@code StepInterceptor} 对每个后续
     * {@code @Step} 调用检查 {@code aStepInTheCurrentTestHasFailed() && !DEEP_STEP_EXECUTION_AFTER_FAILURES}
     * （该 Serenity 开关 {@code ThucydidesSystemProperty.DEEP_STEP_EXECUTION_AFTER_FAILURES} <b>默认 false</b>），
     * 命中即<b>静默跳过</b>该步骤：只发 skipped 事件、返回默认值、<b>不执行业务方法体</b>。
     * 若业务自行 try/catch 了该异常（自愈重试 / 降级返回），JUnit/Cucumber 不会收到未处理异常（绿灯），
     * 但 Serenity 侧出现两件事：① 失败态残留、报告留红；② 后续代理步骤被逐个跳过。本方法针对 ②。</p>
     *
     * <p><b>诚实边界（必读）</b>：{@code StepEventBus.clearStepFailures()} <b>只</b>复位 {@code stepFailed} 标志，
     * <b>不会</b>撤回已记入 {@code TestResultTally} 的失败（Serenity 无公开移除 API）——报告仍会显示该步骤失败。
     * 若要求报告也干净（重试后整体判绿），正确做法是把重试放进 {@code @Step} 方法<b>内部</b>（或调用一个非代理的
     * private 帮助方法），让代理根本看不到失败；本方法仅用于"捕获点必须在 @Step 之外"的场景
     * （例如通用重试框架包装步骤调用）。</p>
     *
     * <p><b>线程语义</b>：必须在<b>业务线程</b>（与 {@code @Step} 调用同线程）调用——{@code stepFailed} 标志是
     * {@code StepEventBus}（ThreadLocal 单例）上的实例状态。</p>
     *
     * <p>非 Serenity 环境（离线单测 / 总线不可用）下安全降级为 no-op，绝不向上抛（与本类其它方法一致）。</p>
     */
    public static void clearStepFailureState() {
        try {
            StepEventBus bus = StepEventBus.getEventBus();
            if (bus != null) {
                bus.clearStepFailures();
            }
        } catch (Throwable t) {
            LOGGER.debug("[SerenityBusBridge] clearStepFailureState skipped (no Serenity bus available): {}",
                    t.getMessage());
        }
    }
}
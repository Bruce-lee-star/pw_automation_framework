package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MONITOR 断言失败的「当前 case 立即判红」检查点（core 层，web / route 共用）。
 *
 * <p><b>解决的问题</b>：MONITOR 断言失败原先只在{@code settledFailures}队列里入队，
 * 直到步骤/场景收尾才被消费，且消费出口遍历全部 runtime、抛错又发生在生命周期事件回调里
 * （异常被派发器隔离吞掉）——表现为「断言失败不立即抛错、case 仍判绿」。</p>
 *
 * <p><b>线程模型（无共享可变状态）</b>：本类<b>不持有任何状态</b>。失败数据始终留在
 * {@code MonitorSink.settledFailures}——按 {@code context}（BrowserContext 实例）归属的
 * {@link java.util.concurrent.ConcurrentLinkedQueue}，本身消费式、线程安全。
 * 消费一次即取走，因此同一批失败只会有一个线程抛出，且绝不会串到别的 context。
 * 不再使用 {@code ThreadLocal} 暂存：它既与失败数据的归属维度不同源，又依赖
 * 「生命周期事件必然在同一线程按序派发」这一无法由框架自身保证的引擎不变式。</p>
 *
 * <p><b>抛错位置</b>：{@link #checkNow(Object)} 由调用方选在<b>业务线程</b>的安全点
 * （元素操作 / 页面等待 / UI 等待循环）。在 Cucumber 原生 scenario 线程上，
 * {@link AssertionError} 沿 glue 步骤方法冒泡：当前步骤 FAILED、该 case 后续步骤 SKIPPED，
 * 不终止 JVM、不中断 run、不影响其它并行 case。
 * 生命周期事件回调（结果已定、异常会被 {@code FrameworkLifecycleBus.safe()} 吞掉）
 * 一律不调用 {@link #checkNow(Object)}，只调 {@link #drainForEvidence(Object)}。</p>
 *
 * <p><b>执行模型差异（务必知悉）</b>：若业务通过 {@code ConcurrentContextExecutor} 在
 * {@code ctx-N} worker 线程里操作页面，worker 抛出的 {@link AssertionError} 会被
 * {@code runOnce} 的 {@code catch (Throwable)} 包装进 {@code ContextTaskResult}，
 * 由 {@code assertAllSucceeded} 在<b>编排线程</b>回放判红——此时是「并发批次结束后判红」，
 * 而非「当前步骤立即判红」。原始异常经 {@code LifecycleBusBridge.replayFailures}
 * 的 cause 链保留，不被降级成摘要文本。</p>
 *
 * <p><b>报告证据</b>：不在本类写。按 context 消费失败时，
 * {@code RouteRuntimeImpl.drainSettledAssertionFailures()} 已把它们喂给
 * {@code RouteMonitorFailureReportSink}（类级 {@code ConcurrentHashMap} +
 * {@code CopyOnWriteArrayList} + 喂入即落盘，线程安全、不依赖报告 ThreadLocal 句柄），
 * 这是权威通道；step 级补充证据仍由 web 侧 {@code StepFailureAggregator} 在场景线程写入。</p>
 *
 * <p><b>晚到失败的判红投影</b>：{@link #drainForEvidence(Object)} 取到失败时，除留痕外还会
 * 登记 {@link RouteAssertionVerdict}，由 {@code CucumberWithPw} 在 JUnit
 * {@code RunNotifier.fireTestFinished} 补发 {@code fireTestFailure}。这样「最后一步内
 * 完全没有框架业务操作、失败直到场景收尾才被消费」也不会假绿。本类不抛错——事件回调里
 * 抛异常会被生命周期总线隔离，且会打断 Cucumber 的 {@code TestCaseFinished} 派发链。</p>
 *
 * <p><b>消费式幂等 / 降级</b>：失败取走即清空；探针缺席 / context 为 null /
 * 探针自身异常时静默放行（路由模块未启用场景零行为变化）。</p>
 */
public final class RouteAssertionCheckpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteAssertionCheckpoint.class);

    private RouteAssertionCheckpoint() {
    }

    /**
     * 业务线程检查点：消费当前 context 已定案的 MONITOR 断言失败，非空即抛
     * {@link AssertionError}，使当前步骤立即判红。
     *
     * @param context 当前 case 的 BrowserContext（null 时直接放行）
     * @throws AssertionError 本 case 存在已定案的 MONITOR 断言失败
     */
    public static void checkNow(Object context) {
        List<RouteAssertionFailure> failures = drain(context);
        if (failures.isEmpty()) {
            return;
        }
        String report = buildReport(failures);
        LOGGER.error("MONITOR assertion failed - failing current step:\n{}", report);
        throw new AssertionError("Route MONITOR assertion failed - failing current step:\n" + report);
    }

    /**
     * 场景收尾：消费当前 context 剩余失败、留痕，并登记 {@link RouteAssertionVerdict}
     * 供 Runner 层投影为 case 判红；<b>本方法不抛错</b>——事件回调抛异常会被生命周期总线
     * 隔离，且会打断 Cucumber 的 {@code TestCaseFinished} 派发链（afterHooks 之后的收尾、
     * 报告插件、JUnitReporter 都收不到事件）。
     *
     * <p>消费动作本身保证失败不会遗留到复用同一 Context 的下一条 case；失败明细已由
     * route 侧全局报告 sink 归集。</p>
     */
    public static void drainForEvidence(Object context) {
        List<RouteAssertionFailure> failures = drain(context);
        if (failures.isEmpty()) {
            return;
        }
        String report = buildReport(failures);
        LOGGER.error("MONITOR assertion failures remaining at scenario end:\n{}", report);
        RouteAssertionVerdict.record(report);
    }

    private static List<RouteAssertionFailure> drain(Object context) {
        if (context == null) {
            return List.of();
        }
        RouteAssertionProbe probe = RouteAssertionRegistry.get();
        if (probe == null) {
            return List.of();
        }
        try {
            List<RouteAssertionFailure> failures = probe.drainAndResolveFailures(context);
            return failures == null ? List.of() : failures;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[Route] assertion probe drain skipped: {}", e.toString());
            return List.of();
        }
    }

    private static String buildReport(List<RouteAssertionFailure> failures) {
        StringBuilder sb = new StringBuilder(failures.size() * 96);
        for (RouteAssertionFailure failure : failures) {
            sb.append("  ").append(failure.summary()).append(System.lineSeparator());
        }
        return sb.toString().stripTrailing();
    }
}

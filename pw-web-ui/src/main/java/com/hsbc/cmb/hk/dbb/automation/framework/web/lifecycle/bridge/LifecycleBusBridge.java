package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 桥接协作类（设计文档 9.10-③ / 9.3）：并发任务失败回放（DEV-V5，移除 框架 依赖）。
 *
 * <p>原实现经 框架 事件总线把并发任务失败标记到 框架 报告；DEV-V5 下 v2 的
 * {@code @Step} 拦截器会自行把抛出的异常记录到报告，故本类只需在编排线程汇总失败并抛出
 * {@link CompletionException}（若存在失败），失败标记交由 v2 处理。</p>
 *
 * <p>{@code recordCrashRecoveryIfAny} 与 {@code clearStepFailureState} 在 v2 下不再需要
 * （v2 不会因"已失败"而静默跳过后续步骤），保留方法签名以兼容调用方，内部降级为 no-op。</p>
 */
public final class LifecycleBusBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleBusBridge.class);

    private LifecycleBusBridge() {
    }

    /**
     * 在编排线程回放失败：对每个失败项抛 {@link CompletionException}（message 含线程/耗时/页面错误诊断），
     * 由 v2 的 {@code @Step} 拦截器负责报告标记。
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
                }
            }
        }
        if (!summaries.isEmpty()) {
            throw new CompletionException("ConcurrentContextExecutor: " + summaries.size()
                    + " task(s) failed -> " + summaries, null);
        }
    }

    /**
     * 标注"浏览器崩溃恢复重跑"事件（WEB-P1-4 验收 ②：重跑事件在报告中可见）。
     *
     * <p>DEV-V5：v2 报告本身会记录最终失败与重跑过程，本方法降级为 no-op（仅保留调试日志，
     * 不引入对 v2 报告内部 API 的耦合）。</p>
     *
     * @param results runAll 返回的等序结果
     */
    public static void recordCrashRecoveryIfAny(List<? extends ContextTaskResult<?>> results) {
        if (results == null) {
            return;
        }
        for (ContextTaskResult<?> r : results) {
            if (r.isReplayed()) {
                LOGGER.debug("[LifecycleBusBridge] task '{}' was auto-replayed after {} (v2 report records the outcome)",
                        r.getTaskName(), r.getRecoveryType());
            }
        }
    }

    /**
     * 业务捕获并恢复了 {@code @Step} 代理步骤抛出的异常后，解除 v2 对后续步骤的跳过。
     *
     * <p>DEV-V5：v2 不实现"失败后静默跳过后续 @Step"的机制（与 框架 不同），故本方法降级为 no-op。</p>
     */
    public static void clearStepFailureState() {
        // no-op under v2
    }
}

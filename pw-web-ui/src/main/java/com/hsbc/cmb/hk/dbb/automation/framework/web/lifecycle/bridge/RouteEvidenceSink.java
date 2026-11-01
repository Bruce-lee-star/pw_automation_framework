package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextOutcomeBinding;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigReader;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceSink} 的 框架 实现 —— 待报告队列 + 主线程 flush（对齐 master 的
 * {@code framework/web/route/util/SerenityReporter}）。
 *
 * <p><b>为什么必须入队而不是直接写</b>：route 事件线程不一定有测试线程上下文，故 {@link #record}
 * 只在能解析到显式 outcome ID 时入队；收尾线程仅 flush 自己当前 outcome 的记录，避免 JVM 级队列把并行
 * 场景证据写入错误报告。无法解析归属的记录计数并告警，不会被猜测性地挂到其它用例。</p>
 *
 * <p><b>降级语义</b>：非报告环境（如纯 JUnit 单测 / 未装配报告引擎）经 {@code ReportingBridge.isReportingActive()}
 * 短路 —— 此时直接清空队列并静默返回，绝不产生 ERROR 噪音、也不积压。进程级上限由
 * {@code route.evidence.max.pending}（默认 500）控制，每个 outcome 默认最多 100 条；
 * 单场景超限只淘汰自身旧记录，进程级满载时优先从过度占用的场景腾挪空间。旧系统属性
 * {@code serenity.route.maxPendingRecords} 仍兼容。运行计数可通过本类的只读指标方法获取。</p>
 */
public final class RouteEvidenceSink implements com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteEvidenceSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteEvidenceSink.class);

    /** 进程级待报告上限。 */
    private static final int MAX_PENDING = configuredLimit(
            ConfigRegistry.ROUTE_EVIDENCE_MAX_PENDING, "serenity.route.maxPendingRecords");

    /** 单个 outcome 的待报告上限。 */
    private static final int MAX_PENDING_PER_OUTCOME = Math.min(MAX_PENDING, configuredLimit(
            ConfigRegistry.ROUTE_EVIDENCE_MAX_PENDING_PER_OUTCOME, null));

    /** 待报告记录按 outcome id 隔离；flush 只消费当前场景所属记录。 */
    private static final Map<String, ArrayDeque<EvidenceRecord>> PENDING = new LinkedHashMap<>();
    private static int pendingCount;
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong UNATTRIBUTED = new AtomicLong();
    private static final AtomicLong WRITE_FAILURES = new AtomicLong();
    private static final AtomicLong WRITTEN = new AtomicLong();

    @Override
    public void record(String operation, String url, String detail) {
        record(operation, url, detail, null);
    }

    @Override
    public void record(String operation, String url, String detail, Object requestContext) {
        try {
            var bridge = ReportingBridgeRegistry.get();
            String outcomeId = outcomeId(bridge, requestContext);
            if (outcomeId == null || outcomeId.isBlank()) {
                long count = UNATTRIBUTED.incrementAndGet();
                if (shouldLogCount(count)) {
                    LOGGER.warn("[Route] network evidence has no outcome context and was not queued "
                            + "(unattributed count={})", count);
                }
                return;
            }
            // A context-bound callback can use a ThreadLocal step only if that thread is bound to the same outcome.
            String stepId = requestContext == null || outcomeId.equals(bridge.currentOutcomeId())
                    ? bridge.currentStepId() : null;
            synchronized (PENDING) {
                ArrayDeque<EvidenceRecord> outcomeQueue = PENDING.computeIfAbsent(outcomeId,
                        ignored -> new ArrayDeque<>());
                if (outcomeQueue.size() >= MAX_PENDING_PER_OUTCOME) {
                    outcomeQueue.poll();
                    pendingCount--;
                    long count = DROPPED.incrementAndGet();
                    if (shouldLogCount(count)) {
                        LOGGER.warn("[Route] outcome {} exceeded {} pending evidence records — dropping its oldest "
                                + "record (dropped count={})", outcomeId, MAX_PENDING_PER_OUTCOME, count);
                    }
                }
                if (pendingCount >= MAX_PENDING && !rebalanceFor(outcomeId)) {
                    if (outcomeQueue.isEmpty()) {
                        PENDING.remove(outcomeId);
                    }
                    long count = DROPPED.incrementAndGet();
                    if (shouldLogCount(count)) {
                        LOGGER.warn("[Route] pending evidence queue is full at {} records — rejecting new evidence "
                                + "for outcome {} (dropped count={})", MAX_PENDING, outcomeId, count);
                    }
                    return;
                }
                outcomeQueue.offer(new EvidenceRecord(
                        operation == null ? "-" : operation, maskUrl(url), detail, stepId));
                pendingCount++;
            }
        } catch (Exception e) {
            noteWriteFailure("enqueue", e);
        }
    }

    private static boolean rebalanceFor(String incomingOutcomeId) {
        Map.Entry<String, ArrayDeque<EvidenceRecord>> donor = PENDING.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(incomingOutcomeId) && entry.getValue().size() > 1)
                .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                .orElse(null);
        if (donor == null) {
            return false;
        }
        donor.getValue().poll();
        pendingCount--;
        long count = DROPPED.incrementAndGet();
        if (shouldLogCount(count)) {
            LOGGER.warn("[Route] pending evidence queue is full at {} records — dropping one record from "
                            + "overrepresented outcome {} to admit evidence for {} (dropped count={})",
                    MAX_PENDING, donor.getKey(), incomingOutcomeId, count);
        }
        return true;
    }

    private static int configuredLimit(ConfigRegistry key, String legacyKey) {
        ConfigReader.Resolved resolved = ConfigReader.resolveWithLayer(key.key(), key.defaultValue());
        String raw = resolved.layer() == ConfigReader.Layer.DEFAULT && legacyKey != null
                ? ConfigReader.getProperty(legacyKey)
                : resolved.value();
        if (raw == null) {
            raw = key.defaultValue();
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Report malformed values below, then use the registered safe default.
        }
        LOGGER.warn("[Route] invalid {} value '{}'; using registered default {}",
                key.key(), raw, key.defaultValue());
        return Integer.parseInt(key.defaultValue());
    }

    private static void noteWriteFailure(String operation, Exception error) {
        long count = WRITE_FAILURES.incrementAndGet();
        if (shouldLogCount(count)) {
            LOGGER.warn("[Route] evidence {} failed (failure count={}): {}",
                    operation, count, error.toString());
        }
    }

    private static boolean shouldLogCount(long count) {
        return count <= 8 || (count & (count - 1)) == 0;
    }

    private static String outcomeId(
            com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridge bridge,
            Object requestContext) {
        if (requestContext == null) {
            return bridge.currentOutcomeId();
        }
        BrowserContext context;
        if (requestContext instanceof BrowserContext browserContext) {
            context = browserContext;
        } else if (requestContext instanceof Request request) {
            try {
                context = request.frame().page().context();
            } catch (RuntimeException unavailableFrame) {
                return null;
            }
        } else {
            return null;
        }
        return ContextOutcomeBinding.outcomeId(context);
    }

    @Override
    public void flush() {
        var bridge = ReportingBridgeRegistry.get();
        if (!bridge.isReportingActive()) {
            int drained;
            synchronized (PENDING) {
                drained = pendingCount;
                PENDING.clear();
                pendingCount = 0;
            }
            LOGGER.debug("[Route] evidence flush skipped (no 框架 listener), drained {} record(s)", drained);
            return;
        }
        String outcomeId = bridge.currentOutcomeId();
        if (outcomeId == null || outcomeId.isBlank()) {
            LOGGER.warn("[Route] evidence flush skipped: current thread has no outcome context");
            return;
        }
        List<EvidenceRecord> batch = new ArrayList<>();
        synchronized (PENDING) {
            ArrayDeque<EvidenceRecord> outcomeQueue = PENDING.remove(outcomeId);
            if (outcomeQueue != null) {
                batch.addAll(outcomeQueue);
                pendingCount -= batch.size();
            }
        }
        int written = 0;
        for (EvidenceRecord item : batch) {
            try {
                String title = String.format("[Route %s] %s", item.operation(), item.url());
                bridge.recordReportData(title, item.detail());
                //  同时进结构化的 apiOperations：报告页按「每个抓取到的 API」逐条展示，
                //  证据区不再渲染（见 test-outcome 模板），仅保留 JSON 侧诊断。
                //  stepId 为入队时抓到的归属步骤：有则挂到该步骤下（与 Route Register 一致），
                //  无（钩子 / 场景边界产生）则按用例级兜底。
                bridge.recordApiOperation(item.stepId(),
                        item.detail() == null || item.detail().isEmpty() ? title : title + " — " + item.detail());
                written++;
            } catch (Exception e) {
                noteWriteFailure("report write", e);
            }
        }
        if (written > 0) {
            WRITTEN.addAndGet(written);
            // 仅在真的写了东西时记录（步骤级 flush 大多为空）：便于回答"证据是在哪一步落进报告的"
            LOGGER.info("[Route] route evidence written to report: {} record(s)", written);
        }
    }

    public static long droppedCount() {
        return DROPPED.get();
    }

    public static long unattributedCount() {
        return UNATTRIBUTED.get();
    }

    public static long writeFailureCount() {
        return WRITE_FAILURES.get();
    }

    public static long writtenCount() {
        return WRITTEN.get();
    }

    static int pendingCount() {
        synchronized (PENDING) {
            return pendingCount;
        }
    }

    static int maxPendingPerOutcome() {
        return MAX_PENDING_PER_OUTCOME;
    }

    static int maxPending() {
        return MAX_PENDING;
    }

    static void clearPendingForTests() {
        synchronized (PENDING) {
            PENDING.clear();
            pendingCount = 0;
        }
    }

    private record EvidenceRecord(String operation, String url, String detail, String stepId) {
    }

    /**
     * 报告<b>标题</b>的 URL 脱敏：整段丢弃 query（只留 path）。
     *
     * <p>刻意比 {@code SensitiveDataSanitizer.sanitizeUrl}（正文/日志侧用：仅当命中敏感 query 键/值
     * 才丢整个 query）更严 —— 标题是报告的"门面"，宁可过度遮蔽。正文那侧由
     * {@code RouteDsl.describeCaptured} 统一收口，两处都脱敏、口径各自说明。</p>
     */
    private static String maskUrl(String url) {
        if (url == null) {
            return "-";
        }
        int queryAt = url.indexOf('?');
        return queryAt < 0 ? url : url.substring(0, queryAt) + "?***";
    }
}

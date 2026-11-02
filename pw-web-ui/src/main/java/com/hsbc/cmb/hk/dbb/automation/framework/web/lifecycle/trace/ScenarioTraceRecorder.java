package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.common.reporting.ReportingBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ContextOutcomeBinding;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Tracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Scenario 级 trace 分段录制器（方案 A，2026-09-17 评审落地）。
 *
 * <h2>为什么需要它</h2>
 * <p>原实现把 `tracing().start()` 放在 <b>context 创建</b>、`stop()` 放在 <b>context 关闭</b>，
 * 于是 trace 的时间区间 = <b>Context 存活期</b>，而不是 case 执行期：
 * <ul>
 *   <li>区间≈该用例，但起点早于用例（含 context 建立、会话恢复、登录）；故本录制器改为 scenario-scoped，
 *       使每个用例的 trace 独立、文件名/报告标注与其执行用例一致。</li>
 * </ul>
 *
 * <h2>做法（Playwright 原生分段录制）</h2>
 * <p>官方协议（本仓 Playwright 1.62.0 {@code Tracing} 的 javadoc）：每个 context 调一次
 * {@code start(opts)}，随后反复 {@code startChunk()} / {@code stopChunk(path)}，每次得到一个独立 trace 文件。
 * 本类据此在 <b>scenario 边界</b>切段：
 * <pre>
 *   context 创建 → start(opts)             // chunk #0 开始（含 context 建立/会话恢复）
 *   scenario 结束 → stopChunk(trace-&lt;id&gt;-&lt;start&gt;-&lt;end&gt;-PASS|FAIL.zip)
 *   下一个 scenario 开始 → startChunk()    // 2026-09-30 收口后每 case 重建 context：chunk #0 覆盖本用例
 *   context 关闭 → 若仍有活动 chunk 则兜底导出（ONCLOSE），随后 stop()
 * </pre>
 * 于是 <b>「trace 的时间 == case 的执行时间」成为结构保证</b>，文件名/报告同时带 scenarioId 与起止时间。
 *
 * <h2>健壮性</h2>
 * <ul>
 *   <li>导出走【每调用独立守护线程 + 有界 join】（无固定线程池、无硬编码池大小、线程按导出需求动态创建）；
 *       任一次 stopChunk 卡死只放弃该次导出，不影响后续导出、也不阻塞 JVM 退出；</li>
 *   <li><b>超时/异常一律删除残缺文件</b> —— 坏 zip 挂进报告比没有更糟；</li>
 *   <li>scenarioId 由调用方显式传入（不依赖 MDC），规避"套件收尾/失败清理路径上 MDC 已解绑 →
 *       文件名退化为 unknown 或错挂到别的用例"；</li>
 *   <li>可通过 {@code playwright.context.trace.chunk.per.scenario=false} 回退为整段录制（逃生开关）。</li>
 * </ul>
 *
 * @apiNote framework-internal：由 框架 监听器 / context 生命周期调用，业务代码不得依赖。
 */
public final class ScenarioTraceRecorder {

    private static final Logger logger = LoggerFactory.getLogger(ScenarioTraceRecorder.class);

    /**
     * 证据兜底目录（固定值，<b>非配置</b>）：仅当报告未指导落点（无活动用例句柄 / 未接 v2 报告）时，
     * trace.zip 才落到此处作证据；正常使用 v2 报告时 trace 直接落 per-case（{@link #resolveTraceTarget}），
     * 此目录不被使用。
     */
    static final String TRACE_DIR = "target/reports/traces";

    /** trace 保存目录（固定 {@link #TRACE_DIR}，不由配置覆盖——trace 落点由报告经 {@code traceTarget()} 指导）。 */
    static String traceDir() {
        return TRACE_DIR;
    }

    /**
     * 解析本次导出的目标文件：优先采用<b>报告指导的落点</b>（{@code ReportingBridge#traceTarget()}，
     * 通常是 per-case 的 {@code trace.zip}）——trace 直接写进报告目录，不产生“框架 traces/ + per-case”
     * 两份、也无须事后移动；报告未指导（无活动用例句柄 / 未接报告）时落固定 {@link #TRACE_DIR} 作证据兜底
     * （此时文件名带 slug/时间戳/结果，便于在扁平目录中区分，由 TraceRetention 治理）。
     */
    private static Path resolveTraceTarget(String fallbackName) {
        try {
            Path reportTarget = ReportingBridgeRegistry.get().traceTarget();
            if (reportTarget != null) {
                return reportTarget;
            }
        } catch (Exception ignore) {
            // 桥缺失/异常 → 回落固定证据目录
        }
        return Paths.get(traceDir(), fallbackName);
    }

    /**
     * 上下文感知的落点解析：优先使用 context 绑定的 outcomeId，避免收尾线程上的进程级活动用例快照
     * 抢先命中并将 trace 落入并行场景的错误目录；无绑定时才回退到当前线程报告句柄。
     */
    static Path resolveTraceTarget(BrowserContext context, String fallbackName) {
        try {
            String id = context != null ? ContextOutcomeBinding.outcomeId(context) : null;
            if (id != null) {
                Path byId = ReportingBridgeRegistry.get().traceTargetByOutcomeId(id);
                if (byId != null) {
                    return byId;
                }
                return Paths.get(traceDir(), fallbackName);
            }
            Path reportTarget = ReportingBridgeRegistry.get().traceTarget();
            if (reportTarget != null) {
                return reportTarget;
            }
        } catch (Exception ignore) {
            // 桥缺失/异常 → 回落固定证据目录
        }
        return Paths.get(traceDir(), fallbackName);
    }

    /** 文件名片段最大长度（防超长名，文件系统上限 255）。 */
    private static final int MAX_NAME_FRAGMENT = 120;

    //  线程级（非用例级 TestContextHolder）：Cucumber @After 会清空用例级 TestContextHolder，
    //    而 Context 是线程级持有，故 trace 的 per-scenario 状态必须 thread-scoped，才能在场景收尾时可靠读取。
    private static final ThreadLocal<Boolean> CHUNK_ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<String> SCENARIO_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> CHUNK_START_MS = new ThreadLocal<>();

    private ScenarioTraceRecorder() {
    }

    private static boolean traceEnabled() {
        return FrameworkConfigManager.getBoolean(WebFrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_ENABLED);
    }

    private static boolean chunkPerScenario() {
        return FrameworkConfigManager.getBoolean(WebFrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_CHUNK_PER_SCENARIO);
    }

    private static long exportTimeoutSeconds() {
        return Math.max(1, WebFrameworkConfig.PLAYWRIGHT_CONTEXT_CLOSE_TRACE_TIMEOUT_SECONDS.getIntValue());
    }

    /** context 创建：开启 tracing（chunk #0 同步开始，覆盖 context 建立/会话恢复/首个用例）。 */
    public static void onContextCreated(BrowserContext context) {
        if (context == null || !traceEnabled()) {
            return;
        }
        try {
            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(PlaywrightManager.config().isTraceScreenshots())
                    .setSnapshots(PlaywrightManager.config().isTraceSnapshots())
                    .setSources(PlaywrightManager.config().isTraceSources()));
            setChunkActive(true);
            logger.debug("[trace] chunk #0 started with context (chunkPerScenario={})", chunkPerScenario());
        } catch (Exception e) {
            // tracing 未启用/已停止时 start 可能抛异常：降级，绝不影响 context 创建
            logger.debug("[trace] start skipped: {}", e.getMessage());
        }
    }

    /**
     * scenario 开始：记录用例标识与起点；若当前 chunk 已被上一个用例导出（2026-09-30 收口后每 case
     * 重建 Context、续段场景已不存在，此分支为防御性保留），则显式开新 chunk。
     *
     * @param scenarioId 用例标识（由监听器显式传入，如 {@code uniqueTestName}）
     */
    public static void onScenarioStart(String scenarioId) {
        SCENARIO_ID.set(scenarioId);
        CHUNK_START_MS.set(System.currentTimeMillis());
        if (!traceEnabled() || !chunkPerScenario()) {
            return;
        }
        BrowserContext context = currentContext();
        if (context == null || Boolean.TRUE.equals(chunkActive())) {
            // context 尚未懒创建（创建时会 start → chunk #0 覆盖本用例）；或 chunk #0 仍活动（首个用例）
            return;
        }
        try {
            context.tracing().startChunk();
            setChunkActive(true);
            logger.debug("[trace] chunk started for scenario '{}'", scenarioId);
        } catch (Exception e) {
            logger.debug("[trace] startChunk skipped: {}", e.getMessage());
        }
    }

    /**
     * scenario 结束：把本用例的 chunk 导出为独立 trace 文件并挂进报告（含起止时间）。
     *
     * @param scenarioId 用例标识（显式传入；{@code null} 时回退到 {@link #onScenarioStart} 记录的值）
     * @param failed     该用例是否失败：{@code null}=未知（收尾兜底路径），写入文件名便于保留策略优先保留失败证据
     */
    public static void onScenarioEnd(String scenarioId, Boolean failed) {
        String id = scenarioId != null ? scenarioId : SCENARIO_ID.get();
        Long startMs = CHUNK_START_MS.get();
        long endMs = System.currentTimeMillis();
        String outcome = failed == null ? "UNKNOWN" : (failed ? "FAIL" : "PASS");
        try {
            BrowserContext context = currentContext();
            if (!traceEnabled() || context == null || !Boolean.TRUE.equals(chunkActive())) {
                return;
            }
            Path file = resolveTraceTarget(context,
                    traceFileName(id, startMs == null ? endMs : startMs, outcome));
            exportChunk(context, file, id, startMs, endMs);
        } finally {
            setChunkActive(false);
            clearScenarioState();
        }
    }

    /**
     * context 关闭：若仍有活动 chunk（该用例未走到 {@link #onScenarioEnd}，如收尾/异常路径），
     * 兜底导出为 {@code ONCLOSE} 文件；随后结束 tracing。
     */
    public static void onContextClosing(BrowserContext context) {
        //  注意：不以实时 traceEnabled() 早退——运行中若关闭 trace 开关，仍须把已开启的 tracing 落盘，
        //  否则 context 关闭时 trace 残留未 flush（P2-21）。未开启 tracing 时走 else 分支的 stop() 被 try/catch 兜底。
        if (context == null) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(chunkActive())) {
                //  收尾线程（CloseGuard worker）上 SCENARIO_ID 这一 ThreadLocal 恒为 null；改为优先取
                //    context 绑定的 outcomeId（精确、跨线程可见），回落 SCENARIO_ID 再回落 unknown。
                String id = ContextOutcomeBinding.outcomeId(context);
                if (id == null) {
                    id = SCENARIO_ID.get();
                }
                Long startMs = CHUNK_START_MS.get();
                long endMs = System.currentTimeMillis();
                Path file = resolveTraceTarget(context,
                        traceFileName(id, startMs == null ? endMs : startMs, "ONCLOSE"));
                exportChunk(context, file, id, startMs, endMs);
            } else {
                // 无待导出 chunk：仅结束 tracing（chunk #0 若从未导出，说明本 context 未绑定过用例）
                context.tracing().stop();
            }
        } catch (Exception e) {
            logger.debug("[trace] stop skipped on context close: {}", e.getMessage());
        } finally {
            setChunkActive(false);
        }
    }

    /** 导出当前 chunk 到指定文件（每导出独立守护线程 + 有界 join + 超时放弃 + 失败删残片 + 挂报告）。 */
    private static void exportChunk(BrowserContext context, Path file, String scenarioId,
                                    Long startMs, long endMs) {
        try {
            //  Path.getParent() 对无父路径返回 null：显式判空（本仓既有写法，SpotBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE）
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            //  A-10（doc 22）：原实现用【单一共享线程】跑 stopChunk（CompletableFuture.runAsync(..., 该线程池)）。
            //    stopChunk 是阻塞式 native 调用、不可中断；一旦卡住该线程被永久占住 → 此后【所有】导出排队超时、
            //    删除残片 → 后续场景 trace 全丢。改为「每导出独立守护线程 + 有界 join」：卡住的线程是 daemon，
            //    JVM 退出后由 Playwright driver 进程树回收，不阻塞后续导出、也不阻塞 JVM 退出
            //    （线程动态调整：按需创建、无硬编码池大小）。
            Thread exporter = new Thread(() -> {
                try {
                    context.tracing().stopChunk(new Tracing.StopChunkOptions().setPath(file));
                } catch (Throwable t) {
                    logger.warn("[trace] stopChunk failed for {}: {}", file.getFileName(), t.getMessage());
                }
            }, "pw-context-trace-" + (scenarioId == null ? "unknown" : sanitizeForFileName(scenarioId)));
            exporter.setDaemon(true);
            exporter.start();
            try {
                exporter.join(TimeUnit.SECONDS.toMillis(exportTimeoutSeconds()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (exporter.isAlive()) {
                //  超时：放弃该次导出（线程 daemon，随 JVM 退出被 driver 回收），删残片，记可观测信号；
                //    注意：不要在此再 join 等待 —— 那会重新把调用线程拖死。
                logger.warn("[trace] export of {} exceeded {}s and was abandoned (trace capability degraded for "
                                + "this scenario; subsequent exports are unaffected). The exporter thread is a daemon "
                                + "and will be reclaimed by the Playwright driver on JVM exit.",
                        file.getFileName(), exportTimeoutSeconds());
                deletePartialFile(file, "timeout(" + exportTimeoutSeconds() + "s)");
                return;
            }
            attachToReport(file, scenarioId, startMs, endMs,
                    context != null ? ContextOutcomeBinding.outcomeId(context) : null);
            TraceRetention.pruneIfDue();
        } catch (Exception e) {
            // tracing 未启动 / 已停止 / 写盘失败 / 目标被占用：一律删残片，绝不把坏 zip 挂进报告
            deletePartialFile(file, e.getMessage());
        }
    }

    /** 删除不完整/失败的 trace 文件（不存在则 no-op）。 */
    private static void deletePartialFile(Path file, String reason) {
        try {
            if (Files.exists(file)) {
                Files.delete(file);
                logger.warn("[trace] discarded incomplete trace file {} ({})", file.getFileName(), reason);
            } else {
                logger.debug("[trace] export failed ({}): {}", reason, file.getFileName());
            }
        } catch (Exception ignored) {
            logger.warn("[trace] failed to delete incomplete trace file {}: {}", file.getFileName(), ignored.getMessage());
        }
    }

    /**
     * 把已落盘的 trace 登记进报告（经 {@link ReportingBridgeRegistry} 端口；框架 构建下落到 框架 报告，
     * v2 构建下落到 v2 报告）。任何失败（非报告线程 / 步骤上下文已结束）均降级为 debug，绝不抛出。
     */
    private static void attachToReport(Path tracePath, String scenarioId, Long startMs, long endMs,
                                       String outcomeId) {
        try {
            if (tracePath == null || !Files.exists(tracePath)) {
                return;
            }
            String title = "TRACE" + (scenarioId != null ? ": " + scenarioId : "");
            StringBuilder contents = new StringBuilder();
            //  只记采集文件名 + 执行窗口，不写绝对路径：报告会把该文件落入 per-case（<outcomeId>/trace.zip），
            //    采集路径随之失效，写进去只会是陈旧信息；文件名（含 slug/时间戳/结果）本身就是稳定标识，
            //    未接报告时也可凭它在 traces/ 定位（TraceRetention 同目录治理）。
            contents.append("trace=").append(tracePath.getFileName());
            if (startMs != null) {
                contents.append("\nwindow=")
                        .append(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(startMs)))
                        .append(" → ")
                        .append(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(endMs)))
                        .append(" (").append(Math.max(0, endMs - startMs)).append("ms)")
                        .append("  [覆盖本用例执行窗口]");
            }
            ReportingBridgeRegistry.get().recordReportData(title, contents.toString());
            //  v2 报告：把 trace 落到 <outcomeId>/trace.zip 并设 tracePath，使 per-case 页出现
            //    "Play trace" 按钮（recordReportData 仅落证据文本、不驱动该按钮）。旧报告/无活动句柄下 no-op，
            //    此时文件留在 traces/ 由 TraceRetention 治理。
            //    同时把录制窗口（起止时刻 + 时长）一并交给报告，供 Trace 卡片展示（旧报告经默认方法回落到只登记路径）。
            //    归属优先用 context 绑定的 outcomeId（并行下精确）；缺失时桥回落最佳努力（句柄/活动用例）。
            if (startMs != null) {
                if (outcomeId != null) {
                    ReportingBridgeRegistry.get().attachTrace(outcomeId, tracePath,
                            windowTime(startMs), windowTime(endMs), Math.max(0L, endMs - startMs));
                } else {
                    ReportingBridgeRegistry.get().attachTrace(tracePath,
                            windowTime(startMs), windowTime(endMs), Math.max(0L, endMs - startMs));
                }
            } else {
                if (outcomeId != null) {
                    ReportingBridgeRegistry.get().attachTrace(outcomeId, tracePath);
                } else {
                    ReportingBridgeRegistry.get().attachTrace(tracePath);
                }
            }
            logger.debug("[trace] recorded {} to 框架 report (outcomeId={})",
                    tracePath.getFileName(), outcomeId);
        } catch (Exception e) {
            logger.debug("[trace] record to report skipped: {}", e.getMessage());
        }
    }

    /**
     * 构造 trace 文件名：{@code trace-<safe(scenarioId)>-<startMs>-<endMs>-<outcome>.zip}。
     *
     * <p>带上 <b>起止时间</b>（原实现只有结束时间戳），既可按时间窗定位，也让"文件名即证据区间"。
     *
     * @param scenarioId 用例标识（可为 null → {@code unknown}）
     * @param startMs    chunk 起点（epoch ms）
     * @param endMs      chunk 终点（epoch ms）
     * @param outcome    {@code PASS} / {@code FAIL} / {@code ONCLOSE}（保留策略据此优先保留失败证据）
     * @return 文件名（不含目录）
     */
    static String traceFileName(String scenarioId, long startMs, String outcome) {
        return "trace-" + slugForFileName(scenarioId) + "-" + readableStamp(startMs)
                + "-" + (outcome == null ? "UNKNOWN" : outcome) + ".zip";
    }

    /** 可读时间戳（UTC，{@code yyyyMMdd-HHmmss}）——替代原始 epoch 毫秒，文件名更易读且确定（测试不受时区影响）。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    static String readableStamp(long epochMs) {
        return STAMP.format(Instant.ofEpochMilli(epochMs));
    }

    /**
     * 录制窗口时刻格式（{@code HH:mm:ss.SSS}，本地时区）。
     *
     * <p>刻意用 {@code DateTimeFormatter} 而非 {@code SimpleDateFormat}：后者<b>非线程安全</b>，
     * 而本类的 trace 导出可能并发触发（并行 scenario），共享实例会产出错乱时间。
     */
    private static final java.time.format.DateTimeFormatter WINDOW =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    /** epoch 毫秒 → {@code HH:mm:ss.SSS}（本地时区）。 */
    private static String windowTime(long epochMs) {
        return WINDOW.format(java.time.LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault()));
    }

    /**
     * 将任意字符串转为文件系统安全片段：非 {@code [A-Za-z0-9._-]} 字符替换为 {@code _}，截断至 120 字符。
     *
     * @param raw 原始字符串（允许 null）
     * @return 安全片段，绝不为 null/空
     */
    static String sanitizeForFileName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_NAME_FRAGMENT));
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME_FRAGMENT; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    /**
     * 可读 slug：转小写，把非 {@code [a-z0-9]} 字符段折叠为单个 {@code -}、去首尾 {@code -}、截断至 120。
     * 比逐字符换 {@code _} 的 {@link #sanitizeForFileName} 更适合人读（如 {@code "testbed - 3MB base64 UI"}
     * → {@code testbed-3mb-base64-ui}），用于 trace 文件名。
     */
    static String slugForFileName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(Math.min(lower.length(), MAX_NAME_FRAGMENT));
        boolean pendingDash = false;
        for (int i = 0; i < lower.length() && sb.length() < MAX_NAME_FRAGMENT; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingDash && sb.length() > 0) {
                    sb.append('-');
                }
                pendingDash = false;
                sb.append(c);
            } else {
                pendingDash = true;
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private static BrowserContext currentContext() {
        try {
            //  优先用例级 CONTEXT_KEY；Cucumber @After（ScenarioContext.end → ctx.clear()）会早于框架
            //    场景收尾清空用例级 TestContextHolder，故 CONTEXT_KEY 此时可能为 null —— 回退到线程级记录
            //    （ContextRegistryImpl.closeContext/getContext 同源兜底），保证 onScenarioEnd 仍能拿到本线程 Context 导出 trace。
            BrowserContext ctx = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
            return ctx != null ? ctx : PlaywrightManager.currentContextForThread();
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean chunkActive() {
        return CHUNK_ACTIVE.get();
    }

    private static void setChunkActive(boolean active) {
        CHUNK_ACTIVE.set(active);
    }

    private static void clearScenarioState() {
        SCENARIO_ID.remove();
        CHUNK_START_MS.remove();
    }
}

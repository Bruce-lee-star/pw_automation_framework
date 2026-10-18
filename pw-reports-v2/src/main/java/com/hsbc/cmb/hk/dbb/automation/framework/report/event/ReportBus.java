package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestAttachment;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import com.hsbc.cmb.hk.dbb.automation.framework.report.sink.HttpPushSink;
import com.hsbc.cmb.hk.dbb.automation.framework.report.sink.LocalReportSink;
import com.hsbc.cmb.hk.dbb.automation.framework.report.sink.LogSink;
import com.hsbc.cmb.hk.dbb.automation.framework.report.sink.ReportSink;
import com.hsbc.cmb.hk.dbb.automation.framework.report.step.PwStepFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.console.ConsoleCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 报告总线——线程安全的录制中枢（方案 v3 §5，R1/R2/R3/R4）。
 *
 * <p>三条不变式：
 * <ol>
 *   <li><b>证据入 bus 必经 ScrubGate</b>（attachEvidence 内部执行，调用方无法绕过）；</li>
 *   <li><b>sink fan-out 逐个 try/catch</b>——一个 sink 死，不影响测试本身与其他 sink；</li>
 *   <li><b>finishRun 永不抛</b>——JSON 优先、HTML 降级、失败只记日志与 metrics。</li>
 * </ol>
 *
 * <p>并发模型：{@code ConcurrentHashMap<outcomeId, RecordingSession>} + 显式
 * {@link StepHandle}。零参便捷 API 仅限场景线程（ThreadLocal 解析）；跨线程必须
 * {@link #captureHandle()} 后显式传句柄。
 *
 * <p>kill-switch：{@code pw.report.enabled=false} → 所有 API 静默 no-op / 直通，
 * 测试行为与无本模块一致。
 */
public final class ReportBus {

    /**
     * 报告数据来源——用于防止 Cucumber 路（{@code PwReportPlugin}）与 FrameworkListener 桥接重复计数。
     */
    public enum ReportSource {
        /** Cucumber 事件源：PwReportPlugin 已产出 outcome，FrameworkListener 桥接退化为 no-op。 */
        CUCUMBER,
        /** 通用 / 非 Cucumber（Serenity-bus 驱动）：由 FrameworkListener 桥接产出 outcome。 */
        GENERIC
    }

    private static final Logger log = LoggerFactory.getLogger(ReportBus.class);

    private static volatile ReportBus defaultBus;

    private final PwReportsConfig config;
    private final ScrubGate scrub;
    private final ConcurrentHashMap<String, RecordingSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ReportSink> sinks = new CopyOnWriteArrayList<>();
    private final ThreadLocal<StepHandle> current = new ThreadLocal<>();
    private final AtomicLong sinkErrors = new AtomicLong();
    private final AtomicBoolean runFinished = new AtomicBoolean(false);
    private volatile boolean enabled;

    /** 当前报告数据来源（运行级）。Cucumber 路置 CUCUMBER 以互斥 FrameworkListener 桥接。 */
    private volatile ReportSource source = ReportSource.GENERIC;

    public ReportBus(PwReportsConfig config) {
        this.config = config;
        this.scrub = new ScrubGate(config.getExtraScrubPatterns(), config.getMaxEvidenceLineBytes(), 256);
        this.enabled = config.isEnabled();
        if (this.enabled) {
            ConsoleCapture.install();
            sinks.add(new LocalReportSink(config));
            sinks.add(new LogSink());
            if (config.isPushEnabled() && config.getPushUrl() != null && !config.getPushUrl().isBlank()) {
                sinks.add(new HttpPushSink(config));
            }
        }
    }

    // ---------------------------------------------------------------- singleton

    /** 进程级默认总线（Cucumber 插件 / @Step dispatcher / glue 共用）。 */
    public static ReportBus getDefault() {
        ReportBus b = defaultBus;
        if (b == null) {
            synchronized (ReportBus.class) {
                b = defaultBus;
                if (b == null) {
                    b = new ReportBus(PwReportsConfig.load());
                    defaultBus = b;
                }
            }
        }
        return b;
    }

    /** 框架/测试装配入口：替换默认总线；旧总线的推送线程在此关闭，避免泄漏。 */
    public static void installDefault(ReportBus bus) {
        synchronized (ReportBus.class) {
            if (defaultBus != null) {
                defaultBus.close();
            }
            defaultBus = bus;
        }
    }

    /** 关闭所有 sink（推送线程等）。幂等，可重复调用。 */
    public void close() {
        for (ReportSink sink : sinks) {
            try {
                sink.close();
            } catch (Throwable t) {
                log.warn("sink {} close failed", sink.name(), t);
            }
        }
    }

    // ---------------------------------------------------------------- run lifecycle

    /** 开跑（Cucumber TestRunStarted 触发）。幂等：重复调用只重置状态。 */
    public void startRun() {
        if (!enabled) {
            return;
        }
        sessions.clear();
        runFinished.set(false);
    }

    /**
     * 收尾。永不抛（R4）。
     *
     * <p>顺序：① 快照全部 session → ② fan-out RunFinished（LocalReportSink 在其中完成
     * sweep 补写 / HTML 渲染 / summary / index / assets，每项独立 try/catch）。
     * 二次调用静默返回（ObjectFactory.stop 与插件 TestRunFinished 双触发安全）。
     */
    public void finishRun() {
        if (!enabled) {
            return;
        }
        if (!runFinished.compareAndSet(false, true)) {
            return;
        }
        try {
            List<TestOutcome> snapshot = new ArrayList<>();
            for (RecordingSession s : new HashMap<>(sessions).values()) {
                snapshot.add(s.toOutcome(scrub));
            }
            fanOut(new RunFinishedEvent(List.copyOf(snapshot)));
        } catch (Throwable t) {
            log.error("finishRun failed; JSON preserved where written", t);
        }
    }

    // ---------------------------------------------------------------- case lifecycle

    /**
     * 用例开始。冲突 id（outline 同名 / 重试插件重发）自动加 {@code -2/-3} 后缀（R7）。
     *
     * @param baseId    {@code slug(feature)-line-slug(scenario)}
     * @param feature   feature 名
     * @param scenario  场景名
     * @param tags      标签（不含 @）
     */
    public StepHandle startCase(String baseId, String feature, String scenario, List<String> tags) {
        return startCase(baseId, feature, scenario, tags, null);
    }

    public StepHandle startCase(String baseId, String feature, String scenario, List<String> tags, String featurePath) {
        if (!enabled) {
            return StepHandle.NOOP;
        }
        String safeBase = (baseId == null || baseId.isBlank()) ? "scenario" : baseId;
        RecordingSession session = placeSession(safeBase, feature, scenario, tags, featurePath);
        StepHandle handle = StepHandle.ofCase(session.outcomeId());
        current.set(handle);
        // 用例级控制台捕获（钩子输出等）：压栈，步骤开始时再压步骤 sink 盖在上层
        ConsoleCapture.clear();
        ConsoleCapture.push((isError, line) -> session.appendConsole(isError, scrub.scrubLine(line)));
        fanOut(new CaseStartedEvent(session.outcomeId(), feature, scenario));
        return handle;
    }

    /**
     * 标注某个 case 为 Scenario Outline 行（供 sink 端 {@code OutlineAggregator} 分组聚合）。
     * 幂等覆盖；总线未激活或 session 缺失时 no-op。表头 / 本行值暂不采集（缺省 null）。
     */
    public void markOutline(StepHandle handle, String outlineKey, int exampleRowNumber) {
        if (handle == null) {
            return;
        }
        RecordingSession s = sessions.get(handle.outcomeId());
        if (s != null) {
            s.markOutline(outlineKey, null, null, exampleRowNumber);
        }
    }

    /** 标注某 case 为手工用例（标签 @manual）：不执行、报告记录为手工。 */
    public void markManual(StepHandle handle) {
        if (handle == null) {
            return;
        }
        RecordingSession s = sessions.get(handle.outcomeId());
        if (s != null) {
            s.markManual();
        }
    }

    /** 浏览器日志写入指定 case（page console / page-error）；跨线程事件经 pw-web-ui 收尾 flush。 */
    public void appendBrowserConsole(StepHandle handle, boolean isError, String line) {
        if (handle == null || line == null) {
            return;
        }
        RecordingSession s = sessions.get(handle.outcomeId());
        if (s != null) {
            s.appendBrowserConsole(isError, line);
        }
    }

    /** 当前场景线程的用例句柄（lifecycle 收尾 flush 浏览器日志用）。 */
    public StepHandle currentHandle() {
        return current.get();
    }

    /** putIfAbsent 循环占位：并行 startCase 同 baseId 也保证 id 唯一。 */
    private RecordingSession placeSession(String baseId, String feature, String scenario,
                                          List<String> tags, String featurePath) {
        String id = baseId;
        int suffix = 1;
        while (true) {
            RecordingSession candidate = new RecordingSession(id, feature, scenario, tags, featurePath,
                    config.getMaxEvidenceLines(), config.getMaxApiOperations());
            RecordingSession prev = sessions.putIfAbsent(id, candidate);
            if (prev == null) {
                return candidate;
            }
            id = baseId + "-" + (++suffix);
            if (suffix > 999) {
                id = baseId + "-" + System.nanoTime(); // 理论兜底，不进正常路径
            }
        }
    }

    /**
     * 用例结束。
     *
     * @param durationMs Cucumber 侧权威耗时；{@code <= 0} 时回退本地计时
     */
    public void finishCase(StepHandle handle, TestResult result, Throwable error, long durationMs) {
        // 场景级回收：per-thread 共享步骤库实例随场景结束释放（线程池复用时下一场景拿全新实例，
        // 对齐 Serenity per-Scenario StepFactory；与总线 enabled / 句柄是否 NOOP 无关，必清）。
        PwStepFactory.clearSharedCacheForThread();
        if (!enabled || handle == null || handle.isNoop()) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return;
        }
        session.finish(result, error, durationMs);
        StepHandle cur = current.get();
        if (cur != null && cur.outcomeId().equals(handle.outcomeId())) {
            current.remove();
        }
        ConsoleCapture.clear();
        fanOut(new CaseFinishedEvent(session.toOutcome(scrub)));
    }

    /** @Before hook 失败：记录用例级根因（首个优先），最终 result 由 Cucumber 落 FAILED。 */
    public void recordBeforeHookFailure(StepHandle caseHandle, Throwable error) {
        if (!enabled || caseHandle == null || caseHandle.isNoop()) {
            return;
        }
        RecordingSession session = sessions.get(caseHandle.outcomeId());
        if (session != null) {
            session.recordRootFailure(error);
        }
    }

    /** @After hook 失败：用例级 warning 证据（不影响 result）。 */
    public void recordAfterHookWarning(StepHandle caseHandle, Throwable error) {
        if (!enabled || caseHandle == null || caseHandle.isNoop()) {
            return;
        }
        FailureLine line = describeThrowable(error);
        attachEvidence(caseHandle, "WARNING: hook failed: " + line);
    }

    /**
     * API 监控失败条目（跨线程安全：显式句柄版）——独立于 {@link #recordApiOperation}
     * 的失败通道，只收失败；进 per-case JSON 的 {@code apiMonitorFailures} 与
     * {@code monitor-failures.html}。用例级（不挂步骤树），≤200 条，入 bus 必经 ScrubGate。
     */
    public void recordApiMonitorFailure(StepHandle handle, String raw) {
        if (!enabled || handle == null || handle.isNoop()) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session != null) {
            session.appendApiMonitorFailure(scrub.scrubLine(raw));
        }
    }

    /** 场景线程内零参版：取当前句柄记录监控失败（异步线程禁止——会解析错场景）。 */
    public void recordApiMonitorFailure(String raw) {
        recordApiMonitorFailure(captureHandle(), raw);
    }

    // ---------------------------------------------------------------- steps

    /**
     * 业务步骤开始（@Step dispatcher 入口）。场景线程内隐式嵌套到当前句柄之下。
     *
     * @return 步骤句柄；总线未激活/不在用例内 → {@link StepHandle#NOOP}
     */
    public StepHandle stepStarted(String description) {
        return stepStarted(description, null);
    }

    public StepHandle stepStarted(String description, Integer lineNumber) {
        return stepStarted(description, lineNumber, null, null);
    }

    /**
     * 步骤开始（带 Gherkin 参数）。
     *
     * @param dataTable DataTable（行×列，含表头）；无则 null
     * @param docString DocString 内容；无则 null
     */
    public StepHandle stepStarted(String description, Integer lineNumber,
                                  List<List<String>> dataTable, String docString) {
        if (!enabled) {
            return StepHandle.NOOP;
        }
        StepHandle cur = current.get();
        if (cur == null || cur.isNoop()) {
            return StepHandle.NOOP;
        }
        RecordingSession session = sessions.get(cur.outcomeId());
        if (session == null) {
            return StepHandle.NOOP;
        }
        TestStep step = session.newStep(scrub.scrubLine(description), lineNumber, cur);
        step.setDataTable(dataTable);
        step.setDocString(docString);
        StepHandle handle = StepHandle.ofStep(cur.outcomeId(), step.getStepId(), cur);
        current.set(handle);
        // 步骤级控制台捕获：盖在用例级 sink 之上，本步骤内输出归本步骤
        ConsoleCapture.push((isError, line) -> session.appendStepConsole(step.getStepId(), isError, scrub.scrubLine(line)));
        return handle;
    }

    /**
     * 业务步骤结束。异常由 dispatcher 原样 rethrow（报告永不吞业务异常），此处仅记录。
     */
    public void stepFinished(StepHandle handle, TestResult result, Throwable error) {
        if (!enabled || handle == null || handle.isNoop() || handle.isCaseLevel()) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return;
        }
        session.finishStep(handle.stepId(), result, error);
        if (handle.equals(current.get())) {
            StepHandle parent = handle.parent();
            current.set(parent == null ? StepHandle.ofCase(handle.outcomeId()) : parent);
        }
        ConsoleCapture.pop();
    }

    // ---------------------------------------------------------------- evidence

    /** 场景线程内零参版：挂到当前步骤（无当前步骤 → 用例级）。 */
    public void attachEvidence(String raw) {
        attachEvidence(captureHandle(), raw);
    }

    /** 显式句柄版：异步安全（R2）。进异步前 captureHandle，回调内传回来。 */
    public void attachEvidence(StepHandle handle, String raw) {
        if (!enabled || handle == null || handle.isNoop()) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return;
        }
        String line = scrub.scrubLine(raw);
        if (handle.isCaseLevel()) {
            session.appendEvidence(line);
        } else {
            session.appendStepEvidence(handle.stepId(), line);
        }
    }

    /** API 操作记录（承接 SerenityReporter.recordApiOperation；≤500 条，脱敏同规则）。 */
    public void recordApiOperation(String raw) {
        recordApiOperation(captureHandle(), raw);
    }

    public void recordApiOperation(StepHandle handle, String raw) {
        if (!enabled || handle == null || handle.isNoop()) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session != null) {
            session.appendApiOperation(scrub.scrubLine(raw));
        }
    }

    /** RouteDsl 路由注册记录（承接框架 {@code ReportingBridge.recordRouteRegistration}；用例级，always-rendered）。 */
    public void recordRouteRegistration(String raw) {
        recordRouteRegistration(captureHandle(), raw);
    }

    public void recordRouteRegistration(StepHandle handle, String raw) {
        if (!enabled || handle == null || handle.isNoop() || raw == null || raw.isEmpty()) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session != null) {
            session.appendRouteRegistration(scrub.scrubLine(raw));
        }
    }

    /** 进异步前调用：拿当前句柄（不在用例内 → NOOP）。 */
    public StepHandle captureHandle() {
        StepHandle h = current.get();
        return h == null ? StepHandle.NOOP : h;
    }

    // ---------------------------------------------------------------- screenshots

    /** 当前截图模式下，给定步骤结果是否应当采集（框架侧 FailureScreenshotHandler 用）。 */
    public boolean screenshotWanted(TestResult result) {
        if (!enabled) {
            return false;
        }
        return switch (config.getScreenshotMode()) {
            case ON_FAILURE -> result != null && result.isFailure();
            case AFTER_EACH_STEP -> true;
            case DISABLED, MANUAL -> false;
        };
    }

    /**
     * 落一张截图（调用方已通过 {@link #screenshotWanted} 或 MANUAL 语义决策）。
     * 写 {@code <output>/<outcomeId>/screenshots/step-NNN.png}，step 记录相对文件名。
     * 任何 IO 失败只记日志——截图缺失不拖累用例。
     */
    public void attachScreenshot(StepHandle handle, byte[] png) {
        if (!enabled || handle == null || handle.isNoop() || png == null || png.length == 0) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return;
        }
        String name = session.nextScreenshotName();
        Path target = config.getOutputDir().resolve(session.outcomeId()).resolve("screenshots").resolve(name);
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.write(target, png);
            if (!handle.isCaseLevel()) {
                TestStep step = session.stepForWrite(handle.stepId());
                if (step != null) {
                    step.setScreenshot(name);
                }
            }
        } catch (Throwable t) {
            log.warn("attachScreenshot failed for {} ({} bytes): {}", session.outcomeId(), png.length, t.toString());
        }
    }

    // ---------------------------------------------------------------- attachments

    /**
     * 业务层塞 HTML 附件到当前步骤（如业务汇总表、DOM dump、自定义卡片）。
     * 内容过 ScrubGate，但标签不过滤（自己人看自己的产物）。
     */
    public void attachHtml(String name, String htmlContent) {
        attachHtml(captureHandle(), name, htmlContent);
    }

    public void attachHtml(StepHandle handle, String name, String htmlContent) {
        attachAttachment(handle, new TestAttachment(name, "text/html", scrub.scrubLine(htmlContent)));
    }

    /** 业务层塞纯文本附件（如 console log、业务 JSON dump）。 */
    public void attachText(String name, String textContent) {
        attachText(captureHandle(), name, textContent);
    }

    public void attachText(StepHandle handle, String name, String textContent) {
        attachAttachment(handle, new TestAttachment(name, "text/plain", scrub.scrubLine(textContent)));
    }

    private void attachAttachment(StepHandle handle, TestAttachment attachment) {
        if (!enabled || handle == null || handle.isNoop()) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return;
        }
        if (handle.isCaseLevel()) {
            session.appendAttachment(attachment);
        } else {
            session.appendStepAttachment(handle.stepId(), attachment);
        }
    }

    /**
     * 挂载 Playwright trace.zip 并设 {@code tracePath}（报告页 "Play trace" 按钮据此深链 Trace Viewer）。
     * <p>trace 已在报告输出目录内（默认框架 {@code traces/} 就在 outputDir 下）时<b>直接引用、不拷贝</b>，
     * 避免同一文件两份；仅当 trace 位于报告目录外（如 {@code PW_REPORT_OUTPUT} 与框架 traces/ 不一致）时，
     * 才拷到 {@code <outcomeId>/trace.zip} 保报告自包含。
     */
    public void attachTrace(StepHandle handle, java.nio.file.Path traceZip) {
        if (!enabled || handle == null || handle.isNoop() || traceZip == null) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return;
        }
        try {
            java.nio.file.Path outputDir = config.getOutputDir().toAbsolutePath().normalize();
            java.nio.file.Path traceAbs = traceZip.toAbsolutePath().normalize();
            java.nio.file.Path rel = outputDir.relativize(traceAbs);
            if (rel.getNameCount() > 0 && !rel.startsWith("..") && !rel.isAbsolute()) {
                //  trace 已在报告目录内 → 直接引用（相对 outputDir），消除同文件两份拷贝
                session.setTracePath(rel.toString().replace('\\', '/'));
            } else {
                //  trace 在报告目录外 → 拷进 per-case，保报告自包含
                java.nio.file.Path target = config.getOutputDir().resolve(session.outcomeId()).resolve("trace.zip");
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.copy(traceZip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                session.setTracePath(session.outcomeId() + "/trace.zip");
            }
        } catch (Throwable t) {
            log.warn("attachTrace failed for {}: {}", session.outcomeId(), t.toString());
        }
    }

    /**
     * 挂载一个可下载文件附件（如下载的文件）。文件会被拷到
     * {@code <outputDir>/<outcomeId>/<filename>}，并附一条指向它的文本证据。
     */
    public void attachDownloadableFile(StepHandle handle, Path file) {
        if (!enabled || handle == null || handle.isNoop() || file == null) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return;
        }
        try {
            Path target = config.getOutputDir()
                    .resolve(session.outcomeId())
                    .resolve(file.getFileName().toString());
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            attachText(handle, "DOWNLOAD: " + file.getFileName(),
                    "Downloadable file archived at " + session.outcomeId() + "/" + file.getFileName());
        } catch (Throwable t) {
            log.warn("attachDownloadableFile failed for {}: {}", session.outcomeId(), t.toString());
        }
    }

    /** 便捷版：场景线程内解析当前步骤句柄。 */
    public void attachDownloadableFile(Path file) {
        attachDownloadableFile(captureHandle(), file);
    }

    // ---------------------------------------------------------------- state

    /** session 内部快照（同包测试用；渲染走事件）。 */
    Map<String, RecordingSession> sessionsSnapshot() {
        return new HashMap<>(sessions);
    }

    /**
     * 全部用例的 POJO 投影（只读断言 API：测试门禁 / 框架接缝如
     * {@code AutoBrowserProcessor.readScenarioTags} 换靶后读取场景状态）。
     */
    public List<TestOutcome> outcomeSnapshots() {
        List<TestOutcome> out = new ArrayList<>();
        for (RecordingSession s : new HashMap<>(sessions).values()) {
            out.add(s.toOutcome(scrub));
        }
        return List.copyOf(out);
    }

    /** 已录用例数。 */
    public int sessionCount() {
        return sessions.size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 运行时标记报告数据来源（Cucumber 路由 {@code PwReportPlugin} 设置；默认 {@link ReportSource#GENERIC}）。 */
    public void setSource(ReportSource source) {
        this.source = source == null ? ReportSource.GENERIC : source;
    }

    /** 当前报告数据来源。 */
    public ReportSource getSource() {
        return source;
    }

    /** 运行时 kill-switch（测试用；生产走 pw.report.enabled 启动属性）。 */
    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public PwReportsConfig config() {
        return config;
    }

    public long sinkErrorCount() {
        return sinkErrors.get();
    }

    // ---------------------------------------------------------------- internals

    private record FailureLine(String type, String message) {
    }

    private static FailureLine describeThrowable(Throwable t) {
        if (t == null) {
            return new FailureLine("unknown", "");
        }
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            message = t.getClass().getName();
        }
        return new FailureLine(t.getClass().getSimpleName(), message);
    }

    private void fanOut(ReportEvent event) {
        for (ReportSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (Throwable t) {
                sinkErrors.incrementAndGet();
                log.warn("sink {} failed on {} (total sink errors: {})",
                        sink.name(), event.type(), sinkErrors.get(), t);
            }
        }
    }
}

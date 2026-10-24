package com.hsbc.cmb.hk.dbb.automation.framework.report.event;

import com.hsbc.cmb.hk.dbb.automation.framework.report.config.PwReportsConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.DownloadRecord;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestAttachment;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestOutcome;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestResult;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.TestStep;
import com.hsbc.cmb.hk.dbb.automation.framework.report.model.UserStory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.sink.HttpPushSink;
import com.hsbc.cmb.hk.dbb.automation.framework.report.sink.LocalReportSink;
import com.hsbc.cmb.hk.dbb.automation.framework.report.sink.LogSink;
import com.hsbc.cmb.hk.dbb.automation.framework.report.sink.ReportSink;
import com.hsbc.cmb.hk.dbb.automation.framework.report.step.PwStepFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.report.console.ConsoleCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
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

    /**
     * 跨线程可见的「最近启动的步骤」——{@code ThreadLocal} 只在本线程有效，而 Playwright 事件线程
     * （route 命中线程）没有该上下文。此 volatile 快照让事件线程也能把证据归到「当时正在跑的步骤」，
     * 是<b>最佳努力</b>归属：并行执行下可能落到另一线程的步骤，但总比全部落到用例级更接近真相。
     */
    private volatile StepHandle activeStep;

    /**
     * 跨线程可见的「当前活动用例 id」——与 {@link #activeStep} 同源，供<b>非测试线程</b>
     * （CloseGuard 的 {@code close-bounded-*} 收尾线程）在挂接证据（录屏）时定位归属。
     * 并行执行下取"最近开始的用例"，属最佳努力；总比因 ThreadLocal 缺失而整份丢弃强。
     */
    private volatile String activeOutcomeId;

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
            fanOut(new RunFinishedEvent(List.copyOf(orderForReport(collectOutcomes()))));
        } catch (Throwable t) {
            log.error("finishRun failed; JSON preserved where written", t);
        }
    }

    /**
     * 报告顺序确定化——{@link #finishRun} 与 {@link #outcomeSnapshots} 共用。
     *
     * <p>{@code sessions} 是 {@link ConcurrentHashMap}，{@code new HashMap<>(sessions).values()} 迭代是
     * <b>hash 序</b>：非确定性、无业务含义、跨运行不稳定，令 index / feature 分组 / per-case 列表 /
     * CSV / JUnit-XML 的顺序不可预期。改为两级稳定排序：
     * <ol>
     *   <li><b>同 feature 聚合</b>：feature 之间按该 feature 首个场景的 {@code startTime} 排序
     *       （保持执行流，而非字母序——报告读者关心"先跑了什么"）；</li>
     *   <li><b>feature 内按 {@code startTime}</b>（{@code RecordingSession#toOutcome} 产出 ISO-8601 UTC，
     *       同一次运行格式一致，字典序=时间序）。</li>
     * </ol>
     * {@code startTime} 为 {@code null}（未记录开始时间的兜底路径）或 feature 名缺失（归一为 {@code ""}）
     * 的场景仍确定性地参与排序（前者沉底、后者相邻聚合），比较器保持全序、不抛——
     * 不影响 {@code finishRun} 永不抛的不变式。输入列表不被修改。
     */
    static List<TestOutcome> orderForReport(List<TestOutcome> outcomes) {
        List<TestOutcome> ordered = new ArrayList<>(outcomes);
        Map<String, String> featureFirstSeen = new HashMap<>();
        for (TestOutcome o : ordered) {
            String feature = featureKey(o);
            //  HashMap.merge 不接受 null 值（startTime 可能为 null）→ 显式 get/put 取更早者
            featureFirstSeen.put(feature, earlierStart(featureFirstSeen.get(feature), o.getStartTime()));
        }
        ordered.sort(java.util.Comparator
                .comparing((TestOutcome o) -> featureFirstSeen.get(featureKey(o)),
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(TestOutcome::getStartTime,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return ordered;
    }

    /** 收集全部 session 的 POJO 投影（hash 序；排序见 {@link #orderForReport}）。 */
    private List<TestOutcome> collectOutcomes() {
        List<TestOutcome> out = new ArrayList<>();
        for (RecordingSession s : new HashMap<>(sessions).values()) {
            out.add(s.toOutcome(scrub));
        }
        return out;
    }

    /** feature 分组键：{@code userStory.storyName}；缺失归一为 {@code ""}（相邻聚合，按首场景时间参与排序）。 */
    private static String featureKey(TestOutcome o) {
        UserStory story = o.getUserStory();
        String name = story == null ? null : story.getStoryName();
        return name == null ? "" : name;
    }

    /** 取更早的 ISO-8601 时间串（同运行格式一致，字典序=时间序）；任一为 {@code null} 取另一个。 */
    private static String earlierStart(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) <= 0 ? a : b;
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
        activeOutcomeId = session.outcomeId();
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
            activeStep = null;
        }
        if (handle.outcomeId().equals(activeOutcomeId)) {
            activeOutcomeId = null;
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
        activeStep = handle;
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
            activeStep = parent == null ? StepHandle.ofCase(handle.outcomeId()) : parent;
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
            session.appendApiOperation(handle.stepId(), scrub.scrubLine(raw));
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
            //  注册发生在哪个步骤就归到哪个步骤（RouteDsl 在步骤内调用 → handle 为 step 级）；
            //  步骤上下文外（@Before 钩子等）stepId 为 null → 回落用例级列表。
            session.appendRouteRegistration(handle.stepId(), scrub.scrubLine(raw));
        }
    }

    /** 进异步前调用：拿当前句柄（不在用例内 → NOOP）。 */
    public StepHandle captureHandle() {
        StepHandle h = current.get();
        return h == null ? StepHandle.NOOP : h;
    }

    /**
     * 当前（或最近）活动步骤的 id —— 供<b>非测试线程</b>（Playwright 事件线程）在证据产生时抓取归属。
     *
     * <p>解析顺序：本线程 ThreadLocal（业务线程上精确）→ 跨线程 volatile 快照（事件线程上的最佳努力）。
     * 两者都取不到步骤上下文（如场景边界）时返回 {@code null}，调用方应按「用例级」处理。</p>
     */
    /**
     * 当前活动用例 id（<b>不依赖线程上下文</b>）——供收尾线程（CloseGuard worker）挂接录屏等证据。
     * 用例结束（finishCase）后清空；并行执行下取"最近开始的用例"（最佳努力）。
     */
    public String activeOutcomeId() {
        StepHandle local = current.get();
        if (local != null && !local.isNoop()) {
            return local.outcomeId();
        }
        return activeOutcomeId;
    }

    public String activeStepId() {
        StepHandle local = current.get();
        if (local != null && !local.isNoop() && !local.isCaseLevel()) {
            return local.stepId();
        }
        StepHandle shared = activeStep;
        if (shared != null && !shared.isNoop() && !shared.isCaseLevel()) {
            return shared.stepId();
        }
        return null;
    }

    /**
     * 按<b>指定的 stepId</b> 记录一条 API 操作（供跨线程证据在入队时已抓取归属的场景使用）。
     *
     * <p>与 {@link #recordApiOperation(StepHandle, String)} 的差别：句柄由调用方在证据产生时捕获，
     * 而非在 flush 时解析——否则场景收尾时 {@code current} 已回落到用例级，所有证据都会被算作
     * 「用例级兜底」，per-case 页的步骤下就永远看不到 Route Captured。</p>
     *
     * <p>stepId 为空 / 该步骤已不在索引中时，自动回落用例级（与 {@code appendApiOperation} 语义一致）。</p>
     */
    public void recordApiOperationByStep(String stepId, String raw) {
        if (!enabled) {
            return;
        }
        if (stepId == null || stepId.isBlank()) {
            recordApiOperation(captureHandle(), raw);
            return;
        }
        StepHandle handle = captureHandle();
        if (handle == null || handle.isNoop()) {
            return;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session != null) {
            session.appendApiOperation(stepId, scrub.scrubLine(raw));
        }
    }

    // ---------------------------------------------------------------- screenshots

    /** 当前截图模式下，给定步骤结果是否应当采集（框架侧 FailureScreenshotHandler 用）。 */
    public boolean screenshotWanted(TestResult result) {
        if (!enabled) {
            return false;
        }
        return switch (config.getScreenshotMode()) {
            case FOR_FAILURES -> result != null && result.isFailure();
            case AFTER_EACH_STEP, BEFORE_AND_AFTER_EACH_STEP -> true;
            case DISABLED -> false;
        };
    }

    /**
     * 落一张截图（调用方已通过 {@link #screenshotWanted} 或显式采集决策）。
     * 写 {@code <output>/<outcomeId>/screenshots/step-NNN[-<slug>].png}，step 记录相对文件名。
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
        // 先解析步骤（取描述用于可读截图名）；解析失败不影响截图落盘
        TestStep step = null;
        if (!handle.isCaseLevel()) {
            try {
                step = session.stepForWrite(handle.stepId());
            } catch (Throwable ignore) {
                step = null;
            }
        }
        String name = session.nextScreenshotName(step == null ? null : step.getDescription());
        Path target = config.getOutputDir().resolve(session.outcomeId()).resolve("screenshots").resolve(name);
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.write(target, png);
            if (step != null) {
                step.setScreenshot(name);
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
    /**
     * 当前用例的 trace 落点（{@code <outputDir>/<outcomeId>/trace.zip}）——经 {@code ReportingBridge#traceTarget()}
     * 指导框架<b>直接导出到此</b>，从源头避免"框架 traces/ + per-case"两份（也无须事后移动）。无活动句柄 /
     * 会话不存在时返回 {@code null}（框架落其固定 {@code traces/} 目录作证据兜底，非配置项）。
     */
    public Path traceTarget(StepHandle handle) {
        if (!enabled || handle == null || handle.isNoop()) {
            return null;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return null;
        }
        return config.getOutputDir().resolve(session.outcomeId()).resolve("trace.zip");
    }

    /**
     * 当前用例的截图落盘<b>目录</b>（{@code <outputDir>/<outcomeId>/screenshots}）——经
     * {@code ReportingBridge#screenshotTarget()} 指导框架把截图直接写到这里，
     * 不再散落在 {@code target/site/serenity} 根目录（2026-10-20 用户反馈）。
     *
     * <p>与 {@link #traceTarget} 一致的无活动句柄语义：返回 {@code null} 时框架自选位置兜底。
     * 目录<b>不</b>在此处创建（框架侧负责创建），仅返回目标路径。
     */
    public Path screenshotTarget(StepHandle handle) {
        if (!enabled || handle == null || handle.isNoop()) {
            return null;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return null;
        }
        return config.getOutputDir().resolve(session.outcomeId()).resolve("screenshots");
    }

    /**
     * 当前用例的录屏落点（{@code <outputDir>/<outcomeId>/records}）——经 {@code ReportingBridge#videoTarget()}
     * 指导框架<b>直接录到 per-case 目录</b>，从源头避免「配置的全局 videoDir 先落一份、再搬进
     * per-case」的两份与中途失败。无活动句柄 / 会话不存在时返回 {@code null}
     * （框架回落其配置的录屏目录，由 {@link #attachVideo} 事后搬入）。
     */
    public Path videoTarget(StepHandle handle) {
        if (!enabled || handle == null || handle.isNoop()) {
            return null;
        }
        RecordingSession session = sessions.get(handle.outcomeId());
        if (session == null) {
            return null;
        }
        Path dir = config.getOutputDir().resolve(session.outcomeId()).resolve("records");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            log.debug("videoTarget dir creation failed for {}: {}", dir, e.toString());
        }
        return dir;
    }

    /**
     * 按显式用例 id 解析 trace 落点（{@code <outputDir>/<outcomeId>/trace.zip}）——绕开 ThreadLocal 句柄，
     * 供收尾线程（CloseGuard worker）凭"跨线程可见的活动用例 id"把 trace <b>直接导出到 per-case</b>，
     * 从源头避免"traces/ + per-case"两份与事后移动（与 {@link #traceTarget} 同语义，仅入参不同）。
     * 无活动会话时返回 {@code null}（框架回落其固定 {@code traces/} 证据目录）。
     */
    public Path traceTargetByOutcomeId(String outcomeId) {
        if (!enabled || outcomeId == null || outcomeId.isBlank()) {
            return null;
        }
        RecordingSession session = sessions.get(outcomeId);
        if (session == null) {
            return null;
        }
        return config.getOutputDir().resolve(session.outcomeId()).resolve("trace.zip");
    }

    /**
     * 按显式用例 id 解析录屏落点（{@code <outputDir>/<outcomeId>/records}）——绕开 ThreadLocal 句柄，
     * 供收尾线程凭"跨线程可见的活动用例 id"把录屏<b>直接录进 per-case</b>（与 {@link #videoTarget} 同语义，
     * 仅入参不同）。无活动会话时返回 {@code null}（框架回落其配置的录屏目录，由 {@link #attachVideo} 事后搬入）。
     */
    public Path videoTargetByOutcomeId(String outcomeId) {
        if (!enabled || outcomeId == null || outcomeId.isBlank()) {
            return null;
        }
        RecordingSession session = sessions.get(outcomeId);
        if (session == null) {
            return null;
        }
        Path dir = config.getOutputDir().resolve(session.outcomeId()).resolve("records");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            log.debug("videoTargetByOutcomeId dir creation failed for {}: {}", dir, e.toString());
        }
        return dir;
    }

    /**
     * 登记场景录屏：把 webm 落入 {@code <outputDir>/<outcomeId>/records/} 并设
     * {@code videoPath}（报告页 “Playwright records” Tab 据此渲染播放器）。
     *
     * <p>与 {@link #attachTrace} 同策略：优先移动（消副本，录屏只有一份），失败则拷贝，
     * 绝不丢录像。任何 IO 失败只记日志。
     */
    public void attachVideo(StepHandle handle, java.nio.file.Path video) {
        if (!enabled || handle == null || video == null) {
            return;
        }
        if (handle.isNoop()) {
            //  兜底：关闭常发生在 CloseGuard 的 worker 线程（close-bounded-context-close），
            //   该线程没有 ThreadLocal 用例上下文 → captureHandle() 恒 NOOP → 录像被静默丢弃
            //   （2026-10-22 实测：dbb-1 有句柄却进不了报告）。改用跨线程可见的活动用例。
            attachVideoToOutcome(activeOutcomeId(), video);
            return;
        }
        attachVideoToOutcome(handle.outcomeId(), video);
    }

    /**
     * 按<b>用例 id</b> 直接登记录屏 —— 不依赖线程上下文。
     *
     * <p>存在理由：context 关闭跑在 CloseGuard 的有界 worker 线程上（{@code close-bounded-*}），
     * 那个线程没有 {@code ThreadLocal} 用例上下文，只能靠用例 id 定位归属。
     * 会话不存在（已 finishCase）时安全 no-op——录像本身已落盘，只是不进报告。</p>
     */
    /**
     * 把产物（录像 / trace）安全落入 per-case 目录，绝不丢产物；源文件不存在时跳过。
     * 落盘策略：{@code moveWhenOutsideReport=true}（录像）恒 move（顺带清理 Playwright 临时文件）、
     * 失败 copy 兜底；{@code false}（trace）仅当源落在报告目录<b>内</b>才 move（消副本），
     * 目录外则 copy（保留框架侧原件，见 {@code ReportBusTraceTargetTest}）。
     *
     * <p>{@code onMounted} 在落盘成功后回调，由调用方设置对应 path / meta 字段。统一此前
     * attachVideo / attachTrace 两套近乎一致的"建目录 + move/copy + 设字段"逻辑，消除职责重复（#2）。</p>
     */
    private void mountArtifact(String outcomeId, java.nio.file.Path source, String relativeTarget,
                              boolean moveWhenOutsideReport,
                              java.util.function.Consumer<RecordingSession> onMounted) {
        if (!enabled || outcomeId == null || outcomeId.isBlank() || source == null) {
            return;
        }
        RecordingSession session = sessions.get(outcomeId);
        if (session == null) {
            return;
        }
        try {
            if (!java.nio.file.Files.exists(source)) {
                log.debug("mountArtifact skipped (file absent): {}", source);
                return;
            }
            java.nio.file.Path target = config.getOutputDir().resolve(session.outcomeId()).resolve(relativeTarget);
            java.nio.file.Files.createDirectories(target.getParent());
            //  必须按绝对路径比较：report 输出目录常为相对路径，而 Playwright 产物为绝对路径；
            //  此前用 Path.equals 比较相对 vs 绝对永远不等 → 对自身执行 move → 失败被吞（2026-10-21 反馈）。
            if (!source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                boolean insideReport = source.toAbsolutePath().normalize()
                        .startsWith(config.getOutputDir().toAbsolutePath().normalize());
                boolean move = moveWhenOutsideReport || insideReport;
                if (move) {
                    try {
                        java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.io.IOException moveFailed) {
                        //  移动失败（文件被占用 / 跨卷）→ 拷贝兜底，绝不丢产物
                        java.nio.file.Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    java.nio.file.Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            onMounted.accept(session);
        } catch (Throwable t) {
            log.warn("mountArtifact failed for {} ({}): {}", session.outcomeId(), source, t.toString());
        }
    }

    public void attachVideoToOutcome(String outcomeId, java.nio.file.Path video) {
        mountArtifact(outcomeId, video, "records/" + video.getFileName().toString(), true,
                session -> session.setVideoPath(
                        session.outcomeId() + "/records/" + video.getFileName().toString()));
    }

    /**
     * 挂载 Playwright trace.zip 并设 {@code tracePath}（报告页 "Play trace" 按钮据此深链 Trace Viewer）。
     * <p>正常路径：框架已按 {@link #traceTarget} 把 trace 直接写到 {@code <outcomeId>/trace.zip}，
     * 本方法仅登记 {@code tracePath}（文件就位，不拷贝不移动——trace 全程只有一份）。
     * <p>兜底路径：调用方传来的是<b>其它位置</b>的文件（如外部直接调 API、或框架未指导落点而落到
     * 固定 {@code traces/} 后被消费）→ 落入 per-case：源在报告目录内则移动（消副本），目录外/移动失败则拷贝，
     * 绝不丢 trace。
     */
    public void attachTrace(StepHandle handle, java.nio.file.Path traceZip) {
        attachTrace(handle, traceZip, null, null, 0L);
    }

    /**
     * 挂载 trace 并附带录制窗口（起止时刻 + 时长）——报告页 Trace 卡片据此展示
     * 「录制窗口 / 时长」。任一时点为 {@code null} 时省略对应行，不影响 path / 播放按钮。
     */
    public void attachTrace(StepHandle handle, java.nio.file.Path traceZip,
                            String traceStartTime, String traceEndTime, long traceDurationMs) {
        if (!enabled || handle == null || traceZip == null) {
            return;
        }
        if (handle.isNoop()) {
            //  兜底：context 关闭常发生在 CloseGuard 的 worker 线程（close-bounded-*），
            //    该线程无 ThreadLocal 用例上下文 → captureHandle() 恒 NOOP → trace 被静默丢弃
            //    （与 attachVideo 同策略：改用跨线程可见的活动用例 id 定位归属，绝不丢 trace）。
            attachTraceToOutcome(activeOutcomeId(), traceZip, traceStartTime, traceEndTime, traceDurationMs);
            return;
        }
        attachTraceToOutcome(handle.outcomeId(), traceZip, traceStartTime, traceEndTime, traceDurationMs);
    }

    /**
     * 按<b>用例 id</b> 直接挂载 trace —— 不依赖线程上下文。
     *
     * <p>存在理由与 {@link #attachVideoToOutcome} 同：context 关闭跑在 CloseGuard 的有界 worker
     * 线程上（{@code close-bounded-*}），那个线程没有 {@code ThreadLocal} 用例上下文，只能靠用例 id
     * 定位归属。会话不存在（已 finishCase）时安全 no-op——trace 本身已落盘，只是不进报告。</p>
     */
    public void attachTraceToOutcome(String outcomeId, java.nio.file.Path traceZip,
                                     String traceStartTime, String traceEndTime, long traceDurationMs) {
        mountArtifact(outcomeId, traceZip, "trace.zip", false,
                session -> {
                    session.setTracePath(session.outcomeId() + "/trace.zip");
                    session.setTraceMeta(traceStartTime, traceEndTime, traceDurationMs);
                });
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
            //  结构化登记：供 per-case 报告「Downloads」Tab 罗列/链接下载文件（additive-only）。
            session.appendDownload(new DownloadRecord(
                    "DOWNLOAD: " + file.getFileName(),
                    file.getFileName().toString(),
                    session.outcomeId() + "/" + file.getFileName(),
                    Instant.now().toString()));
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
        return List.copyOf(orderForReport(collectOutcomes()));
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

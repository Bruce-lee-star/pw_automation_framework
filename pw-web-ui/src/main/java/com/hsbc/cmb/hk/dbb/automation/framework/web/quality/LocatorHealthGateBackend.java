package com.hsbc.cmb.hk.dbb.automation.framework.web.quality;

import io.cucumber.core.backend.Backend;
import io.cucumber.core.backend.Glue;
import io.cucumber.core.backend.HookDefinition;
import io.cucumber.core.backend.Snippet;
import io.cucumber.core.backend.StaticHookDefinition;
import io.cucumber.core.backend.TestCaseState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定位健康度门禁的 Cucumber 后端适配器：把 {@link LocatorHealthGate} 的策略挂到
 * <b>场景边界</b>与<b>运行收尾</b>两个原生钩子上，使「选择器回归」成为构建可拦的事实，
 * 而<b>不需要业务改一行代码、也不需要改 pom</b>。
 *
 * <h3>为什么是 Backend 而不是 Plugin</h3>
 * <ul>
 *   <li><b>零配置</b>：{@code io.cucumber.core.backend.BackendProviderService} 由 Cucumber 自己用
 *       {@code ServiceLoader} 发现（与 {@code cucumber-java} 的 Java backend 并列加载），
 *       不依赖 {@code @CucumberOptions(plugin = …)} 的显式声明；</li>
 *   <li><b>失败语义干净</b>：钩子抛错由 Cucumber 原生语义记为"该场景失败"，surefire/failsafe
 *       照常计数；而 {@code TestRunFinished} 插件处理器抛错会打断<b>同事件的其它处理器</b>
 *       （报告生成正在那里），代价是产物丢失——本类刻意不碰运行收尾事件。</li>
 * </ul>
 *
 * <h3>两个钩子的职责</h3>
 * <ol>
 *   <li>{@code after hook}（每个场景，order 最大＝最后执行）：累积快照与基线比对，
 *       <b>首次</b>发现回归即抛 {@link LocatorHealthRegressionException}；</li>
 *   <li>{@code after-all hook}（运行收尾）：基线缺失时按真实采集数据<b>播种</b>，
 *       并提示提交。播种失败只告警，绝不改变运行结果。</li>
 * </ol>
 *
 * <h3>防止自伤的三条状态机</h3>
 * <ul>
 *   <li>{@link #REPORTED}：回归只报一次，后续场景不连坐（并行场景由 CAS 保证唯一）；</li>
 *   <li>{@link #SEEDED_IN_THIS_JVM}：本次 JVM 刚播种过基线，则整个 JVM 内不再判定——
 *       否则"第一个 Runner 播种、第二个 Runner 立刻判红"；严格比对从<b>下一次</b>运行开始；</li>
 *   <li>无采集数据时不播种：写出空基线等于"假装武装"，下一轮会把所有真实违例判成 NEW。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：钩子本身无实例状态，跨线程状态只有两个 {@link AtomicBoolean}；
 * 并行场景下可安全并发执行。判定只读内存快照与基线文件，不触碰浏览器/DOM。
 */
public final class LocatorHealthGateBackend implements Backend {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocatorHealthGateBackend.class);

    /**
     * 钩子执行顺序。Cucumber 对 after / after-all 钩子按 order <b>升序</b>执行（已核对 7.34.4 字节码：
     * {@code CachingGlue} 用 {@code STATIC_HOOK_ORDER_ASCENDING} / {@code HOOK_ORDER_ASCENDING} 排序），
     * 故取最大值以保证本门禁在业务清理钩子<b>之后</b>看到尽量完整的数据。
     */
    static final int HOOK_ORDER_LAST = Integer.MAX_VALUE;

    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private static final AtomicBoolean SEEDED_IN_THIS_JVM = new AtomicBoolean();

    /** 本后端不定义步骤，故不参与"未定义步骤"的代码片段建议（返回空模板而非 null，避免 NPE）。 */
    private static final Snippet NO_SNIPPET = new Snippet() {
        @Override
        public MessageFormat template() {
            return new MessageFormat("");
        }

        @Override
        public String tableHint() {
            return "";
        }

        @Override
        public String arguments(Map<String, Type> argumentTypeMap) {
            return "";
        }

        @Override
        public String escapePattern(String pattern) {
            return pattern == null ? "" : pattern;
        }
    };

    @Override
    public void loadGlue(Glue glue, List<URI> gluePaths) {
        try {
            glue.addAfterHook(new ScenarioBoundaryGateHook());
            glue.addAfterAllHook(new BaselineSeedHook());
        } catch (Throwable t) {
            //  门禁属质量旁路：装配失败不允许把整轮运行拖垮（fail-open + 可见告警，便于发现 SPI 冲突）
            LOGGER.error("[locator-health] gate hook registration failed - gate disabled for this run", t);
        }
    }

    @Override
    public void buildWorld() {
        //  无 world 概念（本后端不提供步骤定义）
    }

    @Override
    public void disposeWorld() {
        //  无 world 概念
    }

    @Override
    public Snippet getSnippet() {
        return NO_SNIPPET;
    }

    // ---------------------------------------------------------------- 门禁行为

    /**
     * 场景边界判定（每个场景执行一次）。
     *
     * <p>仅当<b>首次</b>发现回归时抛异常；{@code SEEDED}（首次接入）与 {@code SKIPPED}（门禁关闭 /
     * 基线不可读）一律不失败。
     */
    static void evaluateAtScenarioBoundary() {
        if (!LocatorHealthGate.enabled() || REPORTED.get() || SEEDED_IN_THIS_JVM.get()) {
            return;
        }
        LocatorHealthGate.AutoResult result = LocatorHealthGate.autoCheck();
        if (result.status() == LocatorHealthGate.AutoResult.Status.SEEDED) {
            SEEDED_IN_THIS_JVM.set(true);
            LOGGER.warn("[locator-health] {}", result.message());
            return;
        }
        if (result.failed() && REPORTED.compareAndSet(false, true)) {
            LOGGER.error("[locator-health] {}", result.message());
            throw new LocatorHealthRegressionException(result.message(), result.regressions());
        }
    }

    /**
     * 运行收尾播种基线（基线已存在 / 门禁关闭 / 无采集数据时均不动）。
     *
     * <p>任何异常只 WARN：门禁自身的 IO 故障不允许改变本次运行结果。
     */
    static void seedAtRunEnd() {
        if (!LocatorHealthGate.enabled()) {
            return;
        }
        try {
            if (!LocatorHealthGate.hasCollectedData()) {
                LOGGER.info("[locator-health] no selector data collected - baseline not generated "
                        + "(gate stays unarmed until a run actually exercises locators)");
                return;
            }
            Path baseline = LocatorHealthGate.resolveBaseline();
            boolean existed = Files.exists(baseline);
            Path written = LocatorHealthGate.seedIfMissing();
            if (!existed && Files.exists(written)) {
                LOGGER.warn("[locator-health] baseline generated: {} ({} violating selector(s)) - "
                                + "commit it to arm the regression gate; this run is NOT judged",
                        written, LocatorHealthGate.currentEntries().size());
            }
        } catch (Exception | LinkageError e) {
            LOGGER.warn("[locator-health] baseline seed skipped: {}", e.toString());
        }
    }

    /** 测试隔离：复位"已报一次 / 本 JVM 已播种"两个状态机（生产代码不得调用）。 */
    static void resetForTest() {
        REPORTED.set(false);
        SEEDED_IN_THIS_JVM.set(false);
    }

    // ---------------------------------------------------------------- 钩子实现

    /** 场景边界门禁钩子（无实例状态，线程安全）。 */
    private static final class ScenarioBoundaryGateHook implements HookDefinition {

        @Override
        public void execute(TestCaseState testCaseState) {
            evaluateAtScenarioBoundary();
        }

        @Override
        public String getTagExpression() {
            return ""; // 空表达式＝对所有场景生效
        }

        @Override
        public int getOrder() {
            return HOOK_ORDER_LAST;
        }

        @Override
        public boolean isDefinedAt(StackTraceElement stackTraceElement) {
            return false; // 框架钩子，不参与用户 glue 的重定义/冲突判定
        }

        @Override
        public String getLocation() {
            return "locator-health-gate (framework after hook)";
        }
    }

    /** 运行收尾播种钩子（after-all）。 */
    private static final class BaselineSeedHook implements StaticHookDefinition {

        @Override
        public void execute() {
            seedAtRunEnd();
        }

        @Override
        public int getOrder() {
            return HOOK_ORDER_LAST;
        }

        @Override
        public boolean isDefinedAt(StackTraceElement stackTraceElement) {
            return false;
        }

        @Override
        public String getLocation() {
            return "locator-health-baseline-seed (framework after-all hook)";
        }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.execution;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * {@link StepPlanActionSink} 的线程局部装配点。
 *
 * <p><b>装配顺序（业务零钩子、默认不介入）</b>：</p>
 * <ol>
 *   <li>开关门控：{@link ConfigRegistry#AGENT_SEMANTIC_ENABLED} 默认关闭。关闭时不加载 SPI、
 *       不创建 sink、不注册 agent 生命周期监听器，日常用例完全不受影响；
 *       显式绑定（下一条）不受开关影响；</li>
 *   <li>显式绑定优先：测试运行时若经 {@link #setCurrent} 注入自定义实现（如录制/断言 sink），
 *       {@link #requireCurrent()} 直接返回；</li>
 *   <li>开关开启后，经 {@link StepPlanActionSinkProvider} SPI 按线程<b>惰性创建</b>框架默认实现。
 *       生成的 Steps 在字段初始化期就会调用 {@code requireCurrent()}，这个时点可能早于
 *       {@code TestCaseStarted} 与任何 {@code @Before}，故懒装配是唯一可靠的时机。</li>
 * </ol>
 *
 * <p>SPI 发现结果与「是否绑定」分开：{@link #clear()} 只清 ThreadLocal，不动 provider 缓存；
 * 框架生命周期监听器在场景开始/结束时调用 {@link #clear()}，使下一个复用的 worker 线程
 * 重新创建 sink，避免跨场景串用。ThreadLocal 与 Cucumber 并行场景的线程模型一致。</p>
 *
 * <p>SPI 属"可选集成"，发现过程对 {@link Throwable} 全面降级（含
 * {@code ServiceConfigurationError} / {@code ExceptionInInitializerError}），
 * 无实现时抛出可定位的未绑定异常，绝不静默空跑。</p>
 */
public final class StepPlanActionSinks {

    private static final Logger LOGGER = LoggerFactory.getLogger(StepPlanActionSinks.class);
    private static final ThreadLocal<StepPlanActionSink> CURRENT = new ThreadLocal<>();

    /** provider 与解析标记都必须 volatile：双检锁的快路径在 synchronized 之外读取。 */
    private static volatile StepPlanActionSinkProvider provider;
    private static volatile boolean providerResolved;

    private StepPlanActionSinks() {
    }

    public static void setCurrent(StepPlanActionSink sink) {
        CURRENT.set(Objects.requireNonNull(sink, "sink"));
    }

    public static StepPlanActionSink requireCurrent() {
        StepPlanActionSink sink = CURRENT.get();
        if (sink != null) {
            return sink;
        }
        sink = createDefault();
        CURRENT.set(sink);
        return sink;
    }

    /** 当前线程是否已绑定 sink（诊断 / 生命周期断言用）。 */
    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 包级私有测试 seam：显式注入 provider，绕过 ServiceLoader。 */
    static void setProviderForTest(StepPlanActionSinkProvider candidate) {
        synchronized (StepPlanActionSinks.class) {
            provider = candidate;
            providerResolved = true;
        }
    }

    /** 包级私有测试 seam：复位 SPI 缓存与当前线程绑定。 */
    static void resetProviderForTest() {
        synchronized (StepPlanActionSinks.class) {
            provider = null;
            providerResolved = false;
        }
        CURRENT.remove();
    }

    private static StepPlanActionSink createDefault() {
        if (!ConfigRegistry.AGENT_SEMANTIC_ENABLED.booleanValue()) {
            // 门控在 SPI 发现之前：默认关闭时连 provider 类都不加载，日常用例零开销。
            throw new IllegalStateException(
                    "语义 Agent 未启用，生成 case 的自动装配已关闭（默认关闭，不影响日常用例）。"
                            + "仅在使用 agent 按业务描述自主操作时开启：-D"
                            + ConfigRegistry.AGENT_SEMANTIC_ENABLED.key() + "=true"
                            + " 或环境变量 PW_AGENT_SEMANTIC_ENABLED=true；"
                            + "也可经 StepPlanActionSinks.setCurrent 显式绑定自定义 sink。");
        }
        StepPlanActionSinkProvider factory = resolveProvider();
        if (factory == null) {
            throw new IllegalStateException(
                    "StepPlanActionSink 未绑定：既无 StepPlanActionSinks.setCurrent，"
                            + "也无 META-INF/services/"
                            + StepPlanActionSinkProvider.class.getName() + " 实现");
        }
        StepPlanActionSink created;
        try {
            created = factory.create();
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "StepPlanActionSinkProvider " + factory.getClass().getName() + " 创建失败", t);
        }
        if (created == null) {
            throw new IllegalStateException(
                    "StepPlanActionSinkProvider " + factory.getClass().getName() + " 返回了 null");
        }
        return created;
    }

    private static StepPlanActionSinkProvider resolveProvider() {
        if (providerResolved) {
            return provider;
        }
        synchronized (StepPlanActionSinks.class) {
            if (providerResolved) {
                return provider;
            }
            try {
                ServiceLoader<StepPlanActionSinkProvider> loader = ServiceLoader.load(
                        StepPlanActionSinkProvider.class, StepPlanActionSinks.class.getClassLoader());
                Iterator<StepPlanActionSinkProvider> iterator = loader.iterator();
                while (true) {
                    try {
                        if (!iterator.hasNext()) {
                            break;
                        }
                        StepPlanActionSinkProvider candidate = iterator.next();
                        if (candidate != null) {
                            provider = candidate;
                        }
                        break;
                    } catch (Throwable t) {
                        LOGGER.debug("[StepPlanActionSinks] 跳过不可加载的 SPI provider: {}", t.toString());
                        break;
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("[StepPlanActionSinks] SPI 发现不可用: {}", t.toString());
            }
            providerResolved = true;
            return provider;
        }
    }
}

package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Agent 运行时角色的 SPI 解析 + 双检锁聚合 + no-op 回落（T5）。
 *
 * <p>与 T1 的 {@code AgentTraceRegistry} / 既有 {@code LocatorHealthReportRegistry} 同构：
 * {@link #resolve()} 的 {@code catch (Throwable)} 必须覆盖
 * {@link java.util.ServiceConfigurationError}——它是 {@link Error} 子类，普通
 * {@code catch (Exception)} 抓不住，会让本类 {@code <clinit>} 失败并导致此后每次访问只报
 * {@link NoClassDefFoundError} 而真实原因被掩盖。</p>
 *
 * <p><b>槽位聚合语义</b>：多个贡献者各提供部分角色时，按 ServiceLoader 发现顺序取
 * <b>第一个非 NO_OP</b> 的槽位；全部贡献者均无该槽位则为 NO_OP。这样两个兄弟模块可以
 * 各接一半而不互相依赖。</p>
 */
public final class AgentRuntimeContexts {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntimeContexts.class);

    private static volatile AgentRuntimeContext instance;

    private AgentRuntimeContexts() {
    }

    /** 当前生效的运行时组合根；无 SPI 贡献者时为 {@link AgentRuntimeContext#empty()}。 */
    public static AgentRuntimeContext current() {
        AgentRuntimeContext ctx = instance;
        if (ctx == null) {
            synchronized (AgentRuntimeContexts.class) {
                ctx = instance;
                if (ctx == null) {
                    ctx = resolve();
                    instance = ctx;
                }
            }
        }
        return ctx;
    }

    /** 测试 / 宿主可注入替身；传 {@code null} 复位为下次按 SPI 重新解析。 */
    public static void install(AgentRuntimeContext context) {
        synchronized (AgentRuntimeContexts.class) {
            instance = context;
        }
    }

    /** 复位，便于测试 teardown 后重新按 SPI 解析。 */
    public static void reset() {
        install(null);
    }

    private static AgentRuntimeContext resolve() {
        List<AgentRuntimeContext> contributed = new ArrayList<>();
        try {
            for (AgentRuntimeContextProvider provider : ServiceLoader.load(AgentRuntimeContextProvider.class)) {
                // 单个贡献者失败只跳过它自己：兄弟模块各有部分槽位，一个坏 provider 不得拖垮其余贡献。
                try {
                    AgentRuntimeContext ctx = provider.contribute();
                    if (ctx != null) {
                        contributed.add(ctx);
                    }
                } catch (Throwable t) {
                    LOGGER.debug("[agent-runtime] SPI provider {} failed, skipped: {}",
                            provider.getClass().getName(), t.toString());
                }
            }
        } catch (Throwable t) {
            // ServiceLoader 迭代本身抛 ServiceConfigurationError（Error 子类）时整体回落 empty。
            LOGGER.debug("[agent-runtime] SPI provider unavailable, falling back to empty context: {}",
                    t.toString());
            return AgentRuntimeContext.empty();
        }
        if (contributed.isEmpty()) {
            return AgentRuntimeContext.empty();
        }
        return merge(contributed);
    }

    /** 按槽位取首个非 NO_OP 贡献；任一槽位全为 NO_OP 时保持 NO_OP。 */
    private static AgentRuntimeContext merge(List<AgentRuntimeContext> contexts) {
        AgentRouteControl route = AgentRouteControl.NO_OP;
        AgentSessionControl session = AgentSessionControl.NO_OP;
        AgentConcurrencyControl concurrency = AgentConcurrencyControl.NO_OP;
        AgentMonitorControl monitor = AgentMonitorControl.NO_OP;
        for (AgentRuntimeContext ctx : contexts) {
            if (route == AgentRouteControl.NO_OP && ctx.route() != AgentRouteControl.NO_OP) {
                route = ctx.route();
            }
            if (session == AgentSessionControl.NO_OP && ctx.session() != AgentSessionControl.NO_OP) {
                session = ctx.session();
            }
            if (concurrency == AgentConcurrencyControl.NO_OP
                    && ctx.concurrency() != AgentConcurrencyControl.NO_OP) {
                concurrency = ctx.concurrency();
            }
            if (monitor == AgentMonitorControl.NO_OP && ctx.monitor() != AgentMonitorControl.NO_OP) {
                monitor = ctx.monitor();
            }
        }
        return new AgentRuntimeContext(route, session, concurrency, monitor);
    }
}

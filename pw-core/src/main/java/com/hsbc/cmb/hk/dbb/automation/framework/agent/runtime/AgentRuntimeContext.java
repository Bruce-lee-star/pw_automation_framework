package com.hsbc.cmb.hk.dbb.automation.framework.agent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * <b>Agent 执行链运行时组合根</b>（T5 / I-06）：把四个业务链能力以<b>实例角色</b>注入执行链，
 * 消除「执行链与业务链各持一套路由/会话/并发/监控状态」的孤岛。
 *
 * <p>这是本任务「静态工具类改实例方法」的落点：执行链不再直接调用散落的静态门面，而是经本
 * <b>不可变实例</b>的四个角色槽位编排；每个槽位由下游模块以适配器包装其静态组件而成，
 * 因此同一 JVM 内执行链与业务链共享同一份状态（同一把 ConcurrencyGate、同一个
 * RouteLifecycleRegistry、同一份 MonitorFailure 归集），语义一致。</p>
 *
 * <h2>为什么放在 pw-core</h2>
 * <p>Route / MonitorSink 属 pw-route，SessionStore / ConcurrencyGate 属 pw-web-ui，二者是
 * <b>互不依赖的兄弟模块</b>，且 pw-core 不能反向依赖它们。故本类只定义角色契约
 * （{@link AgentRouteControl} 等），由 {@link AgentRuntimeContexts} 经 SPI 聚合下游贡献者
 * ——与 T1 的 {@code AgentTraceSink} / 既有 {@code RouteLifecycleRegistry} 同构，
 * <b>不改任何模块 pom 依赖图</b>。</p>
 *
 * <h2>生命周期编排（fail-safe）</h2>
 * <ol>
 *   <li>进入顺序固定：Concurrency → Session → Route → Monitor；</li>
 *   <li>若某槽位进入抛异常，<b>仅回滚已成功进入的槽位</b>（逆序），再原样抛出；</li>
 *   <li>执行体正常：逆序退出，任一退出异常不掩盖其余退出，最终以首个异常抛出（其余 suppressed）；</li>
 *   <li>执行体异常：逆序退出，退出异常全部 <b>suppressed</b> 到原异常后重抛原异常，避免掩盖真实失败。</li>
 * </ol>
 *
 * <p>默认 {@link #empty()} 四槽全 no-op，<b>逐字保持 T5 之前的行为</b>；生产组合根
 * （{@code AssetVerificationWiring}）显式注入 SPI 聚合结果，保证全量回归零回归。</p>
 */
public final class AgentRuntimeContext {

    private static final AgentRuntimeContext EMPTY = new AgentRuntimeContext(
            AgentRouteControl.NO_OP, AgentSessionControl.NO_OP,
            AgentConcurrencyControl.NO_OP, AgentMonitorControl.NO_OP);

    private final AgentConcurrencyControl concurrency;
    private final AgentSessionControl session;
    private final AgentRouteControl route;
    private final AgentMonitorControl monitor;

    /** 四个角色槽位显式注入；任一为 {@code null} 抛 {@link NullPointerException}。 */
    public AgentRuntimeContext(AgentRouteControl route, AgentSessionControl session,
                               AgentConcurrencyControl concurrency, AgentMonitorControl monitor) {
        this.route = Objects.requireNonNull(route, "route");
        this.session = Objects.requireNonNull(session, "session");
        this.concurrency = Objects.requireNonNull(concurrency, "concurrency");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    /** 全 no-op 实例（默认、零回归）。 */
    public static AgentRuntimeContext empty() {
        return EMPTY;
    }

    public AgentRouteControl route() {
        return route;
    }

    public AgentSessionControl session() {
        return session;
    }

    public AgentConcurrencyControl concurrency() {
        return concurrency;
    }

    public AgentMonitorControl monitor() {
        return monitor;
    }

    /**
     * 在四角色作用域内执行动作，负责进入 / 退出 / 异常回滚的完整编排。
     *
     * @param scope  本次执行作用域（不可变）
     * @param action 执行体
     * @return 执行体结果
     */
    public <T> T runInScope(AgentRuntimeScope scope, Supplier<T> action) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");

        List<AgentRuntimeParticipant> opened = new ArrayList<>(4);
        try {
            enter(concurrency, scope, opened);
            enter(session, scope, opened);
            enter(route, scope, opened);
            enter(monitor, scope, opened);
        } catch (RuntimeException | Error e) {
            rollbackOnEnterFailure(opened, scope, e);
            throw e;
        }

        Throwable failure = null;
        T result = null;
        try {
            result = action.get();
        } catch (RuntimeException | Error e) {
            failure = e;
        }
        exit(opened, scope, failure);
        if (failure != null) {
            throwUnchecked(failure);
        }
        return result;
    }

    /** 无返回值重载。 */
    public void runInScope(AgentRuntimeScope scope, Runnable action) {
        Objects.requireNonNull(action, "action");
        runInScope(scope, () -> {
            action.run();
            return null;
        });
    }

    /** 按固定顺序进入一个槽位，成功后才登记进 opened（供精确回滚）。 */
    private static void enter(AgentRuntimeParticipant participant, AgentRuntimeScope scope,
                              List<AgentRuntimeParticipant> opened) {
        participant.onScopeOpen(scope);
        opened.add(participant);
    }

    /** 进入阶段失败：逆序退出已成功槽位，退出异常 suppressed 到原异常，不掩盖根因。 */
    private static void rollbackOnEnterFailure(List<AgentRuntimeParticipant> opened,
                                               AgentRuntimeScope scope, Throwable cause) {
        for (int i = opened.size() - 1; i >= 0; i--) {
            try {
                opened.get(i).onScopeClose(scope, cause);
            } catch (RuntimeException | Error closeFailure) {
                if (closeFailure != cause) {
                    cause.addSuppressed(closeFailure);
                }
            }
        }
    }

    /** 逆序退出全部槽位；每个槽位独立 try/catch，保证一个槽位失败不阻断其余释放。 */
    private static void exit(List<AgentRuntimeParticipant> opened, AgentRuntimeScope scope,
                             Throwable failure) {
        Throwable firstCloseFailure = null;
        for (int i = opened.size() - 1; i >= 0; i--) {
            try {
                opened.get(i).onScopeClose(scope, failure);
            } catch (RuntimeException | Error closeFailure) {
                if (firstCloseFailure == null) {
                    firstCloseFailure = closeFailure;
                } else if (firstCloseFailure != closeFailure) {
                    firstCloseFailure.addSuppressed(closeFailure);
                }
            }
        }
        if (firstCloseFailure == null) {
            return;
        }
        if (failure != null) {
            // 不掩盖执行体真实失败
            failure.addSuppressed(firstCloseFailure);
        } else {
            throwUnchecked(firstCloseFailure);
        }
    }

    /** 把 catch 到的 RuntimeException/Error 原样重抛（避免包装改变异常契约）。 */
    private static void throwUnchecked(Throwable t) {
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error err) {
            throw err;
        }
        throw new IllegalStateException(t);
    }
}
